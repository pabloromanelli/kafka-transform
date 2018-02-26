package com.socialmetrix.template

import java.io.File

import com.fasterxml.jackson.databind.ObjectMapper
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.{Matchers, WordSpec}

import scala.util.Success

class EngineTest extends WordSpec with Matchers {

  private val objectMapper = new ObjectMapper()
  private val engine = new Engine()

  val jsonFiles = Table(
    ("fileName", "template", "data", "result"),
    new File("src/test/resources/com/socialmetrix/template/examples")
      .listFiles()
      .map { file =>
        val json = objectMapper.readTree(file)
        (file.getName, json.path("template"), json.path("data"), json.path("result"))
      }: _*
  )

  "JsonTemplate" should {

    "match every example" in {
      forAll(jsonFiles) { (fileName, template, data, result) =>
        val actualResult = engine.transform(template, data)
        actualResult shouldBe Success(result)
      }
    }

    "fail on multiple fields on map / flatmap" in {
      fail
    }

  }

}
