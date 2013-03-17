package com.typesafe.webwords.common

import akka.actor._
import com.github.sstone.amqp
import com.github.sstone.amqp.Amqp
import com.github.sstone.amqp.RpcClient
import com.github.sstone.amqp.Amqp._

import akka.pattern.ask
import scala.concurrent.duration._
import com.github.sstone.amqp.RabbitMQConnection
import scala.util._

/**
 * This actor wraps the work queue on the "client" side (in the web process).
 */
class WorkQueueClientActor(url: Option[String] = None)
    extends AbstractWorkQueueActor(url) {

  implicit val timeout = akka.util.Timeout(60 second)
  import scala.concurrent.ExecutionContext.Implicits.global
//    private[this] var rpcClient: Option[RPC.RpcClient[WorkQueueRequest, WorkQueueReply]] = None
    private[this] var rpcClient: Option[ActorRef] = None
    override def receive = {
        case request: WorkQueueRequest =>
          println("WorkQueueClient: receive "+request)
          rpcClient.get ? Publish("amq.direct", "my_key", request.toBinary) onComplete {
            case Success(Ok(_, reply)) => reply match {
              case Some(r:WorkQueueReply) =>
                println("WorkQueueClient: reply "+reply )
                sender ! reply
              case Some(r) =>
                println("FIX ME: WorkQueueClientActor.scala receive success Ok reply "+r)
              case None =>
                println("FIX ME: WorkQueueClientActor.scala receive success None reply ")
            }
            case Success(m) =>
              println("FIX ME: WorkQueueClientActor.scala receive success message "+m)
            case Failure(_) =>
              println("FIX ME: WorkQueueClientActor.scala should not receive failure")
          }
          /*
            rpcClient.get.callAsync(request, timeout = 60 * 1000)({
                case Some(reply) =>
                    sender ! reply
                case None =>
                    savedChannel.sendException(new Exception("no reply to: " + request))
            })
*/
        case m =>
          println("WorkQueueClient: pass to super "+m)
            super.receive.apply(m)
    }
    override def createRpc(connection:RabbitMQConnection) = {
      rpcClient = Some(connection.createRpcClient())
      Amqp.waitForConnection(context.system, rpcClient.get).await()
    }
/*
    override def createRpc(connectionActor: ActorRef) = {
      /*
        val serializer =
            new RPC.RpcClientSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.toBinary, WorkQueueReply.fromBinary)
        rpcClient = Some(RPC.newRpcClient(connectionActor, rpcExchangeName, serializer))
        * 
        */
      rpcClient = Some(context.actorOf(Props(new RpcClient() )))
    }
*/
    override def destroyRpc = {
        rpcClient foreach { c => context.stop(c) }
        rpcClient = None
    }
}
