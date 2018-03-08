package com.socialmetrix.lucene

import javax.inject.Singleton

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node._
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.standard.{StandardFilter, StandardTokenizer}
import org.apache.lucene.analysis.{Analyzer, LowerCaseFilter}
import org.apache.lucene.document.{DoublePoint, LongPoint}
import org.apache.lucene.index.memory.MemoryIndex
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.queryparser.classic.QueryParser.Operator.AND
import org.apache.lucene.search.Query

import scala.collection.JavaConverters._
import scala.util.Try

@Singleton
class Matcher(fieldSeparator: String = ".", queryParser: QueryParser = NumericQueryParser) {

  def matches(data: ObjectNode, query: String): Boolean = {
    buildIndex(data)
      .search(queryParser.parse(query)) > 0
  }

  protected def buildIndex(data: ObjectNode): MemoryIndex = {
    // TODO support cached / compile rules to improve performance (using #reset)
    val index = new MemoryIndex()
    addFields(data, List(), index)
    index
  }

  protected def addFields(data: JsonNode, field: List[String], index: MemoryIndex): Unit = data match {
    case j: ObjectNode => data.fields().asScala.foreach(entry =>
      addFields(entry.getValue, entry.getKey :: field, index)
    )
    case j: ArrayNode => data.asScala.foreach(node => addFields(node, field, index))
    // TODO add DATE support (http://www.joda.org/joda-time/apidocs/org/joda/time/format/ISODateTimeFormat.html#dateOptionalTimeParser--)
    case j: TextNode => index.addField(name(field), data.textValue, lowerCaseASCIIFoldingAnalyzer)
    case j: BooleanNode => index.addField(name(field), data.booleanValue().toString, lowerCaseASCIIFoldingAnalyzer)
    case j: NumericNode => if (data.isIntegralNumber)
    // int / long
      index.addField(new LongPoint(name(field), data.longValue()), null)
    else {
      // float / double
      index.addField(new DoublePoint(name(field), data.doubleValue()), null)
    }
    case j: NullNode => // ignore null values
  }

  protected def name(field: List[String]): String =
    field.reduce((a, b) => b + fieldSeparator + a)

}

object NumericQueryParser extends QueryParser("", lowerCaseASCIIFoldingAnalyzer) {
  setAllowLeadingWildcard(true)
  setDefaultOperator(AND)

  override def getRangeQuery(field: String, part1: String, part2: String, startInclusive: Boolean, endInclusive: Boolean): Query = {
    lazy val triedLong1 = Try(part1.toLong).map(p => if (startInclusive) p else Math.addExact(p, 1))
    lazy val triedLong2 = Try(part2.toLong).map(p => if (endInclusive) p else Math.addExact(p, -1))
    lazy val triedDouble1 = Try(part1.toDouble).map(p => if (startInclusive) p else DoublePoint.nextUp(p))
    lazy val triedDouble2 = Try(part2.toDouble).map(p => if (endInclusive) p else DoublePoint.nextDown(p))

    if (part1 == null ^ part2 == null) {
      // if one of them is null (and not both) => wildcard query
      if (triedLong1.orElse(triedLong2).isSuccess) {
        LongPoint.newRangeQuery(
          field,
          triedLong1.getOrElse(Long.MinValue),
          triedLong2.getOrElse(Long.MaxValue))
      } else if (triedDouble1.orElse(triedDouble2).isSuccess) {
        DoublePoint.newRangeQuery(
          field,
          triedDouble1.getOrElse(Double.NegativeInfinity),
          triedDouble2.getOrElse(Double.PositiveInfinity))
      } else {
        super.getRangeQuery(field, part1, part2, startInclusive, endInclusive)
      }
    } else {
      // full range query (both ends have values)
      val longRange = for {
        p1 <- triedLong1
        p2 <- triedLong2
      } yield LongPoint.newRangeQuery(field, p1, p2)

      longRange
        .orElse(for {
          p1 <- triedDouble1
          p2 <- triedDouble2
        } yield DoublePoint.newRangeQuery(field, p1, p2))
        .getOrElse(
          super.getRangeQuery(field, part1, part2, startInclusive, endInclusive)
        )
    }
  }

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Query = {
    if (quoted) {
      super.getFieldQuery(field, queryText, quoted)
    } else {
      Try(queryText.toLong)
        .map(LongPoint.newExactQuery(field, _))
        .orElse(
          Try(queryText.toDouble)
            .map(DoublePoint.newExactQuery(field, _))
        )
        .getOrElse(super.getFieldQuery(field, queryText, quoted))
    }
  }
}

object lowerCaseASCIIFoldingAnalyzer extends Analyzer {
  override def createComponents(fieldName: String) = {
    val src = new StandardTokenizer
    val tok = new LowerCaseFilter(new ASCIIFoldingFilter(new StandardFilter(src)))
    new Analyzer.TokenStreamComponents(src, tok)
  }
}