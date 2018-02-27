package com.socialmetrix.lucene

import com.fasterxml.jackson.databind.node.ObjectNode
import com.socialmetrix.json.Jackson.objectMapper
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpec}

class MatcherTest extends WordSpec with Matchers with TableDrivenPropertyChecks {

  val json = objectMapper.readTree(
    """
      |{
      |  "a": -123,
      |  "b": {
      |    "c": "abc -123",
      |    "d": [
      |      {
      |        "e": "x x x",
      |        "f": "y y y",
      |        "g": -10
      |      },
      |      {
      |        "e": "mmm mmm",
      |        "f": "jjj jjj",
      |        "g": 1000
      |      }
      |    ]
      |  },
      |  "x": "2018.01.01",
      |  "y": -123.456789,
      |  "z": 1.0
      |}
    """.stripMargin).asInstanceOf[ObjectNode]
  val matcher = new Matcher()

  val examples =
    Table(
      ("query", "matches"),
      ("*:*", true),

      // exact integer values
      ( """a:\-123""", true),
      ( """a:"-123"""", false),
      ("""b.d.g:\-10""", true),
      ("b.d.g:1000", true),
      ("b.d.g:9", false),

      // ranges of integer values
      ("a:[-123 TO -123]", true),
      ("a:{-123 TO -120]", false),
      ("a:[-125 TO -123}", false),
      ("a:[-125 TO -120]", true),
      // TODO can't mix floating point numbers with integers
      ("a:[-125.0 TO -120.0]", false),
      ("b.d.g:[-11 TO -9]", true),
      ("b.d.g:[-9 TO -11]", false),
      // TODO inside ranges, quoting is not take into account
      ("""a:["-125" TO "-120"]""", true),

      // ranges of double values
      ("y:[-123.5 TO -123.3]", true),
      // TODO can't mix floating point with integers
      ("y:[-124 TO -125]", false),
      ("y:[-123.3 TO -123.5]", false),
      // TODO exact match over floating point numbers is not reliable
      ("z:1.0", false),
      ("""y:\-123.456789""", false),

      // ranges of integer values with wildcards
      ("a:[* TO -120]", true),
      ("a:[-125 TO *]", true),
      ("a:[* TO -123]", true),
      ("a:[-123 TO *]", true),
      ("a:{-123 TO *]", false),
      // TODO should match anything!
      ("a:*", false),
      // TODO should match anything!
      ("a:[* TO *]", false),

      // ranges of double values with wildcards
      ("y:[-123.5 TO *]", true),
      ("y:[* TO -123.3]", true),
      // TODO should match anything!
      ("y:*", false),
      // TODO should match anything!
      ("y:[* TO *]", false),

      // match strings
      ("b.c:abc", true),
      ("""b.c:"abc"""", true),
      ("""b.c:\-123""", false),
      ("""b.c:"-123"""", true),
      ("b.d.f:jjj", true),
      // TODO support search on every field
      ("abc", false),
      // TODO support search on every child field
      ("b.\\*:abc", false),

      // regex
      ("b.c:/a[ebh]c/", true),

      // string ranges
      ("b.d.e:[w TO z]", true),
      ("""b.d.e:["w" TO "z"]""", true),
      ("b.d.e:[a TO m]", false),
      ("""b.d.e:["a" TO "m"]""", false),

      // strings ranges with wildcards
      ("b.d.e:[* TO *]", true),
      ("b.d.e:[* TO z]", true),
      ("b.d.e:[w TO *]", true),
      ("""b.d.e:[* TO "z"]""", true),
      ("""b.d.e:["w" TO *]""", true),
      ("b.d.e:[* TO m]", false),
      ("""b.d.e:[* TO "m"]""", false),

      // string with wildcards
      ("b.d.e:*", true),
      ("b.c:a*", true),
      ("b.c:a*bc", true),
      ("b.c:a*c", true),
      ("b.c:*bc", true),
      ("b.c:a?c", true),
      ("b.c:abc*", true),
      ("b.c:abc?", false),
      ("""b.c:\-12*""", false),
      ("""b.c:*23""", true),
      ("b.d.f:j*", true),
      // TODO can we support * for field names?
      ("*:abc", false)
    )

  "Lucene Matcher" should {

    "match the examples" in {
      forAll(examples) { (query, matches) =>
        matcher.matches(json, query) shouldBe matches
      }
    }

  }

}