package com.socialmetrix

import javax.inject.Singleton

import com.google.inject.{AbstractModule, Guice, Provides}
import com.socialmetrix.apis.RulesModule
import com.socialmetrix.kafka.Stream
import com.socialmetrix.lucene.Matcher
import com.socialmetrix.template.Engine
import com.socialmetrix.ws.WsModule
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.ExecutionContext

object Main extends StrictLogging {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.systemEnvironment().withFallback(ConfigFactory.load())
    val injector = Guice.createInjector(new MainModule(config), new RulesModule(config), WsModule)
    val stream = injector.getInstance(classOf[Stream])

    stream.start()

    // on shutdown close the stream
    sys.addShutdownHook(stream.stop())
  }

  class MainModule(config: Config) extends AbstractModule {
    override def configure(): Unit = {
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
      val config = mainConfig.getConfig("JsonTemplate")

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