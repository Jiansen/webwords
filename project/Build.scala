import sbt._
import Keys._
import com.typesafe.startscript.StartScriptPlugin
import com.typesafe.sbteclipse.plugin.EclipsePlugin._

object BuildSettings {
    import Dependencies._
    import Resolvers._

    val buildOrganization = "com.typesafe"
    val buildVersion = "1.0"
    val buildScalaVersion = "2.10.0"

    val globalSettings = Seq(
        organization := buildOrganization,
        version := buildVersion,
        scalaVersion := buildScalaVersion,
        scalacOptions += "-deprecation",
        fork in test := true,
        libraryDependencies ++= Seq(slf4jSimpleTest, scalatest, jettyServerTest),
        resolvers := Seq(jbossRepo, akkaRepo, sonatypeRepo, newMotion))

    val projectSettings = Defaults.defaultSettings ++ globalSettings
}

object Resolvers {
    val sonatypeRepo = "Sonatype Release" at "https://oss.sonatype.org/content/repositories/releases/"
    val jbossRepo = "JBoss" at "http://repository.jboss.org/nexus/content/groups/public/"
    val akkaRepo = "Akka" at "http://repo.akka.io/repository/"
//    val typesafe = "Typesafe" at "http://repo.typesafe.com/typesafe/repo/"
    val newMotion = "NewMotion" at "http://nexus.thenewmotion.com/content/repositories/releases-public"
}

// classpathTypes += "orbit"

object Dependencies {
  object V {
    val Akka      = "2.1.1"
  }

    val scalatest = "org.scalatest"       %% "scalatest"         % "1.9.1" % "test"
    val slf4jSimple = "com.typesafe.akka"   %% "akka-slf4j"  % V.Akka
    val slf4jSimpleTest = slf4jSimple % "test"

    private val jettyVersion = "9.0.0.M0"
//    val jettyVersion = "9.0.0.v20130308"
    val jettyServer = "org.eclipse.jetty" % "jetty-server" % jettyVersion
    val jettyServlet = "org.eclipse.jetty" % "jetty-servlet" % jettyVersion
    val jettyServerTest = jettyServer % "test"
    val jettyOrbit = "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" artifacts (    Artifact("javax.servlet", "jar", "jar")  )
    val akka     = "com.typesafe.akka"   %% "akka-actor"  	% V.Akka
    val akkaHttp = "com.thenewmotion.akka" %% "akka-http" % "1.0.0"
    val akkaAmqp = "com.github.sstone" %% "akka-amqp-proxies" % "1.1"
    val asyncHttp = "com.ning" % "async-http-client" % "1.7.12"
    val jsoup = "org.jsoup" % "jsoup" % "1.6.3"
    val casbahCore = "org.mongodb" %% "casbah-core" % "2.5.0"
}


object WebWordsBuild extends Build {
    import BuildSettings._
    import Dependencies._
    import Resolvers._

    override lazy val settings = super.settings ++ globalSettings
/*
    lazy val root = Project("webwords",
                            file("."),
                            settings = projectSettings ++
                            Seq(
                                StartScriptPlugin.stage in Compile := Unit
                            )) aggregate(untypedweb)
*/
    lazy val untypedweb = Project("untypedweb",
                           file("untypedweb"),
                           settings = projectSettings ++
                           StartScriptPlugin.startScriptForClassesSettings ++
                           Seq(libraryDependencies ++= Seq(akka, akkaAmqp, asyncHttp, casbahCore, jettyServer, jettyServlet, jettyOrbit, slf4jSimple, jsoup, akkaHttp))) 

/*
    lazy val web = Project("webwords-web",
                           file("web"),
                            )) aggregate(webwords_common, webwords_web, webwords_indexer)

    lazy val webwords_web = Project("webwords-web",
                           file("webwords-web"),
                           settings = projectSettings ++
                           StartScriptPlugin.startScriptForClassesSettings ++
//                           Seq(libraryDependencies ++= Seq(akkaHttp, jettyServer, jettyServlet, slf4jSimple))) dependsOn(common % "compile->compile;test->test")
                           Seq(libraryDependencies ++= Seq(jettyServer, jettyServlet, slf4jSimple))) dependsOn(webwords_common % "compile->compile;test->test")

    lazy val webwords_indexer = Project("webwords-indexer",
                              file("webwords-indexer"),
                              settings = projectSettings ++
                              StartScriptPlugin.startScriptForClassesSettings ++
                              Seq(libraryDependencies ++= Seq(jsoup))) dependsOn(webwords_common % "compile->compile;test->test")

    lazy val webwords_common = Project("webwords-common",
                           file("webwords-common"),
                           settings = projectSettings ++
                           Seq(libraryDependencies ++= Seq(akka, akkaAmqp, asyncHttp, casbahCore)))
*/
}

