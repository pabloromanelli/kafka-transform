package com.socialmetrix

import akka.actor.ActorSystem
import com.google.inject.{AbstractModule, Guice}
import com.socialmetrix.kafka.Stream
import com.socialmetrix.ws.WsModule
import com.typesafe.config.{Config, ConfigFactory}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.ExecutionContext

object Main {

  def main(args: Array[String]): Unit = {
    val injector = Guice.createInjector(MainModule, WsModule)
    val stream = injector.getInstance(classOf[Stream])

    stream.start()

    // on shutdown close the stream
    sys.addShutdownHook{
      stream.stop()
      injector.getInstance(classOf[StandaloneAhcWSClient]).close()
      injector.getInstance(classOf[ActorSystem]).terminate()
    }
  }

  object MainModule extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[Config]).toInstance(ConfigFactory.load())
      bind(classOf[ExecutionContext]).toInstance(scala.concurrent.ExecutionContext.global)
    }
  }

}