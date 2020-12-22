val AppVersion = "0.1.0"
val AkkaVersion = "2.6.8"
val AkkaHttpVersion = "10.2.1"

val Env = sys.env.get("env").getOrElse("test")
val ImageVersion = if (Env == "test") s"test-$AppVersion" else AppVersion

name := "files-service"
lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      version := AppVersion,
      organization := "cn.freeriver",
      scalaVersion := "2.13.3"
    )),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "javax.ws.rs" % "javax.ws.rs-api" % "2.0.1",
      "com.github.swagger-akka-http" %% "swagger-akka-http" % "2.2.0",
      "co.pragmati" %% "swagger-ui-akka-http" % "1.4.0"
    ),
    packageName in Docker := "colinzeng/files-service",
    version in Docker := ImageVersion,
    dockerBaseImage := "colinzeng/openjdk-with-tools:8u265",
    dockerExposedPorts ++= Seq(80),
    daemonUser in Docker := "root",
    aggregate in Docker := false
  ).enablePlugins(JavaServerAppPackaging)