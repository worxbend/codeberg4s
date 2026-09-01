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
      Left(DecodeFailure(at.field(field), s"required field '$field' is missing"))

  private val at: JsonPath = JsonPath.Root

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
