package takka.webwords.indexer

import java.io.File
import java.net.URI
import java.net.URL

import takka.webwords.common._

import takka.actor._
import akka.dispatch._
import akka.actor.ActorLogging
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._
import scala.concurrent.{Future, promise, future}
import akka.pattern.ask
import scala.util._
import scala.collection.mutable.HashMap

sealed trait SpiderRequest
case class Spider(url: URL) extends SpiderRequest

sealed trait SpiderReply
case class Spidered(url: URL, index: Index)

/**
 * This actor coordinates URLFetcher and IndexerActor actors in order
 * to do a shallow spider of a site and compute an "index" for the
 * site. An index is a list of word counts and a list of links found
 * on the site.
 *
 * There's some list processing in functional style in this file
 * that may be of interest if you're learning about Scala and
 * functional programming.
 */
class SpiderActor
    extends TypedActor[SpiderRequest] {
    private var indexer:Option[ActorRef[IndexerRequest]] = None
//    private var fetcher:Option[ActorRef] = None
    
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = akka.util.Timeout(60 second)
    
    override def preStart() = {
      indexer = Some(typedContext.actorOf(Props[IndexerRequest, IndexerActor], "indexer"))
//      fetcher = Some(context.actorOf(Props[URLFetcher], "fetcher"))
    }

    override def postStop() = {
      typedContext.stop(indexer.get)
//      context.stop(fetcher.get)
    }

//    val bodyFuture = promise[String]
    val bodyPromises = new HashMap[URL, Promise[String]]
    
    override def typedReceive = {
      case Spider(url) =>
//                self.channel.replyWith(SpiderActor.spider(indexer, fetcher, url))            
        sender ! spider(indexer.get, url, typedSelf)
println("=== SpiderActor: replyed to " +sender)
        
        

        def spider(indexer: ActorRef[IndexerRequest], url: URL, spider:ActorRef[SpiderRequest]):Spidered = {
println("=== spidering")      
          val rootIndex = fetchIndex(indexer, url) 
          val childIndexes:Seq[Index] = SpiderActor.childLinksToFollow(url, rootIndex) map { fetchIndex(indexer, _) }
          val allIndexes:Seq[Index] = childIndexes ++ Iterator(rootIndex)
          val combinedIndex = SpiderActor.combineIndexes(allIndexes)
println("=== get spidered: "+Spidered(url, combinedIndex))
          Spidered(url, combinedIndex)
        }
        
        def fetchIndex(indexer: ActorRef[IndexerRequest], url: URL): Index = {
          implicit val timeout = akka.util.Timeout(60 seconds)
          val result:Promise[Index] = promise()
          
          URLFetcher.fetchURL(url)(context.dispatcher) map {
              case URLFetched(url, status, headers, body) if status == 200 =>
                Await.result(indexer ? IndexHtml(url, body), timeout.duration) match {
                  case IndexedHtml(index) =>                    
                    result success index
                }
              case URLFetched(url, status, headers, body) =>
                throw new Exception("Failed to fetch, status: " + status)
              case whatever =>
                throw new IllegalStateException("Unexpected reply to url fetch: " + whatever)
          }
          Await.result(result.future, 60 second)
        }
    }
}

object SpiderActor {    
    // pick a few links on the page to follow, preferring to "descend"
    private def childLinksToFollow(url: URL, index: Index): Seq[URL] = {
        val uri = removeFragment((url.toURI))
        val siteRoot = copyURI(uri, path = Some(null))
        val parentPath = new File(uri.getPath).getParent
        val parent = if (parentPath != null) copyURI(uri, path = Some(parentPath)) else siteRoot

        val sameSiteOnly = index.links map {
            kv => kv._2
        } map {
            new URI(_)
        } map {
            removeFragment(_)
        } filter {
            _ != uri
        } filter {
            isBelow(siteRoot, _)
        } sortBy {
            pathDepth(_)
        }
        val siblingsOrChildren = sameSiteOnly filter { isBelow(parent, _) }
        val children = siblingsOrChildren filter { isBelow(uri, _) }

        // prefer children, if not enough then siblings, if not enough then same site
        val toFollow = (children ++ siblingsOrChildren ++ sameSiteOnly).distinct take 10 map { _.toURL }
        toFollow
    }

    private[indexer] def combineSortedCounts(sortedA: List[(String, Int)], sortedB: List[(String, Int)]): List[(String, Int)] = {
        if (sortedA == Nil) {
            sortedB
        } else if (sortedB == Nil) {
            sortedA
        } else {
            val aHead = sortedA.head
            val bHead = sortedB.head
            val aWord = aHead._1
            val bWord = bHead._1

            if (aWord == bWord) {
                (aWord -> (aHead._2 + bHead._2)) :: combineSortedCounts(sortedA.tail, sortedB.tail)
            } else if (aWord < bWord) {
                aHead :: combineSortedCounts(sortedA.tail, sortedB)
            } else {
                bHead :: combineSortedCounts(sortedA, sortedB.tail)
            }
        }
    }

    private[indexer] def combineCounts(a: Seq[(String, Int)], b: Seq[(String, Int)]) = {
        combineSortedCounts(a.toList.sortBy(_._1), b.toList.sortBy(_._1)).sortBy(0 - _._2)
    }

    private[indexer] def mergeIndexes(a: Index, b: Index): Index = {
        val links = (a.links ++ b.links).sortBy(_._1).distinct

        // ideally we might combine and count length at the same time, but we'll live
        val counts = combineCounts(a.wordCounts, b.wordCounts).take(math.max(a.wordCounts.length, b.wordCounts.length))
        Index(links, counts)
    }

    private def combineIndexes(indexes: TraversableOnce[Index]): Index = {
        indexes.reduce(mergeIndexes(_, _))
    }



    // this lets us copy URIs changing one part of the URI via keyword.
    private def copyURI(uri: URI, scheme: Option[String] = None, userInfo: Option[String] = None,
        host: Option[String] = None, port: Option[Int] = None, path: Option[String] = None,
        query: Option[String] = None, fragment: Option[String] = None): URI = {
        new URI(if (scheme.isEmpty) uri.getScheme else scheme.get,
            if (userInfo.isEmpty) uri.getUserInfo else userInfo.get,
            if (host.isEmpty) uri.getHost else host.get,
            if (port.isEmpty) uri.getPort else port.get,
            if (path.isEmpty) uri.getPath else path.get,
            if (query.isEmpty) uri.getQuery else query.get,
            if (fragment.isEmpty) uri.getFragment else fragment.get)
    }

    private[indexer] def removeFragment(uri: URI) = {
        if (uri.getFragment != null)
            copyURI(uri, fragment = Some(null))
        else
            uri
    }

    private[indexer] def isBelow(uri: URI, possibleChild: URI) = {
        val r = uri.relativize(possibleChild)
        !r.isAbsolute && uri != possibleChild
    }

    private[indexer] def pathDepth(uri: URI) = {
        uri.getPath.count(_ == '/')
    }
}
