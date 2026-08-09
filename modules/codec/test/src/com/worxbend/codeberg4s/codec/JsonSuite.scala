package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath

import munit.FunSuite

/** `Json` is the only place in the library that calls jsoniter, so this suite is where "no codec exception ever
  * escapes" is proved. Every case below is a body that makes the parser throw.
  *
  * Decoding is two steps — parse into [[JsonValue]], then assemble the DTO — and only the first can fail structurally.
  * That is why the failures here carry [[JsonPath.Root]]: the problem is the document, not a field. A field the domain
  * needs is reported by the DTO's own `toDomain`, which knows which field it wanted; those paths are asserted in the
  * DTO suites, not here.
  */
final class JsonSuite extends FunSuite:

  private final case class Leaf(name: Option[String])

  private given JsonDecoder[Leaf] = JsonFields.reader(fields => Leaf(fields.text("name")))

  private val leaf: String = """{"name":"a"}"""

  test("a well-formed body decodes"):
    assertEquals(Json.decode[Leaf](leaf), Right(Leaf(Some("a"))))

  test("a truncated body is a failure, not an exception"):
    assert(Json.decode[Leaf]("""{"name":""").isLeft)

  test("a body that is not JSON at all is a failure"):
    assert(Json.decode[Leaf]("not json").isLeft)

  test("an empty body is a failure"):
    assert(Json.decode[Leaf]("").isLeft)

  test("a body of the wrong JSON kind is a failure"):
    assert(Json.decode[Leaf]("""[1,2,3]""").isLeft)

  test("a wrong-kind failure says what it found rather than only what it wanted"):
    Json.decode[Leaf]("""[1,2,3]""") match
      case Left(failure) => assert(failure.message.contains("an array"), failure.message)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("a document-level failure reports the root path"):
    Json.decode[Leaf]("not json") match
      case Left(failure) => assertEquals(failure.path, JsonPath.Root)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("a document-level failure carries the parser's own explanation, not an empty string"):
    Json.decode[Leaf]("not json") match
      case Left(failure) => assert(failure.message.trim.nonEmpty)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("the failure message is bounded so a decoder cannot dump a payload into a log line"):
    val huge = s"""{"name":"${"x" * 100_000}"""

    Json.decode[Leaf](huge) match
      case Left(failure) => assert(failure.message.length <= Json.MaxReasonLength + 3, failure.message.length)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("the failure message carries no hex dump of the body"):
    // jsoniter appends one by default. A response body is payload, and this
    // library's failures are logged.
    Json.decode[Leaf]("""{"name":"secret-value-in-body""") match
      case Left(failure) => assert(!failure.message.contains("secret"), failure.message)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("a body that is the JSON literal null is a failure, not a null reference"):
    assert(Json.decode[Leaf]("null").isLeft)

  test("a null body is rejected for a list too"):
    assert(Json.decode[Vector[Leaf]]("null").isLeft)

  test("a list body decodes element by element"):
    assertEquals(
      Json.decode[Vector[Leaf]]("""[{"name":"a"},{"name":"b"}]"""),
      Right(Vector(Leaf(Some("a")), Leaf(Some("b")))),
    )

  test("an element of the wrong kind fails the page and names its position"):
    Json.decode[Vector[Leaf]]("""[{"name":"a"},7]""") match
      case Left(failure) => assertEquals(failure.path, JsonPath.Root.index(1))
      case Right(value)  => fail(s"expected a failure, got $value")

  test("an empty list decodes"):
    assertEquals(Json.decode[Vector[Leaf]]("[]"), Right(Vector.empty))

  test("decoder produces a Decode port that agrees with decode"):
    assertEquals(Json.decoder[Leaf].apply(leaf), Json.decode[Leaf](leaf))

  test("decoder never throws either"):
    assert(Json.decoder[Leaf].apply("not json").isLeft)

  test("a document nested past the depth bound is a failure, not a stack overflow"):
    // Remote input must not be able to exhaust the caller's stack.
    val deep = ("[" * (JsonValue.MaxDepth + 50)) + ("]" * (JsonValue.MaxDepth + 50))

    assert(Json.parse(deep).isLeft)

  test("a document within the depth bound still parses"):
    val shallow = ("[" * 10) + ("]" * 10)

    assert(Json.parse(shallow).isRight)

  test("render round-trips a parsed document"):
    val body = """{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}"""

    assertEquals(Json.parse(body).map(Json.render), Right(body))

  test("render keeps object fields in the order they were written"):
    val document = JsonValue.Obj("z" -> JsonValue.Str("1"), "a" -> JsonValue.Str("2"))

    assertEquals(Json.render(document), """{"z":"1","a":"2"}""")

  test("a document that names a field twice is rejected rather than quietly resolved"):
    // JsonValue.Obj argues the decision. In short: the three ways of reading an
    // object disagreed about {"id":1,"id":2} — first, last, and both — and only
    // one of the available answers loses no data, which is to refuse it.
    assert(Json.parse("""{"id":1,"id":2}""").isLeft)

  test("the repeated-field failure names the field that was repeated"):
    Json.parse("""{"id":1,"id":2}""") match
      case Left(failure) => assert(failure.message.contains("""duplicated field "id""""), failure.message)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("a repeated field fails at the root, like every other structural failure"):
    Json.parse("""{"id":1,"id":2}""") match
      case Left(failure) => assertEquals(failure.path, JsonPath.Root)
      case Right(value)  => fail(s"expected a failure, got $value")

  test("a repeated field is rejected whichever door the body is decoded through"):
    // This is the disagreement that made the decision necessary: JsonValue.field
    // answered 1, and a DTO assembled from the same object answered 2.
    assert(Json.decode[Leaf]("""{"name":"a","name":"b"}""").isLeft)
    assert(Json.decode[Map[String, JsonValue]]("""{"id":1,"id":2}""").isLeft)

  test("a repeated field is rejected wherever in the document it sits"):
    assert(Json.parse("""{"owner":{"id":1,"id":2}}""").isLeft)
    assert(Json.parse("""[{"id":1},{"id":2,"id":3}]""").isLeft)

  test("the same field name in two sibling objects is not a repeat"):
    // The rule is about one object naming a field twice. Every element of a page
    // carrying an "id" is what a page looks like.
    assertEquals(Json.parse("""[{"id":1},{"id":2}]""").map(Json.render), Right("""[{"id":1},{"id":2}]"""))

  test("a repeated field is caught in a wide object, where the check hashes instead of comparing"):
    // Objects this wide are checked by a different branch than the narrow ones
    // above — a repository response has 64 keys, so both branches carry real
    // traffic and both need a test. 200 keeps this clear of the width the two
    // branches split at without the test having to know that width.
    val distinct = (1 to 200).map(index => s""""k$index":$index""").mkString("{", ",", "}")
    val repeated = distinct.replace(""""k137":137""", """"k42":137""")

    assert(Json.parse(distinct).isRight)
    assert(Json.parse(repeated).isLeft)

  test("rendering stays faithful, so a document built with a repeated field is one parse refuses"):
    // Json.render writes the fields it is given, and Obj says not to hand it a
    // repeated key. This pins the consequence rather than hiding it: render does
    // not quietly drop a field, and the parser does not quietly accept one, so
    // the two never disagree about a document — they only ever both refuse.
    val document = JsonValue.Obj("id" -> JsonValue.Num(1), "id" -> JsonValue.Num(2))

    assertEquals(Json.render(document), """{"id":1,"id":2}""")
    assert(Json.parse(Json.render(document)).isLeft)

  test("a large integer survives the round trip, which a Double would not"):
    // 2^53 + 1 is the first integer a Double cannot represent.
    val body = """{"id":9007199254740993}"""

    assertEquals(Json.parse(body).map(Json.render), Right(body))
