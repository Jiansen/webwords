package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import akka.actor.Actor.actorOf
import java.net.URL
import com.typesafe.webwords.common._

class WorkerActorSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {
    var httpServer: TestHttpServer = null

    override def beforeAll = {
        httpServer = new TestHttpServer(Some(this.getClass))
        httpServer.start()
    }

    override def afterAll = {
        httpServer.stop()
        httpServer = null
    }

    behavior of "WorkerActor"

    it should "get an index" in {
        val testdb = Some("mongodb://localhost/webwordsworkertest")
        val config = WebWordsConfig(None, testdb, None)
        val url = httpServer.resolve("/resource/ToSpider.html")
        val worker = actorOf(new WorkerActor(config)).start
        Thread.sleep(500) // help ensure worker's amqp exchange is set up
        val client = actorOf(new ClientActor(config)).start
        val indexFuture = (client ? GetIndex(url.toExternalForm, skipCache = false)) map {
            case GotIndex(url, Some(index), cacheHit) =>
                index
            case whatever =>
                throw new Exception("Got bad result from worker: " + whatever)
        }
        val index = indexFuture.get

        index.wordCounts.size should be(50)
        val nowheres = (index.links filter { link => link._2.endsWith("/nowhere") } map { _._1 }).sorted
        nowheres should be(Seq("a", "d", "e", "f", "g", "h", "j", "k", "m", "o"))
    }
}
