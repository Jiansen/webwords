package util.AMQP

import com.rabbitmq.client.Address
import akka.actor.ActorRef

case class ConnectionParameters (addresses: Array[Address],
    username: String, password: String, virtualHost: String,
    initReconnectDelay: Long, connectionCallback: Option[ActorRef])
    extends Product with Serializable{
}

object ConnectionParameters{
  def apply():ConnectionParameters= {
    val defaultAddress = new Address("localhost", 5672)
    ConnectionParameters(Array(defaultAddress), "guest", "guest", "/")
  }
  
  def apply(addresses: Array[Address], username: String, password: String, virtualHost: String):ConnectionParameters = {
    ConnectionParameters(addresses, username, password, virtualHost, 1000L, None)
  }
}