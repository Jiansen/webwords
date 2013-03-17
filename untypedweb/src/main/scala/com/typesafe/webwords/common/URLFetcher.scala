package com.typesafe.webwords.common

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import com.ning.http.client._
import akka.actor._
import akka.dispatch._
// import akka.event.EventHandler
import akka.event.Logging
import scala.concurrent.{Future, Promise, promise, future}
import scala.util._

sealed trait URLFetcherIncoming
case class FetchURL(u: URL, replyTo:ActorRef) extends URLFetcherIncoming

sealed trait URLFetcherOutgoing
case class URLFetched(url:URL, status: Int, headers: Map[String, String], body: String) extends URLFetcherOutgoing

/**
 * This is an actor which encapsulates the AsyncHttpClient library.
 */
class URLFetcher extends Actor {
    import scala.concurrent.ExecutionContext.Implicits.global
    private val asyncHttpClient = URLFetcher.makeClient(context.dispatcher)

    override def receive = {
        case incoming: URLFetcherIncoming => incoming match {
          case FetchURL(u, replyTo) =>
               URLFetcher.fetchURL(asyncHttpClient, u) onComplete{
                 case Success(fetched) => 
//                   println("=== URLFetcher.scala: reply success to "+replyTo)
                   replyTo ! fetched
                 case Failure(e) => 
                   println("URLFetcher.scala: FIX ME: URL fetch failed "+e)
               }
        }
    }

    override def postStop = {
        asyncHttpClient.close()
    }
}

object URLFetcher {
    // This field is just used for debug/logging/testing
    val httpInFlight = new AtomicInteger(0)

    // note: an AsyncHttpClient is a heavy object with a thread
    // and connection pool associated with it, it's supposed to
    // be shared among lots of requests, not per-http-request
    private def makeClient(implicit dispatcher: MessageDispatcher) = {
        val executor = Executors.newCachedThreadPool()

        val builder = new AsyncHttpClientConfig.Builder()
        val config = builder.setMaximumConnectionsTotal(1000)
            .setMaximumConnectionsPerHost(15)
            .setExecutorService(executor)
            .setFollowRedirects(true)
            .build
        new AsyncHttpClient(config)
    }

    private def fetchURL(asyncHttpClient: AsyncHttpClient, u: URL): Future[URLFetched] = {
        // timeout the Akka future 50ms after we'd have timed out the request anyhow,
        // gives us 50ms to parse the response
//        val f = new DefaultCompletableFuture[URLFetched](asyncHttpClient.getConfig().getRequestTimeoutInMs() + 50,
//            TimeUnit.MILLISECONDS)

      var f:Promise[URLFetched] = promise[URLFetched]// = new akka.dispatch.DefaultPromise

      val httpHandler = new AsyncHandler[Unit]() {
            httpInFlight.incrementAndGet()

            val builder =
                new Response.ResponseBuilder()

            var finished = false

            // We can have onThrowable called because onCompleted
            // throws, and other complex situations, so to handle everything
            // we use this
            private def finish(body: => Unit): Unit = {
                if (!finished) {
                    try {
                        body
                    } catch {
                        case t: Throwable => {
//                            EventHandler.debug(this, t.getMessage)
                            f failure t
//                            f.completeWithException(t)
                            throw t // rethrow for benefit of AsyncHttpClient
                        }
                    } finally {
                        finished = true
                        httpInFlight.decrementAndGet()
                        assert(f.isCompleted)
                    }
                }
            }

            // this can be called if any of our other methods throws,
            // including onCompleted.
            def onThrowable(t: Throwable) {
                finish { throw t }
            }

            def onBodyPartReceived(bodyPart: HttpResponseBodyPart) = {
                builder.accumulate(bodyPart)

                AsyncHandler.STATE.CONTINUE
            }

            def onStatusReceived(responseStatus: HttpResponseStatus) = {
                builder.accumulate(responseStatus)

                AsyncHandler.STATE.CONTINUE
            }

            def onHeadersReceived(responseHeaders: HttpResponseHeaders) = {
                builder.accumulate(responseHeaders)

                AsyncHandler.STATE.CONTINUE
            }

            def onCompleted() = {
                import scala.collection.JavaConverters._

                finish {
                    val response = builder.build()

                    val headersJavaMap = response.getHeaders()

                    var headers = Map.empty[String, String]
                    for (header <- headersJavaMap.keySet.asScala) {
                        // sometimes getJoinedValue() would be more correct.
                        headers += (header -> headersJavaMap.getFirstValue(header))
                    }

                    val body = response.getResponseBody()
//                    f.completeWithResult(URLFetched(response.getStatusCode(), headers, body))
                    f success URLFetched(u, response.getStatusCode(), headers, body)                    
                }
            }
        }

        asyncHttpClient.prepareGet(u.toExternalForm()).execute(httpHandler)
        f.future
    }
}
