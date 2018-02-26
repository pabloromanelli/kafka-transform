package com.socialmetrix.template

import java.util.regex.Pattern.quote

import com.fasterxml.jackson.databind.node._
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

  def apply() = new Engine(
    "{{" -> "}}",
    ".",
    "$",
    "#",
    failOnMissingNode,
    failOnInvalidFieldName,
    failOnInvalidType)

}

/**
  * String: replaces each "{{node}}" with the corresponding node from the data object (supports nested nodes sepparated by ".")
  *  - if the node is the only text on the string, it will be replaced by the original data node (retaining type)
  *  - in any other case it will be translated as text
  * String: #each node => data node is an array
  * Any other Value Node: the template node is kept without any change
  * Container node (Array / Object): every element or field will be mapped one by one with the same logic as before
  */
class Engine(delimiters: (String, String),
             fieldSeparator: String,
             metaPrefix: String,
             commandPrefix: String,
             onMissingNode: (String, JsonNode) => Try[JsonNode],
             onInvalidFieldName: (JsonNode, String) => Try[String],
             onInvalidType: (String, JsonNode) => Try[JsonNode]) {

  private val objectMapper = new ObjectMapper()

  // delimiters
  private val start = quote(delimiters._1)
  private val end = quote(delimiters._2)

  // variables
  private val variables = s"$start([^$end]+)$end"
  private val variablesRegex = variables.r
  private val singleVariableRegex = s"^$variables$$".r

  // commands
  private val escapedCommandPrefix = quote(commandPrefix)
  private val mapCommand = "map"
  private val flatmapCommand = "flatmap"
  private val mapFlatmapRegex =
    (raw"^$start" +
      raw"$escapedCommandPrefix($mapCommand|$flatmapCommand)\s+" + // #map|#flatmap
      raw"([^$end]+)" + // variable
      raw"$end$$").r

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

  private object MapFlatmapCommand {
    def unapply(field: (String, JsonNode)): Option[(String, String)] = field match {
      case (mapFlatmapRegex(commandName, variable), node) => Some(commandName, variable)
      case _ => None
    }
  }

  def transform(template: JsonNode, data: JsonNode): Try[JsonNode] =
    transformRecursive(template, data)

  private def transformRecursive(template: JsonNode, data: JsonNode): Try[JsonNode] = template match {
    // expand / interpolate strings
    case j: TextNode => fillout(j.textValue(), data)
    // recurse over arrays
    case j: ArrayNode => Try {
      val result = objectMapper.createArrayNode()
      j.forEach(child => result.add(transformRecursive(child, data).get))
      result
    }
    // "map" / "flatmap" command
    case JsonObject(field@MapFlatmapCommand(command, variable)) => {
      lookup(variable, data).flatMap {
        case array: ArrayNode => Try {
          val result = objectMapper.createArrayNode()
          val eachTemplate = field._2

          array.asScala.zipWithIndex.foreach { case (child, i) =>
            val newChild = transformRecursive(
              eachTemplate,
              child)
            if (command == mapCommand) // map
              result.add(newChild.get)
            else // flatmap
              result.addAll(newChild.flatMap {
                case j: ArrayNode => Success(j)
                case j => onInvalidType("array", j).asInstanceOf[Try[ArrayNode]]
              }.get)
          }

          result
        }
        case json => onInvalidType("array", json)
      }
    }
    // Regular object
    case j: ObjectNode => Try {
      // TODO validate it doesn't have any other command on the field names (to prevent unexpected behaviour)
      val result = objectMapper.createObjectNode()

      j.fields().forEachRemaining { entry =>
        val fieldName = fillout(entry.getKey, data)
          .flatMap {
            case j: ValueNode => Success(j.asText())
            case j => onInvalidFieldName(j, entry.getKey)
          }
          .get
        val value = transformRecursive(entry.getValue, data).get

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
    * TODO include $root, $parent and $index on the lookup
    * TODO allow $parent.$parent.$parent to be available
    * TODO support indexed access (numeric index on arrays)
    * TODO support ignore missing fields {{?field}}
    * TODO provide a way to escape text
    * TODO compile the template to calc lookups more efficiently
    */
  @tailrec
  private def lookup(variable: String, context: Context): Try[JsonNode] = {
    if (variable == thisMeta) {
      Success(data)
    } else if (variable.startsWith(s"$thisMeta$fieldSeparator")) {
      lookup(variable.stripPrefix(s"$thisMeta$fieldSeparator"), data)
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

  // meta
  private val thisMeta = metaPrefix + "this"
  private val rootMeta = metaPrefix + "root"
  private val parentMeta = metaPrefix + "parent"
  private val indexMeta = metaPrefix + "index"

  private object Int {
    def unapply(field: String): Option[Int] = Try(field.toInt).toOption
  }

  object Context {
    def apply(json: JsonNode) = {
      val a:Context = new Context(json, a, Failure(???), Failure(???))
    }
  }

  case class Context(json: JsonNode,
                     root: Context,
                     parent: Try[Context] = Failure(??? /*no parent defined for ???*/),
                     index: Try[Int] = Failure(??? /*no index defined for ???*/)) {

    private def recLookup(variable: List[String]): Try[Context] = variable match {
      case List() => Success(this)
      case `thisMeta` :: rest => recLookup(rest)
      case `parentMeta` :: rest => parent.flatMap(_.recLookup(rest))
      case `rootMeta` :: rest => root.recLookup(rest)
      case `indexMeta` :: List() => index.map(i => Context(new IntNode(i), root, parent, index))
      case `indexMeta` :: rest => Failure(??? /*can't have fields after index*/)
      case Int(i) :: rest if json.isArray => {
        Option(json.get(i))
          .map(child => Context(child, root, Success(this), Success(i)))
          .map(_.recLookup(rest))
          .getOrElse(Failure(??? /*invalid array index*/))
      }
      case fieldName :: rest => {
        Option(json.get(fieldName))
          // TODO get or else here (to allow "missing node" recovery to continue the lookup)
          .map(child => Context(child, root, Success(this), index))
          .map(_.recLookup(rest))
          .getOrElse(
            onMissingNode(fieldName, json)
              .map(j => Context(j, root, parent, index))
          )
      }
    }

    def lookup(variable: String): Try[Context] = {
      variable.split(fieldSeparator).toList match {
        case List() => Failure(???)
        case list => recLookup(list)
      }
    }
  }


}