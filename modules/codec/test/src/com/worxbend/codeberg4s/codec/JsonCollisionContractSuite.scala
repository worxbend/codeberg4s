package com.worxbend.codeberg4s.codec

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** Deterministic fallback semantics only: elapsed time is a diagnostic benchmark, never a regression gate. */
final class JsonCollisionContractSuite extends FunSuite:

  private val family: Vector[String] = (0 until 10).foldLeft(Vector(""))((prefixes, _) =>
    prefixes.flatMap(prefix => Vector(s"${prefix}Aa", s"${prefix}BB"))
  )

  test("the collision corpus consists of distinct equal Java String hashes"):
    assertEquals(family.distinct.size, family.size)
    assertEquals(family.map(_.hashCode).distinct.size, 1)

  Vector(8, 9, 64, 257, 512).foreach: width =>
    val keys    = family.take(width)
    val entries = keys.zipWithIndex.map((key, index) => key -> JsonValue.Int64(index.toLong))
    val body    = Json.render(JsonValue.Obj(entries))

    test(s"parsing $width colliding keys preserves exact document order"):
      assertEquals(Json.parse(body), Right(JsonValue.Obj(entries)))
      assertEquals(Json.parse(body.getBytes(StandardCharsets.UTF_8)), Right(JsonValue.Obj(entries)))

    test(s"field indexing $width colliding keys returns every distinct value"):
      val fields = JsonFields(entries)
      keys.zipWithIndex.foreach: (key, index) =>
        assertEquals(fields.number(key), Some(index.toLong))

    test(s"an absent colliding key terminates correctly among $width fields"):
      assertEquals(keys.head.hashCode, family.last.hashCode)
      assertEquals(JsonFields(entries).value(family.last), None)

    Vector(0, width / 2, width - 1).foreach: duplicateIndex =>
      test(s"parsed duplicate $duplicateIndex after $width colliding fields is rejected"):
        val duplicate = entries :+ (keys(duplicateIndex) -> JsonValue.Int64(-1L))
        val repeated  = Json.render(JsonValue.Obj(duplicate))
        assert(Json.parse(repeated).isLeft)
        assert(Json.parse(repeated.getBytes(StandardCharsets.UTF_8)).isLeft)

      test(s"hand-built duplicate $duplicateIndex after $width colliding fields keeps first-field lookup"):
        val repeated = entries :+ (keys(duplicateIndex) -> JsonValue.Int64(-1L))
        assertEquals(JsonFields(repeated).number(keys(duplicateIndex)), Some(duplicateIndex.toLong))
        assertEquals(JsonValue.Obj(repeated).field(keys(duplicateIndex)).flatMap(_.longOpt), Some(duplicateIndex.toLong))
        assertEquals(JsonFields(repeated).entries, repeated)

  test("an escaped spelling of a duplicate after the collision cluster is still the same key"):
    val entries    = family.take(256).zipWithIndex.map((key, index) => key -> JsonValue.Int64(index.toLong))
    val body       = Json.render(JsonValue.Obj(entries))
    val escapedKey = family.head.map(character => f"\\u${character.toInt}%04x").mkString
    assert(Json.parse(s"${body.dropRight(1)},\"$escapedKey\":-1}").isLeft)

  test("a duplicate inside a nested colliding object is not hidden by its enclosing object"):
    val entries = family.take(256).map(key => key -> JsonValue.Bool(true))
    val nested  = JsonValue.Obj(entries :+ (family.head -> JsonValue.Bool(false)))
    assert(Json.parse(Json.render(JsonValue.Obj(Vector("child" -> nested)))).isLeft)

  test("a first null field is not replaced by a later duplicate after the collision cluster"):
    val entries  = family.take(256).map(key => key -> JsonValue.Int64(1L))
    val repeated = Vector(family.last -> JsonValue.Null) ++ entries :+ (family.last -> JsonValue.Str("later"))
    val fields   = JsonFields(repeated)
    assertEquals(fields.rawText(family.last), None)
    assertEquals(JsonValue.Obj(repeated).field(family.last), None)

  test("collision fallback retains field order when rendered from a hand-built duplicate object"):
    val entries  = family.take(256).zipWithIndex.map((key, index) => key -> JsonValue.Int64(index.toLong))
    val repeated = entries :+ (family.head -> JsonValue.Int64(-1L))
    val expected = repeated.map((key, value) => s"\"$key\":${Json.render(value)}").mkString("{", ",", "}")
    assertEquals(Json.render(JsonValue.Obj(repeated)), expected)
