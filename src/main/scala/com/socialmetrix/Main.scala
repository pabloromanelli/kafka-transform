package com.socialmetrix

import javax.inject.Singleton

import akka.actor.ActorSystem
import com.google.inject.{AbstractModule, Guice, Injector, Provides}
import com.socialmetrix.kafka.Stream
import com.socialmetrix.lucene.Matcher
import com.socialmetrix.template.Engine
import com.socialmetrix.ws.WsModule
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.ExecutionContext

object Main extends StrictLogging {

  def main(args: Array[String]): Unit = {
    val injector = Guice.createInjector(MainModule, WsModule)
    try {
      val stream = injector.getInstance(classOf[Stream])
      stream.setCloseListener {
        closeWs(injector)
      }

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

    @Provides
    @Singleton
    def luceneMatcher(config: Config): Matcher =
      new Matcher(
        config.getString("LuceneMatcher.fieldSeparator")
      )

    @Provides
    @Singleton
    def templateEngine(mainConfig: Config): Engine = {
      val config = mainConfig.getConfig("JsonTempalte")

      new Engine(
        config.getString("delimiters.start") -> config.getString("delimiters.end"),
        config.getString("fieldSeparator"),
        config.getString("metaPrefix"),
        config.getString("thisIdentifier"),
        config.getString("commandPrefix")
      )
    }
  }

}