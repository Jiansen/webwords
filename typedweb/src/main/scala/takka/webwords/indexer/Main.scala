package takka.webwords.indexer

import akka.actor._
import takka.webwords.common._
import java.util.concurrent.CountDownLatch
import takka.webwords.common.AMQPCheck

/**
 * This is the main() method for the indexer (worker) process.
 * The indexer gets requests to "index" a URL from a work queue,
 * storing results in a persistent cache (kept in MongoDB).
 */
object Main extends App {
    val system = ActorSystem("WebWordIndexer")
    val config = WebWordsConfig()

    if (!AMQPCheck.check(config))
        throw new Exception("AMQP not working (start the AMQP service?)")

    val worker = system.actorOf(Props(new WorkerActor(config)), "worker")

    // kind of a hack maybe.
    val waitForever = new CountDownLatch(1)
    waitForever.await
}
