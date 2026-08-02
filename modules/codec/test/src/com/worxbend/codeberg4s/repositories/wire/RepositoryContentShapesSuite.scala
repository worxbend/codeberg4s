package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.RepositoryContent

import munit.FunSuite

/** What the contents endpoint says when the body is a shape it does not have.
  *
  * `RepositoryContentDto` is the one reader in this module that decides by JSON kind rather than by a discriminator
  * field, so "neither an object nor an array" is a real outcome and not a defensive branch. Its message is the only
  * thing a bug report will carry — the pipeline adds a bounded body excerpt, but the excerpt of a body that is `false`
  * says nothing — so each shape has to be named as what it actually is. A `describe` that collapsed the shapes into one
  * wording would still pass a test that only checked the failure path, which is why these assert the message verbatim.
  */
final class RepositoryContentShapesSuite extends FunSuite:

  /** The invariant half of the message; only the shape after it varies. */
  private val Preamble: String = "expected a contents object or an array of them, got "

  test("a body that is the JSON literal null is named as null"):
    assertEquals(message("null"), s"${Preamble}null")

  test("a body that is a JSON string is named as a string"):
    assertEquals(message(""""README.md""""), s"${Preamble}a string")

  test("a body that is a JSON number is named as a number"):
    assertEquals(message("42"), s"${Preamble}a number")

  test("a body that is JSON true is named as a boolean"):
    assertEquals(message("true"), s"${Preamble}a boolean")

  test("a body that is JSON false is named as a boolean, not confused with absence"):
    assertEquals(message("false"), s"${Preamble}a boolean")

  test("an array holding something that is not an entry blames the array, not the element"):
    assertEquals(message("[1, 2, 3]"), s"${Preamble}an array holding a value that is not an object")

  test("an array is a directory only when every element is an object, one bad element is enough"):
    val body = """[{"name": "a", "path": "a", "sha": "abcdef12", "type": "file"}, "not an entry"]"""

    assertEquals(message(body), s"${Preamble}an array holding a value that is not an object")

  test("every unusable shape is reported at the root, since the whole body is the problem"):
    List("null", "42", "true", """"README.md"""", "[1, 2, 3]").foreach: body =>
      assertEquals(failure(body).path.render, "$", s"wrong path for $body")

  test("the first unconvertible entry of a directory is the one reported, and a later one does not replace it"):
    val body =
      """[
        |  {"name": "a", "sha": "abcdef12", "type": "file"},
        |  {"name": "b", "path": "b", "sha": "abcdef12", "type": "dir"},
        |  {"name": "c", "path": "c", "type": "file"}
        |]""".stripMargin

    assertEquals(failure(body).path.render, "$[0].path")
    assertEquals(failure(body).message, "required field 'path' is missing")

  test("a directory whose entries all convert is not a failure at all"):
    val body = """[{"name": "a", "path": "a", "sha": "abcdef12", "type": "file"}]"""

    assertEquals(convert(body).isRight, true, s"a well-formed listing failed: ${convert(body)}")

  private def convert(body: String): Either[DecodeFailure, RepositoryContent] =
    Json.decode[RepositoryContentDto](body).flatMap(_.toDomain)

  private def failure(body: String): DecodeFailure =
    convert(body) match
      case Left(problem) => problem
      case Right(value)  => fail(s"expected a failure, decoded $value")

  private def message(body: String): String =
    failure(body).message
