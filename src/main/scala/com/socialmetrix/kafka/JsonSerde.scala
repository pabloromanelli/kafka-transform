package com.socialmetrix.kafka

import java.util

import com.fasterxml.jackson.databind.JsonNode
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}
import org.apache.kafka.connect.json.{JsonDeserializer, JsonSerializer}

class JsonSerde extends Serde[JsonNode] {
  override val serializer: Serializer[JsonNode] = new JsonSerializer
  override val deserializer: Deserializer[JsonNode] = new JsonDeserializer

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {
    serializer.configure(configs, isKey)
    deserializer.configure(configs, isKey)
  }

  override def close(): Unit = {
    serializer.close()
    deserializer.close()
  }
}