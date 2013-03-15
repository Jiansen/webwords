package util.AMQP

trait ToBinary[T] {
  def toBinary (t: T): Array[Byte]
}