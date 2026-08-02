package com.worxbend.codeberg4s.codec

import munit.FunSuite

/** The optionality hazard, tested directly.
  *
  * `docs/HAZARDS.md` §1 states the requirement in one line: "`null` and *absent* must decode identically". Every DTO in
  * this module inherits that from [[JsonFields]], so it is proved once here and asserted per-DTO on real payloads.
  */
final class JsonFieldsSuite extends FunSuite:

  /** A minimal DTO-shaped type, so the reader can be exercised without dragging a real model into this suite. */
  final case class Probe(a: Option[String])

  private given JsonDecoder[Probe] =
    JsonFields.reader(fields => Probe(fields.text("a")))

  private def fieldsOf(body: String): JsonFields =
    Json.parse(body) match
      case Right(JsonValue.Obj(fields)) => JsonFields(fields.toMap)
      case other                        => fail(s"the fixture body is not a JSON object: $other")

  private val absent: JsonFields = fieldsOf("""{}""")

  private val explicitNull: JsonFields =
    fieldsOf("""{"a":null,"n":null,"b":null,"o":null,"list":null}""")

  private val populated: JsonFields =
    fieldsOf("""{"a":"text","n":7,"b":true,"o":{"inner":"x"},"list":["one","two"]}""")

  test("an absent key and an explicit null are the same string"):
    assertEquals(absent.text("a"), explicitNull.text("a"))
    assertEquals(absent.text("a"), None)

  test("an absent key and an explicit null are the same number"):
    assertEquals(absent.number("n"), explicitNull.number("n"))
    assertEquals(absent.number("n"), None)

  test("an absent key and an explicit null are the same boolean"):
    assertEquals(absent.boolean("b"), explicitNull.boolean("b"))
    assertEquals(absent.boolean("b"), None)

  test("an absent key and an explicit null are the same nested object"):
    assertEquals(absent.nested("o"), explicitNull.nested("o"))
    assertEquals(absent.nested("o"), None)

  test("an absent array and a null array are both empty, never a crash"):
    assertEquals(absent.texts("list"), explicitNull.texts("list"))
    assertEquals(absent.texts("list"), Vector.empty[String])
    assertEquals(explicitNull.values("list"), Vector.empty[JsonValue])

  test("present values are read"):
    assertEquals(populated.text("a"), Some("text"))
    assertEquals(populated.number("n"), Some(7L))
    assertEquals(populated.boolean("b"), Some(true))
    assertEquals(populated.nested("o").flatMap(_.text("inner")), Some("x"))
    assertEquals(populated.texts("list"), Vector("one", "two"))

  test("a value of the wrong JSON kind reads as absent rather than failing the document"):
    val mistyped = fieldsOf("""{"a":123,"n":"seven","b":"yes","o":[],"list":{}}""")

    assertEquals(mistyped.text("a"), None)
    assertEquals(mistyped.number("n"), None)
    assertEquals(mistyped.boolean("b"), None)
    assertEquals(mistyped.nested("o"), None)
    assertEquals(mistyped.texts("list"), Vector.empty[String])

  test("Forgejo's empty string for unset text is folded into absence"):
    val blank = fieldsOf("""{"login_name":"","language":"   "}""")

    assertEquals(blank.text("login_name"), None)
    assertEquals(blank.text("language"), None)

  test("rawText keeps the empty string for a caller that needs the wire value"):
    val blank = fieldsOf("""{"login_name":""}""")

    assertEquals(blank.rawText("login_name"), Some(""))
    assertEquals(blank.rawText("absent"), None)

  test("array elements of the wrong kind are dropped, not fatal"):
    val mixed = fieldsOf("""{"topics":["git",null,7,"forge",{}]}""")

    assertEquals(mixed.texts("topics"), Vector("git", "forge"))

  test("an array of objects becomes a vector of views"):
    val nested = fieldsOf("""{"items":[{"k":"a"},"skipped",{"k":"b"}]}""")

    assertEquals(nested.nestedAll("items").flatMap(_.text("k")), Vector("a", "b"))

  test("the empty view answers every accessor without a key"):
    assertEquals(JsonFields.Empty.text("anything"), None)
    assertEquals(JsonFields.Empty.number("anything"), None)
    assertEquals(JsonFields.Empty.texts("anything"), Vector.empty[String])

  test("reader delegates the is-this-an-object question to the JSON parser"):
    assertEquals(Json.decode[Probe]("""{"a":"x"}"""), Right(Probe(Some("x"))))
    assertEquals(Json.decode[Probe]("""{}"""), Right(Probe(None)))
    assert(Json.decode[Probe]("""["a"]""").isLeft, "an array must not decode as an object")
    assert(Json.decode[Probe]("""7""").isLeft, "a number must not decode as an object")
