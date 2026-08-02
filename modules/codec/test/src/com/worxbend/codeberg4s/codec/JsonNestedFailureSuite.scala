package com.worxbend.codeberg4s.codec

import munit.FunSuite

/** What happens when a reader runs a parse of its own and that inner parse fails.
  *
  * Every failure `Json.decode` sees arrives wrapped in upickle's `TraceVisitor.TraceException`, whose message is a JSON
  * path rather than an explanation. A reader that parses an embedded document — the shape `SearchEnvelopeDto` documents
  * — makes that wrapping happen twice, so the cause chain is `TraceException -> TraceException -> Abort`. The middle
  * one's message is `$['name']`, and reporting it as the failure's `message` would leave a bug report saying only where
  * the problem was, twice, and never what it was.
  *
  * `Json.reasonOf` skips trace exceptions for exactly that reason, and this suite is what holds it there: without the
  * skip, the assertions below see a JSON path where upickle's explanation belongs.
  */
final class JsonNestedFailureSuite extends FunSuite:

  final case class Leaf(name: String) derives upickle.default.ReadWriter

  final case class Embedded(leaf: Leaf)

  /** Reads an object holding a JSON document as a string, and parses that document with its own traced read. */
  private val embeddedReader: upickle.default.Reader[Embedded] =
    JsonFields.reader: fields =>
      val document = fields.rawText("document").getOrElse("")
      Embedded(upickle.default.read[Leaf](document, trace = true))

  test("a reader that parses an embedded document decodes when that document is well-formed"):
    val body = """{"document": "{\"name\": \"ok\"}"}"""

    assertEquals(Json.decode[Embedded](body)(using embeddedReader), Right(Embedded(Leaf("ok"))))

  test("a failure inside an embedded parse reports upickle's explanation, not the inner trace's JSON path"):
    val body = """{"document": "{\"name\": [1, 2]}"}"""

    Json.decode[Embedded](body)(using embeddedReader) match
      case Left(problem) =>
        assert(
          !problem.message.startsWith("$"),
          s"the inner trace's path leaked into the message: ${problem.message}",
        )
        assert(
          problem.message.contains("expected string got sequence"),
          s"upickle's own explanation was lost: ${problem.message}",
        )
      case Right(value)  => fail(s"expected a failure, decoded $value")

  test("an embedded parse that fails on a malformed document is still a failure and not an exception"):
    val body = """{"document": "{not json"}"""

    assertEquals(Json.decode[Embedded](body)(using embeddedReader).isLeft, true)

  test("an embedded failure is reported at the outer document's path, which is where the reader was standing"):
    val body = """{"document": "{\"name\": [1, 2]}"}"""

    Json.decode[Embedded](body)(using embeddedReader) match
      case Left(problem) => assertEquals(problem.path.render, "$")
      case Right(value)  => fail(s"expected a failure, decoded $value")
