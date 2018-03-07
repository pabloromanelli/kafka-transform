package com.socialmetrix.kafka

import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.socialmetrix.apis.{Rule, RulesService}
import com.socialmetrix.json.Jackson.objectMapper
import com.socialmetrix.lucene.Matcher
import com.socialmetrix.template.Engine
import com.socialmetrix.utils.FutureOps.sync
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import org.apache.kafka.streams.KafkaStreams.State
import org.apache.kafka.streams.kstream.Predicate
import org.apache.kafka.streams.{KafkaStreams, StreamsBuilder, Topology}

import scala.collection.JavaConverters._

@Singleton
class Stream @Inject()(matcher: Matcher, rulesService: RulesService, templateEngine: Engine)
                      (implicit config: Config) extends StrictLogging {

  val kafkaConfig = config.getConfig("kafka")
  val streamingConfig = kafkaConfig.toProperties
  val topology = buildTopology(kafkaConfig)
  val stream = new KafkaStreams(topology, streamingConfig)
  stream.setUncaughtExceptionHandler { (t, e) =>
    logger.error("Uncaught exception on kafka streams", e)
  }

  def setCloseListener(f: => Unit) = {
    stream.setStateListener((newState, oldState) => {
      newState match {
        case State.ERROR => stop()
        case State.NOT_RUNNING => f
        case _ => // do nothing
      }
    })
  }

  def start(): Unit = {
    stream.start()
  }

  def stop(): Unit = {
    // timeout to avoid deadlock
    stream.close(5, TimeUnit.SECONDS)
  }

  private def buildTopology(kafkaConfig: Config): Topology = {
    val builder = new StreamsBuilder

    builder
      .stream[AnyRef, JsonNode](kafkaConfig.getString("topic.source"))
      .peek((k, v) => logger.trace("=> " + objectMapper.writeValueAsString(v)))
      // only keep json objects
      .filter((k, v) => v.isObject)
      // create a new message for each rule
      .flatMapValues[(Rule, JsonNode)](data =>
      sync(rulesService.getRules)
        .map(rule => (rule, data))
        .asJava
    )
      // only keep rules that matches with the data
      .filter((k, v) => matcher.matches(v._2.asInstanceOf[ObjectNode], v._1.query))
      // render the template against the data
      .mapValues[JsonNode](v => templateEngine.transform(v._1.template, v._2).get)
      .peek((k, v) => logger.trace("<= " + objectMapper.writeValueAsString(v)))
      .to(kafkaConfig.getString("topic.sink"))

    builder.build()
  }

  implicit class ConfigAdapter(config: Config) {

    def toProperties: Properties = {
      val properties = new Properties()
      config.entrySet()
        .forEach(e =>
          properties.setProperty(e.getKey(), config.getString(e.getKey()))
        )
      properties
    }

  }

}