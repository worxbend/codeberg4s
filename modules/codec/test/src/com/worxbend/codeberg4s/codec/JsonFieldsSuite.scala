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
      case Right(JsonValue.Obj(fields)) => JsonFields(fields)
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

  test("no field can be shadowed, because a document that repeats one never becomes a view"):
    // This view scans the parser's field vector and takes the first match, so a
    // repeated name would read as the first of the two. That answer is
    // unreachable: the parser refuses the document first, so the view is only
    // ever built from names that are already distinct. See JsonValue.Obj for why
    // refusing is the rule.
    assert(Json.decode[Probe]("""{"a":"first","a":"second"}""").isLeft)
    assertEquals(Json.decode[Probe]("""{"a":"first","b":"second"}"""), Right(Probe(Some("first"))))

  test("a wide object reads the same as a narrow one, on either side of the index threshold"):
    // Above a threshold the view stops comparing names one by one and probes a
    // hash index instead. Both strategies have to answer identically, so the
    // same object is read at eight fields (compared) and at forty (indexed).
    def objectOf(width: Int): JsonFields =
      fieldsOf((0 until width).map(at => s""""f$at":$at""").mkString("{", ",", "}"))

    val narrow = objectOf(8)
    val wide   = objectOf(40)

    assertEquals(narrow.number("f0"), Some(0L))
    assertEquals(narrow.number("f7"), Some(7L))
    assertEquals(narrow.number("f8"), None)

    (0 until 40).foreach(at => assertEquals(wide.number(s"f$at"), Some(at.toLong), s"field f$at of a 40-field object"))
    assertEquals(wide.number("f40"), None)
    assertEquals(wide.number("absent"), None)

  test("two field names with the same hash are still told apart in a wide object"):
    // "Aa" and "BB" have the same String hash (2112), so in an indexed object
    // they want the same slot. A lookup that trusted the hash would answer one
    // with the other's value; comparing the names in full is what stops it.
    val colliding =
      fieldsOf(s"""{"Aa":"first","BB":"second",${(0 until 8).map(at => s""""f$at":$at""").mkString(",")}}""")

    assertEquals("Aa".hashCode, "BB".hashCode, "the fixture is pointless unless these two really do collide")
    assertEquals(colliding.text("Aa"), Some("first"))
    assertEquals(colliding.text("BB"), Some("second"))
    assertEquals(colliding.text("Ab"), None)

  test("a view built by hand from a repeated name answers with the first of them, at either width"):
    // Json.parse never produces this, but the view's constructor is public and
    // RepositoryContentDto builds one straight from an already-parsed object.
    // First-wins is the answer JsonValue.field gives, so it is the answer here.
    def repeated(padding: Int): JsonFields =
      JsonFields(
        Vector("a" -> JsonValue.Str("first"), "a" -> JsonValue.Str("second")) ++
          (0 until padding).map(at => s"f$at" -> JsonValue.Num(at))
      )

    assertEquals(repeated(0).text("a"), Some("first"))
    assertEquals(repeated(20).text("a"), Some("first"))

  test("toMap hands the fields over for a payload whose keys are data rather than a schema"):
    assertEquals(populated.toMap("a"), JsonValue.Str("text"))
    assertEquals(populated.toMap.keySet, Set("a", "n", "b", "o", "list"))
    assertEquals(JsonFields.Empty.toMap, Map.empty[String, JsonValue])

  test("reader delegates the is-this-an-object question to the JSON parser"):
    assertEquals(Json.decode[Probe]("""{"a":"x"}"""), Right(Probe(Some("x"))))
    assertEquals(Json.decode[Probe]("""{}"""), Right(Probe(None)))
    assert(Json.decode[Probe]("""["a"]""").isLeft, "an array must not decode as an object")
    assert(Json.decode[Probe]("""7""").isLeft, "a number must not decode as an object")
