name := "kafka-transform"

version := "0.1.0"

scalaVersion := "2.12.4"

scalacOptions := Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.google.inject" % "guice" % "4.1.0",
  "org.apache.kafka" % "kafka-clients" % "1.0.0",
  "org.apache.kafka" % "kafka-streams" % "1.0.0",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.4",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.9.4",
  "com.typesafe" % "config" % "1.3.2",
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.1.3",
  "com.github.ben-manes.caffeine" % "jcache" % "2.6.1",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

enablePlugins(JavaAppPackaging)

dockerRepository := Some("socialmetrix")

dockerUpdateLatest := true

import com.typesafe.sbt.packager.docker._

dockerCommands := Seq(
  Cmd("FROM", "openjdk:8u151-jre-alpine3.7"),
  Cmd("WORKDIR", "/opt/docker"),
  Cmd("USER", "daemon"),
  ExecCmd("ENTRYPOINT", "java", "-Xms64m", "-Xmx256m", "-cp", "lib/*", "com.socialmetrix.Main"),
  Cmd("ADD", "--chown=daemon:daemon opt /opt")
)