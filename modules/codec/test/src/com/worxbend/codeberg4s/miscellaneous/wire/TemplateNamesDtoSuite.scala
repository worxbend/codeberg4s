package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.TemplateName

import munit.FunSuite

/** `golden/misc/gitignore-templates.json` — the one capture behind the template catalogues.
  *
  * It is a bare array of 297 strings, which is also the shape `GET /label/templates` answers, so this suite covers both
  * listings.
  */
final class TemplateNamesDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden gitignore listing decodes to 297 usable template names"):
    converted(golden("misc/gitignore-templates.json")) match
      case Right(names)  =>
        assertEquals(names.size, 297)
        assertEquals(names.headOption.map(_.value), Some("AL"))
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("not one captured name carries a slash or a space, which is what makes the wide validator defensive"):
    converted(golden("misc/gitignore-templates.json")) match
      case Right(names)  =>
        assertEquals(names.count(name => name.value.contains('/')), 0)
        assertEquals(names.count(name => name.value.contains(' ')), 0)
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  test("an empty catalogue is an empty vector, not a failure"):
    assertEquals(converted("[]").map(_.size), Right(0))

  test("a name that could not address the by-name endpoint fails at its own index"):
    converted("""["AL","..","Ada"]""") match
      case Left(failure) => assertEquals(failure.path.render, "$[1]")
      case Right(names)  => fail(s"expected a failure, converted $names")

  test("a blank name fails as well, and at its own index"):
    converted("""["AL","   "]""") match
      case Left(failure) => assertEquals(failure.path.render, "$[1]")
      case Right(names)  => fail(s"expected a failure, converted $names")

  test("a name is trimmed on the way in, so surrounding whitespace never reaches a request path"):
    assertEquals(converted("""["  Ada  "]""").map(_.map(_.value)), Right(Vector("Ada")))

  test("a body that is not an array is a DecodeFailure, not an exception"):
    assert(Json.decode[Vector[String]]("""{"templates":[]}""").isLeft)

  test("a body that is the JSON literal null is rejected rather than becoming an empty catalogue"):
    assert(Json.decode[Vector[String]]("null").isLeft)

  private def converted(body: String): Either[DecodeFailure, Vector[TemplateName]] =
    Json.decode[Vector[String]](body).flatMap(names => TemplateNamesDto.toDomainAll(JsonPath.Root, names))
