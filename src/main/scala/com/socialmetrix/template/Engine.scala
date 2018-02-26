package com.socialmetrix.template

import java.util.regex.Pattern.quote

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode, TextNode, ValueNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object Engine {

  case class MissingVariable(variable: String, data: JsonNode)
    extends RuntimeException(s"Missing variable '$variable' on '${data.toString}'.")

  def failOnMissingNode(variable: String, data: JsonNode): Try[JsonNode] =
    Failure(MissingVariable(variable, data))

  case class InvalidFieldName(invalidReplacement: JsonNode, originalField: String)
    extends RuntimeException(s"Field '$originalField' can't be interpolated because the value is not a value node: '${originalField.toString}'.")

  def failOnInvalidFieldName(invalidReplacement: JsonNode, originalField: String): Try[String] =
    Failure(InvalidFieldName(invalidReplacement, originalField))

  case class InvalidType(expectedType: String, data: JsonNode)
    extends RuntimeException(s"Expected '$expectedType' type but found '$data'.")

  def failOnInvalidType(expectedType: String, data: JsonNode): Try[JsonNode] =
    Failure(InvalidType(expectedType, data))

}

/**
  * String: replaces each "{{node}}" with the corresponding node from the data object (supports nested nodes sepparated by ".")
  *  - if the node is the only text on the string, it will be replaced by the original data node (retaining type)
  *  - in any other case it will be translated as text
  * String: #each node => data node is an array
  * Any other Value Node: the template node is kept without any change
  * Container node (Array / Object): every element or field will be mapped one by one with the same logic as before
  */
class Engine(delimiters: (String, String) = "{{" -> "}}",
             fieldSeparator: String = ".",
             thisIdentifier: String = "this",
             commandPrefix: String = "#",
             onMissingNode: (String, JsonNode) => Try[JsonNode] = Engine.failOnMissingNode,
             onInvalidFieldName: (JsonNode, String) => Try[String] = Engine.failOnInvalidFieldName,
             onInvalidType: (String, JsonNode) => Try[JsonNode] = Engine.failOnInvalidType) {

  private val objectMapper = new ObjectMapper()
  private val start = quote(delimiters._1)
  private val end = quote(delimiters._2)
  private val variables = s"$start([^$end]+)$end"
  private val variablesRegex = variables.r
  private val singleVariableRegex = s"^$variables$$".r
  private val escapedCommandPrefix = quote(commandPrefix)
  private val commandRegex = raw"^$start$escapedCommandPrefix(\S+)\s+([^$end]+)$end$$".r

  private object JsonObject {
    def unapplySeq(json: JsonNode): Option[Seq[(String, JsonNode)]] = json match {
      case j: ObjectNode => Some(
        j.fields().asScala
          .map(entry => entry.getKey -> entry.getValue)
          .toSeq
      )
      case _ => None
    }
  }

  private object Command {
    def unapply(field: (String, JsonNode)): Option[(String, String)] = field match {
      case (commandRegex(commandName, variable), node) => Some(commandName, variable)
      case _ => None
    }
  }

  def transform(template: JsonNode, data: JsonNode): Try[JsonNode] = template match {
    // expand / interpolate strings
    case j: TextNode => fillout(j.textValue(), data)
    // recurse over arrays
    case j: ArrayNode => Try {
      val result = objectMapper.createArrayNode()
      j.forEach(child => result.add(transform(child, data).get))
      result
    }
    // "each" command
    case JsonObject(field@Command("each", variable)) => {
      // TODO include $root, $parent and $index on the lookup
      lookup(variable, data).flatMap {
        case array: ArrayNode => Try {
          val result = objectMapper.createArrayNode()
          val eachTemplate = field._2

          array.forEach(child =>
            result.add(transform(eachTemplate, child).get)
          )

          result
        }
        case json => onInvalidType("array", json)
      }
    }
    // Regular object
    case j: ObjectNode => Try {
      // TODO validate it doesn't have any each command on it (to prevent unexpected behaviour)
      val result = objectMapper.createObjectNode()

      j.fields().forEachRemaining { entry =>
        val fieldName = fillout(entry.getKey, data)
          .flatMap {
            case j: ValueNode => Success(j.asText())
            case j => onInvalidFieldName(j, entry.getKey)
          }
          .get
        val value = transform(entry.getValue, data).get

        result.set(fieldName, value)
      }

      result
    }
    // keep it as is on any other case
    case _ => Success(template)
  }

  /**
    * If the variable is the only value of the string, it replaces the whole string with the actual data node.
    * In any other case, uses the serialized version of the data node.
    */
  private def fillout(text: String, data: JsonNode): Try[JsonNode] = text match {
    // variable replacement
    case singleVariableRegex(variable) =>
      lookup(variable, data)
    // string interpolation
    case _ => Try {
      new TextNode(
        variablesRegex.replaceAllIn(text, m =>
          lookup(m.group(1), data).get match {
            case j: TextNode => j.textValue()
            case j => objectMapper.writeValueAsString(j)
          }
        )
      )
    }
  }

  /**
    * Find data node by field name.
    * It could be a nested field name.
    */
  @tailrec
  private def lookup(variable: String, data: JsonNode): Try[JsonNode] = {
    if (variable == thisIdentifier) {
      Success(data)
    } else if (variable.startsWith(s"$thisIdentifier$fieldSeparator")) {
      lookup(variable.stripPrefix(s"$thisIdentifier$fieldSeparator"), data)
    } else {
      val path = "/" + variable.replace(fieldSeparator, "/")
      val result = data.at(path)
      if (result.isMissingNode) {
        // what to do on missing node
        onMissingNode(variable, data)
      } else {
        Success(result)
      }
    }
  }

}