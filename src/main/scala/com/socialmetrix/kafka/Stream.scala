package com.socialmetrix.kafka

import java.util.Properties
import javax.inject.{Inject, Singleton}

import com.fasterxml.jackson.databind.JsonNode
import com.socialmetrix.apis.{JsonTemplateService, Rule, RulesService}
import com.socialmetrix.json.Jackson.objectMapper
import com.socialmetrix.lucene.Matcher
import com.socialmetrix.utils.FutureOps.sync
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import org.apache.kafka.streams.kstream.Predicate
import org.apache.kafka.streams.{KafkaStreams, StreamsBuilder, Topology}

import scala.collection.JavaConverters._

@Singleton
class Stream @Inject()(jsonTemplateEngine: JsonTemplateService, matcher: Matcher,
                       rulesService: RulesService)(implicit config: Config) extends StrictLogging {

  val kafkaConfig = config.getConfig("kafka")
  val streamingConfig = kafkaConfig.toProperties
  val topology = buildTopology(kafkaConfig)
  val stream = new KafkaStreams(topology, streamingConfig)

  // TODO implement state listener to close the dependencies when the stream dies

  def start(): Unit = {
    // on error close the stream
    stream.setUncaughtExceptionHandler { (t, e) =>
      logger.error("Uncaught exception, closing the stream", e)
      stream.close()
      throw e
    }
    stream.start()
  }

  def stop(): Unit = stream.close()

  private def buildTopology(kafkaConfig: Config): Topology = {
    val builder = new StreamsBuilder

    val a: Predicate[AnyRef, (Rule, JsonNode)] = {
      case (a: AnyRef, (c: Rule, d: JsonNode)) => true
    }

    builder
      .stream[AnyRef, JsonNode](kafkaConfig.getString("topic.source"))
      .peek((k, v) => logger.trace("=> " + objectMapper.writeValueAsString(v)))
      // create a new message for each rule with the current message
      .flatMapValues[(Rule, JsonNode)](data =>
      sync(rulesService.getRules)
        .map(rule => (rule, data))
        .asJava
    )
      // only keep rules that matches with the data
      .filter((k, v) => matcher.matches(v._2, v._1.query))
      // render the template against the data
      .mapValues[JsonNode](v => sync(jsonTemplateEngine.render(v._2, v._1.template)).get)
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