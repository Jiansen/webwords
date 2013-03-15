package com.typesafe.webwords.common

import akka.actor._
import com.github.sstone.amqp
import com.github.sstone.amqp.Amqp
import com.github.sstone.amqp.RpcClient

import akka.pattern.ask
import scala.concurrent.duration._
/**
 * This actor wraps the work queue on the "client" side (in the web process).
 */
class WorkQueueClientActor(url: Option[String] = None)
    extends AbstractWorkQueueActor(url) {

  implicit val timeout = akka.util.Timeout(5 second)
  import scala.concurrent.ExecutionContext.Implicits.global
//    private[this] var rpcClient: Option[RPC.RpcClient[WorkQueueRequest, WorkQueueReply]] = None
    private[this] var rpcClient: Option[ActorRef] = None
    override def receive = {
        case request: WorkQueueRequest =>
          rpcClient.get ? request onComplete{
            case reply => sender ! reply
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
            super.receive.apply(m)
    }

    override def createRpc(connectionActor: ActorRef) = {
      /*
        val serializer =
            new RPC.RpcClientSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.toBinary, WorkQueueReply.fromBinary)
        rpcClient = Some(RPC.newRpcClient(connectionActor, rpcExchangeName, serializer))
        * 
        */
      rpcClient = Some(context.actorOf(Props(new RpcClient() )))
    }

    override def destroyRpc = {
        rpcClient foreach { c => context.stop(c) }
        rpcClient = None
    }
}
