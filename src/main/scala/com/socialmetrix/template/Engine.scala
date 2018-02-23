package com.socialmetrix.template

import java.util.regex.Pattern.quote

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode, TextNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object Engine {

  case class MissingVariable(variable: String, data: JsonNode)
    extends RuntimeException(s"Missing variable '$variable' on '${data.toString}'.")

  def failOnMissingNode(variable: String, data: JsonNode): Try[JsonNode] =
    Failure(MissingVariable(variable, data))

}

class Engine(delimiters: (String, String) = "{{" -> "}}",
             fieldSeparator: String = ".",
             thisIdentifier: String = "this",
             onMissingNode: (String, JsonNode) => Try[JsonNode] = Engine.failOnMissingNode) {

  // Restrictions
  // - Operation ({{#<operation> <node>}})
  //   - Must be the only value on a field name
  //   - The object must have a single field

  // Functionality
  // `this` is an special data node

  // String: replaces each "{{<node>}}" with the corresponding node from the data object (supports nested nodes sepparated by ".")
  //  - if the node is the only text on the string, it will be replaced by the original data node (retaining type)
  //  - in any other case it will be translated as text
  // String: #each <node> => data node is an array
  // Any other Value Node: the template node is kept without any change
  // Container node (Array / Object): every element or field will be mapped one by one with the same logic as before

  def transform(template: JsonNode, data: JsonNode): Try[JsonNode] = template match {
    case j: TextNode => fillout(j.textValue(), data)
    case j: ArrayNode => Try {
      val result = objectMapper.createArrayNode()
      j.forEach(child => result.add(transform(child, data).get))
      result
    }
    case j: ObjectNode => Try {
      val result = objectMapper.createObjectNode()
      j.fields().forEachRemaining(entry =>
        result.set(entry.getKey, transform(entry.getValue, data).get)
      )
      result
    }
    // keep it as is on any other case
    case _ => Success(template)
  }

  private val objectMapper = new ObjectMapper()
  private val start = quote(delimiters._1)
  private val end = quote(delimiters._2)
  private val variables = s"$start([^$end]+)$end"
  private val variablesRegex = variables.r
  private val singleVariableRegex = s"^$variables$$".r

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