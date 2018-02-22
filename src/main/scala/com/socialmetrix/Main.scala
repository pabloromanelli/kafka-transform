package com.socialmetrix

import akka.actor.ActorSystem
import com.google.inject.{AbstractModule, Guice, Injector}
import com.socialmetrix.kafka.Stream
import com.socialmetrix.ws.WsModule
import com.typesafe.config.{Config, ConfigFactory}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.ExecutionContext

object Main {

  def main(args: Array[String]): Unit = {
    val injector = Guice.createInjector(MainModule, WsModule)
    try {
      val stream = injector.getInstance(classOf[Stream])

      stream.start()
      // on shutdown close the stream
      sys.addShutdownHook(stream.stop())
      // on shutdown close ws client
      sys.addShutdownHook(closeWs(injector))
    } catch {
      // on error close ws client
      case e: Exception => closeWs(injector); throw e
    }
  }

  private def closeWs(injector: Injector) = {
    injector.getInstance(classOf[StandaloneAhcWSClient]).close()
    injector.getInstance(classOf[ActorSystem]).terminate()
  }

  object MainModule extends AbstractModule {
    override def configure(): Unit = {
      val config = ConfigFactory.systemEnvironment().withFallback(ConfigFactory.load())
      bind(classOf[Config]).toInstance(config)
      bind(classOf[ExecutionContext]).toInstance(scala.concurrent.ExecutionContext.global)
    }
  }

}