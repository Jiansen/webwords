package util.AMQP

trait FromBinary[T] {
  def fromBinary (bytes: Array[Byte]): T
}