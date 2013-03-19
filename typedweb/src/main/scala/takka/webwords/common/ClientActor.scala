package takka.webwords.common

import java.net.URL

//import akka.actor.{ Index => _, _ }
import takka.actor._
import akka.dispatch._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise, promise}
import scala.util._

sealed trait ClientActorIncoming
case class GetIndex(url: String, skipCache: Boolean) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index], cacheHit: Boolean) extends ClientActorOutgoing

case class GetWithoutCache(sender:ActorRef)
case class GetFromCacheOrElse(sender:ActorRef)

/**
 * This actor encapsulates:
 *  - checking the cache for an index of a certain URL
 *  - asking the indexer worker process to index the URL if it's not cached
 *  - checking the cache again when the worker is done
 * It coordinates a WorkQueueClientActor and IndexStorageActor to accomplish
 * this.
 */
class ClientActor(config: WebWordsConfig) extends TypedActor[GetIndex] {
    implicit val timeout = akka.util.Timeout(60 second)
    import ExecutionContext.Implicits.global
    
    var client:Option[ActorRef[SpiderAndCache]] = None
    var cache:Option[ActorRef[FetchCachedIndex]] = None
        
    import ClientActor._
    
    override def receive = {
        case GetIndex(url, skipCache) =>
          val replyto = sender
          // we look in the cache, if that fails, ask spider to
          // spider and then notify us, and then we look in the
          // cache again.
          def getWithoutCache = {
            getFromWorker(client.get, url) flatMap { _ =>
              getFromCacheOrElse(cache.get, url, cacheHit = false) {
                Future(GotIndex(url, index = None, cacheHit = false))
              }
            }
          }

          val futureGotIndex = if (skipCache)
            getWithoutCache
          else
            getFromCacheOrElse(cache.get, url, cacheHit = true) { getWithoutCache }

          futureGotIndex onComplete{
            case Success(gotIndex) =>
              println("=== Client Actor send "+replyto+" with "+gotIndex)
              
              replyto ! gotIndex
            case Failure(e) =>
              println("=== Client Actor failed with "+e)              
          }
//        case m => 
//          println("=== Client Actor received "+m)
    }

    override def preStart = {
//      println("=== start:"+self)
        client = Some(typedContext.actorOf(Props[SpiderAndCache](new WorkQueueClientActor(config.amqpURL)), "client"))
        cache= Some(typedContext.actorOf(Props[FetchCachedIndex](new IndexStorageActor(config.mongoURL)), "cache"))
    }

    override def postStop = {
//      println("=== stop:"+self)
        typedContext.stop(client.get)
        typedContext.stop(cache.get)
    }
}

object ClientActor {
    implicit val timeout = akka.util.Timeout(60 second)
    import ExecutionContext.Implicits.global
    
    private def getFromCacheOrElse(cache: ActorRef[FetchCachedIndex], url: String, cacheHit: Boolean)(fallback: => Future[GotIndex]): Future[GotIndex] = {
        cache ? FetchCachedIndex(url) flatMap {
            case CachedIndexFetched(Some(index)) =>
                Future(GotIndex(url, Some(index), cacheHit))
            case CachedIndexFetched(None) =>
                fallback
        }
    }

    private def getFromWorker(client: ActorRef[SpiderAndCache], url: String): Future[Unit] = {
        client ? SpiderAndCache(url) map {
            case SpideredAndCached(returnedUrl) =>
                Unit
        }
    }
}