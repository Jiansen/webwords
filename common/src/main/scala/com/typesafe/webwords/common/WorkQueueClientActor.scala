package com.typesafe.webwords.common

import akka.actor._
import akka.amqp
import akka.amqp.AMQP
import akka.amqp.rpc.RPC

/**
 * This actor wraps the work queue on the "client" side (in the web process).
 */
class WorkQueueClientActor(url: Option[String] = None)
    extends AbstractWorkQueueActor(url) {

    private[this] var rpcClient: Option[RPC.RpcClient[WorkQueueRequest, WorkQueueReply]] = None

    override def receive = {
        case request: WorkQueueRequest =>
            rpcClient.get.callAsync(request, timeout = 60 * 1000)({
                case Some(reply) =>
                    sender ! reply
                    // TODO:
//                case None =>
//                    savedChannel.sendException(new Exception("no reply to: " + request))
            })

        case m =>
            super.receive.apply(m)
    }

    override def createRpc(connectionActor: ActorRef) = {
        val serializer =
            new RPC.RpcClientSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.toBinary, WorkQueueReply.fromBinary)
        rpcClient = Some(RPC.newRpcClient(connectionActor, rpcExchangeName, serializer))
    }

    override def destroyRpc = {
        rpcClient foreach { _.stop }
        rpcClient = None
    }
}
