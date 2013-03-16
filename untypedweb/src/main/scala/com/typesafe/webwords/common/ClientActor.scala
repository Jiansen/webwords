package com.typesafe.webwords.common

import java.net.URL

//import akka.actor.{ Index => _, _ }
import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

sealed trait ClientActorIncoming
case class GetIndex(url: String, skipCache: Boolean) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index], cacheHit: Boolean) extends ClientActorOutgoing

/**
 * This actor encapsulates:
 *  - checking the cache for an index of a certain URL
 *  - asking the indexer worker process to index the URL if it's not cached
 *  - checking the cache again when the worker is done
 * It coordinates a WorkQueueClientActor and IndexStorageActor to accomplish
 * this.
 */
class ClientActor(config: WebWordsConfig) extends Actor {
    import ClientActor._
    import ExecutionContext.Implicits.global
    
    var client:ActorRef = context.actorFor("client")
    var cache:ActorRef = context.actorFor("cache")
        
    override def receive = {
        case incoming: ClientActorIncoming =>
            incoming match {
                case GetIndex(url, skipCache) =>
println("receiving message: "+GetIndex(url, skipCache))
                    // we look in the cache, if that fails, ask spider to
                    // spider and then notify us, and then we look in the
                    // cache again.
                    def getWithoutCache = {
                        getFromWorker(client, url) flatMap { _ =>
                            getFromCacheOrElse(cache, url, cacheHit = false) {
                              Future(GotIndex(url, index = None, cacheHit = false))
                            }
                        }
                    }

                    val futureGotIndex = if (skipCache)
                        getWithoutCache
                    else
                        getFromCacheOrElse(cache, url, cacheHit = true) { getWithoutCache }

//                    self.channel.replyWith(futureGotIndex)
                    sender ! futureGotIndex
            }
    }

    override def preStart = {
      println("=== start:"+self)
        client = context.actorOf(Props(new WorkQueueClientActor(config.amqpURL)), "client")
        cache= context.actorOf(Props(new IndexStorageActor(config.mongoURL)), "cache")
    }

    override def postStop = {
      println("=== stop:"+self)
        context.stop(client)
        context.stop(cache)
    }
}

object ClientActor {
    implicit val timeout = akka.util.Timeout(60 second)
    import ExecutionContext.Implicits.global
    private def getFromCacheOrElse(cache: ActorRef, url: String, cacheHit: Boolean)(fallback: => Future[GotIndex]): Future[GotIndex] = {
        cache ? FetchCachedIndex(url) flatMap {
            case CachedIndexFetched(Some(index)) =>
              Future(GotIndex(url, Some(index), cacheHit))
            case CachedIndexFetched(None) =>
                fallback
        }
    }

    private def getFromWorker(client: ActorRef, url: String): Future[Unit] = {
        client ? SpiderAndCache(url) map {
            case SpideredAndCached(returnedUrl) =>
                Unit
        }
    }
}
