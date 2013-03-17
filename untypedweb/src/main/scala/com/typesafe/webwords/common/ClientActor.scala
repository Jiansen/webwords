package com.typesafe.webwords.common

import java.net.URL

//import akka.actor.{ Index => _, _ }
import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise, promise}
import scala.util._

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
    implicit val timeout = akka.util.Timeout(60 second)
    import ExecutionContext.Implicits.global
    
    var client:ActorRef = context.actorFor("client")
    var cache:ActorRef = context.actorFor("cache")
        
    override def receive = {
        case incoming: ClientActorIncoming =>
            incoming match {
                case GetIndex(url, skipCache) =>
// println("ClientActor receiving message: "+GetIndex(url, skipCache))
                    // we look in the cache, if that fails, ask spider to
                    // spider and then notify us, and then we look in the
                    // cache again.
                    def getWithoutCache:GotIndex = {
                      var gotIndex:GotIndex = GotIndex(url, index = None, cacheHit = false)
                      client ? SpiderAndCache(url) onComplete {
                        case Success(SpideredAndCached(returnedUrl)) =>
//                          println("ClientActor: Spiderd URL recieved "+returnedUrl)
                          getFromCacheOrElse(cache, url, cacheHit = false) { 
                              GotIndex(url, index = None, cacheHit = false)
                          }
                        case other => println("=== getWithoutCache: unexpected result "+other)
                      }
                      gotIndex
                    }

                    val gotIndex:GotIndex = if (skipCache){
//                      println("=== GetWithoutCache")
                      getWithoutCache        
                    }else{
                      println("=== GetFromCacheOrElse")
                      getFromCacheOrElse(cache, url, cacheHit = true) { getWithoutCache }                      
                    }
// println("=== reply to "+sender+" with "+gotIndex)
                    sender ! gotIndex
            }
    }

    override def preStart = {
//      println("=== start:"+self)
        client = context.actorOf(Props(new WorkQueueClientActor(config.amqpURL)), "client")
        cache= context.actorOf(Props(new IndexStorageActor(config.mongoURL)), "cache")
    }

    override def postStop = {
//      println("=== stop:"+self)
        context.stop(client)
        context.stop(cache)
    }
    
    

    private def getFromCacheOrElse(cache: ActorRef, url: String, cacheHit: Boolean)(fallback: => GotIndex): GotIndex = {
      println("=== ClientActor: cache is "+cache)
      var gotIndex:GotIndex = fallback
        cache ? FetchCachedIndex(url) onComplete {
            case Success(CachedIndexFetched(Some(index))) =>
println("=== Client Actor receive "+index)              
              gotIndex =  GotIndex(url, Some(index), cacheHit)
            case Success(CachedIndexFetched(None)) =>
println("=== Client Actor receive None")              
              gotIndex = fallback
            case other =>
println("=== Client Actor getFromCacheOrElse: fix me: should not receive "+other)
              gotIndex = fallback
        }
      gotIndex
    }
/*
    private def getFromWorker(client: ActorRef, url: String): Future[Unit] = {
        client ? SpiderAndCache(url) onComplete {
            case Success(SpideredAndCached(returnedUrl)) =>
              println("ClientActor: Spiderd recieved")
              Future(Unit)
        }
    }
    */
}