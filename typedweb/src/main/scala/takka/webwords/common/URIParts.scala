package takka.webwords.common

case class URIParts(scheme: String, user: Option[String], password: Option[String],
        host: Option[String], port: Option[Int], path: Option[String])