package com.typesafe.webwords.common

import akka.actor._
import scala.concurrent.Future
//import akka.amqp
//import akka.amqp.AMQP
//import akka.amqp.rpc.RPC
import com.github.sstone.amqp._
import com.github.sstone.amqp.Amqp.{QueueParameters, ChannelParameters, ExchangeParameters}
import com.github.sstone.amqp.proxy.AmqpProxy

import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import com.rabbitmq.client.{ConnectionFactory}

/**
 * This actor wraps the work queue on the worker process side.
 */
abstract class WorkQueueWorkerActor(url: Option[String] = None)
    extends AbstractWorkQueueActor(url) {

//    private[this] var rpcServer: Option[RPC.RpcServerHandle] = None
    private[this] var rpcServer: Option[RpcServer] = None
    
    protected def handleRequest(request: WorkQueueRequest): Promise[WorkQueueReply]

    override def receive = {
        case request: WorkQueueRequest =>
            //self.channel.replyWith(handleRequest(request))
          sender ! handleRequest(request)

        case m =>
            super.receive.apply(m)
    }

    override def createRpc(connectionActor: ActorRef) = {
      implicit val timeout = akka.util.Timeout(2 seconds)
//        val serializer =
//            new RPC.RpcServerSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.fromBinary, WorkQueueReply.toBinary)
        def requestHandler(request: WorkQueueRequest): WorkQueueReply = {
            // having to block here is not ideal
            // https://www.assembla.com/spaces/akka/tickets/1217
//            (self ? request).as[WorkQueueReply].get
            Await.result((self ? request).mapTo[WorkQueueReply], 2 second)
        }
        // the need for poolSize>1 is an artifact of having to block in requestHandler above 
//        rpcServer = Some(RPC.newRpcServer(connectionActor, rpcExchangeName, serializer, requestHandler, poolSize = 8))
        val connFactory = new ConnectionFactory()
        connFactory.setHost("localhost") // TODO: check
        val conn = context.actorOf(  Props(new ConnectionOwner(connFactory)),   name = "conn")      
        val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
        val queue = QueueParameters(name = "RPCserver", passive = false, autodelete = true)
        val channelParams = Some(ChannelParameters(qos = 1))
        rpcServer =Some( new com.github.sstone.amqp.RpcServer(
                       queue, 
                       exchange, 
                       "RPCserver", 
                       new AmqpProxy.ProxyServer(connectionActor), 
                       channelParams
                       ))
                   
//      waitForConnection(system, server).await()
    }

    override def destroyRpc = {
        rpcServer foreach { _.stop }
        rpcServer = None
    }
}
