package com.typesafe.webwords.web

import scala.collection.mutable
import scala.xml
import scala.xml.Attribute
import akka.actor._
// import akka.actor.Actor.actorOf
import com.thenewmotion.akka.http._
import com.typesafe.webwords.common._
import java.net.URL
import java.net.URI
import java.net.MalformedURLException
import java.net.URISyntaxException
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import com.thenewmotion.akka.http.Async.Complete
import com.thenewmotion.akka.http._
import com.thenewmotion.akka.http.Endpoints._
import org.eclipse.jetty.server.Request
import akka.pattern.ask
import scala.concurrent.duration._

// this is just here for testing a simple case.
class HelloActor extends Actor {
  def receive = {
        case request:Request =>
            // you could do something nicer ;-) this is just an example
          val res = request.getResponse()
          res.getWriter.write(
            <html>
              <body>
                <h1>Hello World</h1>
                <h3>endpoint function</h3>
              </body>
            </html>.toString())
          res.getWriter.close()
  }
}


class BootStrapServlet(config: WebWordsConfig) extends AkkaHttpServlet with StaticEndpoints {

  var helloActor: Option[ActorRef] = None
  var wordsActor: Option[ActorRef] = None
  var custom404Actor: Option[ActorRef] = None

  override def onSystemInit(system: ActorSystem, endpoints: EndpointsAgent) {
    super.onSystemInit(system, endpoints)
    helloActor = Some(system.actorOf(Props[HelloActor], "hello"))
    wordsActor = Some(system.actorOf(Props(new WordsActor(config)), "words"))
    custom404Actor = Some(system.actorOf(Props[Custom404Actor], "custom404"))
  }

  def providers = {
    case "/" | "/words" => wordsActor.get
    case "/hello" => helloActor.get 
    case _ => custom404Actor.get
  }
}

// we send any paths we don't recognize to this one.
class Custom404Actor extends Actor {
    override def receive = {
        case request:Request =>
            // you could do something nicer ;-) this is just an example
          val res = request.getResponse()
          res.getWriter.write("Error 404: Nothing here!")
          res.getWriter.close()
    }
}

// this actor handles the main page.
class WordsActor(config: WebWordsConfig) extends Actor {
    private val client = context.actorFor("./client")

    case class Finish(request: Request, url: String, index: Option[Index],
        cacheHit: Boolean, startTime: Long)

    private def form(url: String, skipCache: Boolean, badUrl: Boolean = false) = {
        <div>
            <form action="/words" method="get">
                <fieldset>
                    <div>
                        <label for="url">Site</label>
                        <input type="text" id="url" name="url" value={ url } style="min-width: 300px;"></input>
                        {
                            if (badUrl) {
                                <div style="font-color: red;">Invalid or missing URL</div>
                            }
                        }
                    </div>
                    <div>
                        {
                            <input type="checkbox" id="skipCache" name="skipCache"></input> %
                                (if (skipCache) Attribute("checked", xml.Text(""), xml.Null) else xml.Null)
                        }
                        <label for="skipCache">Skip cache</label>
                    </div>
                    <div>
                        <button>Spider &amp; Index</button>
                    </div>
                </fieldset>
            </form>
        </div>
    }

    private def results(url: String, index: Index, cacheHit: Boolean, elapsed: Long) = {
        // world's ugliest word cloud!
        def countToStyle(count: Int) = {
            val maxCount = (index.wordCounts.headOption map { _._2 }).getOrElse(1)
            val font = 6 + ((count.doubleValue / maxCount.doubleValue) * 24).intValue
            Attribute("style", xml.Text("font-size: " + font + "pt;"), xml.Null)
        }

        <div>
            <p>
                <a href={ url }>{ url }</a>
                spidered and indexed.
            </p>
            <p>{ elapsed }ms elapsed.</p>
            <p>{ index.links.size } links scraped.</p>
            {
                if (cacheHit)
                    <p>Results were retrieved from cache.</p>
                else
                    <p>Results newly-spidered (not from cache).</p>
            }
        </div>
        <h3>Word Counts</h3>
        <div style="max-width: 600px; margin-left: 100px; margin-top: 20px; margin-bottom: 20px;">
            {
                val nodes = xml.NodeSeq.newBuilder
                for ((word, count) <- index.wordCounts) {
                    nodes += <span title={ count.toString }>{ word }</span> % countToStyle(count)
                    nodes += xml.Text(" ")
                }
                nodes.result
            }
        </div>
        <div style="font-size: small">(hover to see counts)</div>
        <h3>Links Found</h3>
        <div style="margin-left: 50px;">
            <ol>
                {
                    val nodes = xml.NodeSeq.newBuilder
                    for ((text, url) <- index.links)
                        nodes += <li><a href={ url }>{ text }</a></li>
                    nodes.result
                }
            </ol>
        </div>

    }
    def wordsPage(formNode: xml.NodeSeq, resultsNode: xml.NodeSeq) = {
        <html>
            <head>
                <title>Web Words!</title>
            </head>
            <body style="max-width: 800px;">
                <div>
                    <div>
                        { formNode }
                    </div>
                    {
                        if (resultsNode.nonEmpty)
                            <div>
                                { resultsNode }
                            </div>
                    }
                </div>
            </body>
        </html>
    }

    private def completeWithHtml(request: Request, html: xml.NodeSeq) = {
        request.getResponse.setContentType("text/html")
        request.getResponse.setCharacterEncoding("utf-8")
        request.getResponse.getWriter.write("<!DOCTYPE html>\n" + html)
        request.getResponse.getWriter.close()
    }

    private def handleFinish(finish: Finish) = {
        val elapsed = System.currentTimeMillis - finish.startTime
        finish match {
            case Finish(request, url, Some(index), cacheHit, startTime) =>
                val html = wordsPage(form(url, skipCache = false), results(url, index, cacheHit, elapsed))
                completeWithHtml(request, html)
            case Finish(request, url, None, cacheHit, startTime) =>
                request.getResponse.getWriter.write("Failed to index url in " + elapsed + "ms (try reloading)")
                request.getResponse.getWriter.close()
        }
    }

    private def parseURL(s: String): Option[URL] = {
        val maybe = try {
            val uri = new URI(s) // we want it to be a valid URI also
            val url = new URL(s)
            // apparently a valid URI can have no hostname
            if (uri.getHost() == null)
                throw new URISyntaxException(s, "No host in URI")
            Some(url)
        } catch {
            case e: MalformedURLException => None
            case e: URISyntaxException => None
        }
        maybe.orElse({
            if (s.startsWith("http"))
                None
            else
                parseURL("http://" + s)
        })
    }

    private def handleGet(get: Request) = {
        val skipCacheStr = Option(get.getParameter("skipCache")).getOrElse("false")
        val skipCache = Seq("true", "on", "checked").contains(skipCacheStr.toLowerCase)
        val urlStr = Option(get.getParameter("url"))
        val url = parseURL(urlStr.getOrElse(""))
        
        import scala.concurrent.ExecutionContext.Implicits.global

        if (url.isDefined) {
            val startTime = System.currentTimeMillis
            implicit val timeout = akka.util.Timeout(60 second)
            val futureGotIndex = (client ? GetIndex(url.get.toExternalForm, skipCache)).mapTo[GotIndex]

            futureGotIndex foreach {
                // now we're in another thread, so we just send ourselves
                // a message, don't touch actor state
                case GotIndex(url, indexOption, cacheHit) =>
                    self ! Finish(get, url, indexOption, cacheHit, startTime)
            }

            // we have to worry about timing out also.
            futureGotIndex onFailure { case _:Throwable =>
                // again in another thread - most methods on futures are in another thread!
                self ! Finish(get, url.get.toExternalForm, index = None, cacheHit = false, startTime = startTime)
            }
        } else {
            val html = wordsPage(form(urlStr.getOrElse(""), skipCache, badUrl = urlStr.isDefined),
                resultsNode = xml.NodeSeq.Empty)

            completeWithHtml(get, html)
        }
    }

    override def receive = {
        case request:Request => request.getMethod() match {
          case "GET" =>
            handleGet(request)
          case m =>
            val res = request.getResponse()
            res.getWriter.write("HTTP Method "+m+" Not Supported")
            res.getWriter.close()
        }
    }

    override def preStart = {
        context.actorOf(Props(new ClientActor(config)), "client") 
    }

    override def postStop = {
        context.stop(client)
    }
}
// This actor simply delegates to the real handlers.

// There are extra libraries such as Spray that make this less typing:
//   https://github.com/spray/spray/wiki
// but for this example, showing how you would do it manually.
/*
class WebBootstrap(root:EndpointsAgent, config: WebWordsConfig) extends Actor {
//class WebBootstrap(rootEndpoint: ActorRef, config: WebWordsConfig) extends Actor {// with Endpoint {
    private val handlers = Map(
        "/hello" -> context.actorFor("./hello"),
        "/words" -> context.actorFor("./words"))

    private val custom404 = context.actorFor("./custom404")

    // Caution: this callback does not run in the actor thread,
    // so has to be thread-safe. We keep it simple and only touch
    // immutable values so there's nothing to worry about.
    private val handlerFactory: PartialFunction[String, ActorRef] = {
        case path if handlers.contains(path) =>
            handlers(path)
        case "/" =>
            handlers("/words")
        case path: String =>
            custom404
    }

//    override def receive = handleHttpRequest
    override def receive = {
      case req =>
        // TODO: fixme
        println("Fix WebBootstrap: "+req)
    }

    override def preStart = {
        // start up our handlers
      /*
        handlers.values foreach { _.start }
        custom404.start
       */
      context.actorOf(Props[HelloActor], "hello")
      context.actorOf(Props(new WordsActor(config)), "words")
      context.actorOf(Props[Custom404Actor], "custom404")
        // register ourselves with the akka-http RootEndpoint actor.
        // In Akka 2.0, Endpoint.Attach takes a partial function,
        // in 1.2 it still takes two separate functions.
        // So in 2.0 this can just be Endpoint.Attach(handlerFactory)
      /*
        rootEndpoint ! Endpoint.Attach({
            path =>
                handlerFactory.isDefinedAt(path)
        }, {
            path =>
                handlerFactory(path)
        })
        * 
        */
      root.attach(
          "/", {case m => 
            println("=== "+m+" ===")
        Endpoint(handlers("/words"))})
    }

    override def postStop = {
        handlers.values foreach { v => context.stop(v) }
        context.stop(custom404)
    }
}
*/
