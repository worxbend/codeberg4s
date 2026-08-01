package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

import munit.FunSuite

/** `Json` is the only place in the library that calls upickle, so this suite is where "no codec exception ever escapes"
  * is proved. Every case below is a body that makes upickle throw.
  */
final class JsonSuite extends FunSuite:

  /** A strictly-derived reader, unlike the lenient hand-written ones the DTOs use. Needed here precisely because it
    * fails: the JSON-path recovery has nothing to report unless something goes wrong deep inside a document.
    */
  final case class Leaf(name: String) derives upickle.default.ReadWriter

  final case class Branch(leaf: Leaf) derives upickle.default.ReadWriter

  /** Field names chosen to be long, so that upickle's `missing keys in dictionary: …` message overruns
    * [[Json.MaxReasonLength]] and the truncation is exercised for real rather than asserted vacuously.
    */
  final case class Wide(
      aFieldNameLongEnoughToPushUpicklesMissingKeyMessageOverTheReasonBoundOne: String,
      aFieldNameLongEnoughToPushUpicklesMissingKeyMessageOverTheReasonBoundTwo: String,
      aFieldNameLongEnoughToPushUpicklesMissingKeyMessageOverTheReasonBoundSix: String,
  ) derives upickle.default.ReadWriter

  test("a well-formed body decodes"):
    assertEquals(Json.decode[Leaf]("""{"name":"ok"}"""), Right(Leaf("ok")))

  test("a truncated body is a failure, not an exception"):
    val result = Json.decode[Leaf]("""{"name":"ok""")

    assert(result.isLeft, s"expected a DecodeFailure, got $result")

  test("a body that is not JSON at all is a failure"):
    assert(Json.decode[Leaf]("404 page not found").isLeft)

  test("an empty body is a failure"):
    assert(Json.decode[Leaf]("").isLeft)

  test("a body of the wrong JSON kind is a failure"):
    assert(Json.decode[Leaf]("[]").isLeft)

  test("a document-level failure reports the root path"):
    Json.decode[Leaf]("nonsense") match
      case Left(failure) => assert(failure.path.isRoot, s"expected the root path, got ${failure.path.render}")
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("a document-level failure carries upickle's own explanation, not an empty string"):
    Json.decode[Leaf]("nonsense") match
      case Left(failure) => assert(failure.message.trim.nonEmpty, "the failure message was blank")
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("a failure inside a nested object reports the field path"):
    Json.decode[Branch]("""{"leaf":{"name":[1,2]}}""") match
      case Left(failure) => assertEquals(failure.path.render, "$.leaf.name")
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("a failure inside a list element reports the element index"):
    Json.decode[Vector[Leaf]]("""[{"name":"first"},{"name":[1,2]}]""") match
      case Left(failure) => assertEquals(failure.path.render, "$[1].name")
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("a missing required key reports it by name"):
    Json.decode[Leaf]("""{}""") match
      case Left(failure) => assert(failure.message.contains("name"), s"unhelpful message: ${failure.message}")
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("the failure message is bounded so a decoder cannot dump a payload into a log line"):
    Json.decode[Wide]("""{}""") match
      case Left(failure) =>
        assert(failure.message.length > Json.MaxReasonLength / 2, "the fixture stopped producing a long message")
        assert(failure.message.length <= Json.MaxReasonLength + 3, s"unbounded: ${failure.message.length}")
        assert(failure.message.endsWith("..."), s"truncation is not marked: ${failure.message}")
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("a body that is the JSON literal null is a failure, not a null reference"):
    Json.decode[Leaf]("null") match
      case Left(failure) =>
        assert(failure.path.isRoot, failure.path.render)
        assert(failure.message.contains("null"), failure.message)
      case Right(value)  => fail(s"a null body must not decode, got $value")

  test("a null body is rejected for a list too"):
    assert(Json.decode[Vector[Leaf]]("null").isLeft)

  test("types that model absence still read a null body as absence"):
    assertEquals(Json.decode[Option[String]]("null"), Right(None))

  test("a list body decodes element by element"):
    assertEquals(
      Json.decode[Vector[Leaf]]("""[{"name":"a"},{"name":"b"}]"""),
      Right(Vector(Leaf("a"), Leaf("b"))),
    )

  test("decoder produces a Decode port that agrees with decode"):
    val port = Json.decoder[Leaf]

    assertEquals(port("""{"name":"ok"}"""), Json.decode[Leaf]("""{"name":"ok"}"""))
    assertEquals(port("nonsense"), Json.decode[Leaf]("nonsense"))

  test("decoder never throws either"):
    val port = Json.decoder[Leaf]

    assertEquals(port("").isLeft, true)

  test("a failure carries the root path when the whole document is the problem"):
    val expected: Either[DecodeFailure, Leaf] = Json.decode[Leaf]("")

    expected match
      case Left(failure) => assertEquals(failure.path, JsonPath.Root)
      case Right(value)  => fail(s"expected a failure, decoded $value")
