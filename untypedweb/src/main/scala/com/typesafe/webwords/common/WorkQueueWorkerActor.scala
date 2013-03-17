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
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.RpcServer._

/**
 * This actor wraps the work queue on the worker process side.
 */
abstract class WorkQueueWorkerActor(url: Option[String] = None)
    extends AbstractWorkQueueActor(url) {

//    private[this] var rpcServer: Option[RPC.RpcServerHandle] = None
    private[this] var rpcServer: Option[RpcServer] = None
    
    protected def handleRequest(request: WorkQueueRequest): Promise[WorkQueueReply]
/*
    override def receive = {
        case request: WorkQueueRequest =>
            //self.channel.replyWith(handleRequest(request))
println("WorkQueueWorkerActor: received is "+request)          
          sender ! handleRequest(request)

        case m =>
            super.receive.apply(m)
    }
  */  
    override def createRpc(connection:RabbitMQConnection) = {
       val queueParams = QueueParameters("webwords_rpc.request.in", passive = false, durable = false, exclusive = false, autodelete = true)

       //TODO: more server?
       // create n equivalent servers  
       val rpcServers = for (i <- 1 to 1) yield {
          // create a "processor"
          // in real life you would use a serialization framework (json, protobuf, ....), define command messages, etc...
          // check the Akka AMQP proxies project for examples
          val processor = new IProcessor {
            def process(delivery: Delivery) = {              
              val request = WorkQueueRequest.fromBinary.fromBinary(delivery.body)
              import scala.concurrent.ExecutionContext.Implicits.global
// println("=== request is " + request)           
              val response = handleRequest(request)
              response.future map {
                case r => 
// println("=== response is " + r)
                  ProcessResult(Some(r.toBinary))
              }
            }
            def onFailure(delivery: Delivery, e: Throwable) = {
println("=== Failed Delivery "+delivery)
              ProcessResult(None) // we don't return anything 
            }
          }
          // TODO: my_key?
          connection.createRpcServer(StandardExchanges.amqDirect, queueParams, "my_key", processor, Some(ChannelParameters(qos = 1)))
        }
        Amqp.waitForConnection(context.system, rpcServers: _*).await()
    }

/*    
    override def createRpc(connectionActor: ActorRef) = {
      implicit val timeout = akka.util.Timeout(60 seconds)
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
    }
*/      
    override def destroyRpc = {
        rpcServer foreach { _.stop }
        rpcServer = None
    }
}
