package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

import munit.FunSuite

/** Public codec factories and primitive readers are consumer seams, independent of the checked built-in DTO readers. */
final class JsonDecoderPublicContractSuite extends FunSuite:
  private val wrongKinds =
    Vector[JsonValue](JsonValue.Null, JsonValue.Num(7), JsonValue.Arr(Vector.empty), JsonValue.Obj(Vector.empty))

  test("public string reader never coerces unrelated JSON kinds"):
    assertEquals(JsonDecoder[String].decode(JsonValue.Str("value")), Right("value"))
    (wrongKinds :+ JsonValue.Bool(true)).foreach: value =>
      assertEquals(
        JsonDecoder[String].decode(value),
        Left(DecodeFailure(JsonPath.Root, s"expected a string but found ${value.kind}"))
      )

  test("public boolean reader accepts both values and rejects non-booleans"):
    List(true, false).foreach(value => assertEquals(JsonDecoder[Boolean].decode(JsonValue.Bool(value)), Right(value)))
    (wrongKinds :+ JsonValue.Str("true")).foreach: value =>
      assertEquals(
        JsonDecoder[Boolean].decode(value),
        Left(DecodeFailure(JsonPath.Root, s"expected a boolean but found ${value.kind}"))
      )

  test("public long reader keeps int64 bounds and the documented fractional truncation"):
    List(Long.MinValue, 0L, Long.MaxValue).foreach(value =>
      assertEquals(JsonDecoder[Long].decode(JsonValue.Num(value)), Right(value))
    )
    assertEquals(JsonDecoder[Long].decode(JsonValue.Num(BigDecimal("-12.75"))), Right(-12L))
    assertEquals(
      JsonDecoder[Long].decode(JsonValue.Num(BigDecimal("9223372036854775808"))),
      Left(DecodeFailure(JsonPath.Root, "expected a number that fits an int64, but it does not"))
    )
    assertEquals(
      JsonDecoder[Long].decode(JsonValue.Str("7")),
      Left(DecodeFailure(JsonPath.Root, "expected a number but found a string"))
    )

  test("identity exposes the parsed value unchanged and map readers preserve explicit null values"):
    (wrongKinds :+ JsonValue.Str("raw")).foreach(value => assertEquals(JsonDecoder[JsonValue].decode(value), Right(value)))
    val value = JsonValue.Obj(Vector("name" -> JsonValue.Str("field"), "nullable" -> JsonValue.Null))
    assertEquals(
      JsonDecoder[Map[String, JsonValue]].decode(value),
      Right(Map("name" -> JsonValue.Str("field"), "nullable" -> JsonValue.Null))
    )
    assertEquals(
      JsonDecoder[Map[String, JsonValue]].decode(JsonValue.Null),
      Left(DecodeFailure(JsonPath.Root, "expected an object but found null"))
    )

  test("fallible object factory preserves a caller's build failure and rejects a non-object before building"):
    val failure = DecodeFailure(JsonPath.Root.field("name"), "name is absent")
    val reader  = JsonDecoder.objectOfEither(fields => fields.text("name").toRight(failure))
    assertEquals(reader.decode(JsonValue.Obj(Vector("name" -> JsonValue.Str("ready")))), Right("ready"))
    assertEquals(reader.decode(JsonValue.Obj(Vector.empty)), Left(failure))
    assertEquals(
      reader.decode(JsonValue.Arr(Vector.empty)),
      Left(DecodeFailure(JsonPath.Root, "expected an object but found an array"))
    )

  test("mapped public readers retain success and the original failure on direct decode"):
    val reader = JsonDecoder[String].map(_.length)
    assertEquals(reader.decode(JsonValue.Str("abc")), Right(3))
    assertEquals(
      reader.decode(JsonValue.Bool(false)),
      Left(DecodeFailure(JsonPath.Root, "expected a string but found a boolean"))
    )

  test("ordinary leaf readers retain their failure at root and reanchor it when placed in an array"):
    val reader = JsonDecoder.arrayOf(JsonDecoder[String])
    assertEquals(
      reader.decode(JsonValue.Arr(Vector(JsonValue.Str("first"), JsonValue.Bool(false)))),
      Left(DecodeFailure(JsonPath.Root.index(1), "expected a string but found a boolean"))
    )
    assertEquals(
      JsonDecoder[String].decodeAt(JsonValue.Bool(false), JsonPath.Root),
      Left(DecodeFailure(JsonPath.Root, "expected a string but found a boolean"))
    )

  test("legacy all projection preserves first-failure behavior and does not silently discard bad members"):
    assertEquals(JsonDecoder.all[String](Vector(JsonValue.Str("one"), JsonValue.Str("two"))), Right(Vector("one", "two")))
    assertEquals(
      JsonDecoder.all[String](Vector(JsonValue.Bool(false), JsonValue.Str("not visited"))),
      Left(DecodeFailure(JsonPath.Root, "expected a string but found a boolean"))
    )
    assertEquals(JsonDecoder.all[String](Vector.empty), Right(Vector.empty))
