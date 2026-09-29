package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

import munit.FunSuite

/** The path-reporting contract of the [[Wire]] helpers.
  *
  * The mistake these tests pin down is the one a hand-written conversion makes silently: converting a nested DTO at the
  * '''enclosing''' path, so a failure inside a repository's owner is reported as `$.login` and a reader goes looking
  * for a top-level field that does not exist.
  */
final class WireSuite extends FunSuite:

  /** A nested DTO stand-in whose conversion always fails, at whatever path it is handed. */
  private final case class FailingDto(field: String):

    def toDomainAt(at: JsonPath): Either[DecodeFailure, Nothing] =
      Wire.required(at, field, None)

  private val at: JsonPath = JsonPath.Root

  test("required names every wire shape JsonFields folds into absence, not only a missing key"):
    // From here the three are indistinguishable, and a message that said only
    // "missing" sent a reader looking for a key that was in the payload the
    // whole time, holding a value of the wrong kind.
    val result = Wire.required(at, "id", Option.empty[Long])

    assertEquals(result.left.map(_.path.render), Left("$.id"))
    assertEquals(
      result.left.map(_.message),
      Left("required field 'id' is missing, null, or not of the expected JSON kind")
    )

  test("required hands a present value back untouched"):
    assertEquals(Wire.required(at, "id", Some(7L)), Right(7L))

  test("requiredNested reports an absent field at that field's path"):
    val result = Wire.requiredNested(at, "owner", None)((dto: FailingDto, path) => dto.toDomainAt(path))
    assertEquals(result.left.map(_.path.render), Left("$.owner"))

  test("requiredNested reports a nested failure at the nested path, not the enclosing one"):
    val result = Wire.requiredNested(at, "owner", Some(FailingDto("login")))(_.toDomainAt(_))
    assertEquals(result.left.map(_.path.render), Left("$.owner.login"))

  test("nested reports a nested failure at the nested path too"):
    val result = Wire.nested(at, "owner", Some(FailingDto("login")))(_.toDomainAt(_))
    assertEquals(result.left.map(_.path.render), Left("$.owner.login"))

  test("nested keeps absence rather than failing"):
    val result = Wire.nested(at, "owner", Option.empty[FailingDto])(_.toDomainAt(_))
    assertEquals(result, Right(None))
