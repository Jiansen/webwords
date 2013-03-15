package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.net.URL
import com.typesafe.webwords.common._
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.Await


class WorkerActorSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {
  val system = ActorSystem("SpiderActorSpec")
  implicit val timeout = akka.util.Timeout(10 second) 
  import scala.concurrent.ExecutionContext.Implicits.global  
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
        val worker = system.actorOf(Props(new WorkerActor(config)))
        Thread.sleep(500) // help ensure worker's amqp exchange is set up
        val client = system.actorOf(Props(new ClientActor(config)))
        val indexFuture = (client ? GetIndex(url.toExternalForm, skipCache = false)) map {
            case GotIndex(url, Some(index), cacheHit) =>
                index
            case whatever =>
                throw new Exception("Got bad result from worker: " + whatever)
        }
        val index = Await.result(indexFuture, 10 second)

        index.wordCounts.size should be(50)
        val nowheres = (index.links filter { link => link._2.endsWith("/nowhere") } map { _._1 }).sorted
        nowheres should be(Seq("a", "d", "e", "f", "g", "h", "j", "k", "m", "o"))
    }
}
