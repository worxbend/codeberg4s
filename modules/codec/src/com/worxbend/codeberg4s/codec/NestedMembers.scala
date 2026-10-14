package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

import scala.annotation.tailrec

/** Decoder-boundary checks for selected DTO fields, not a global JSON schema or a stricter JsonFields view. Whole
  * missing/null/wrong-kind containers remain lenient; actual array members cannot silently disappear.
  */
private[codeberg4s] object NestedMembers:

  type Check = (JsonFields, JsonPath) => Either[DecodeFailure, Unit]

  /** Checks fields in declaration order and stops at the first malformed member. */
  def objectArrays(fields: JsonFields, at: JsonPath, names: String*): Either[DecodeFailure, Unit] =
    @tailrec def loop(index: Int): Either[DecodeFailure, Unit] =
      if index >= names.size then Right(())
      else
        objectArray(fields, at, names(index)) match
          case Left(failure) => Left(failure)
          case Right(_)      => loop(index + 1)
    loop(0)

  def objectArray(fields: JsonFields, at: JsonPath, name: String): Either[DecodeFailure, Unit] =
    kindArray(fields, at, name, "an object")

  def objects(fields: JsonFields, at: JsonPath, name: String)(child: Check): Either[DecodeFailure, Unit] =
    checkArray(fields, at, name): (value, path) =>
      value match
        case JsonValue.Obj(entries) => child(JsonFields(entries), path)
        case other                  => Left(DecodeFailure(path, s"expected an object but found ${other.kind}"))

  def nested(fields: JsonFields, at: JsonPath, name: String)(child: Check): Either[DecodeFailure, Unit] =
    fields.nested(name).fold[Either[DecodeFailure, Unit]](Right(()))(child(_, at.field(name)))

  def strings(fields: JsonFields, at: JsonPath, name: String): Either[DecodeFailure, Unit] =
    kindArray(fields, at, name, "a string")

  /** The same kind rule applies to every actual member, but never to an absent or wrong-kind container. */
  private def kindArray(
      fields: JsonFields,
      at: JsonPath,
      name: String,
      expected: String,
  ): Either[DecodeFailure, Unit] =
    checkArray(fields, at, name): (value, path) =>
      if value.kind.contentEquals(expected) then Right(())
      else Left(DecodeFailure(path, s"expected $expected but found ${value.kind}"))

  /** Validation-only traversal: retain original positions without building a vector of discarded results. */
  private def checkArray(fields: JsonFields, at: JsonPath, name: String)(
      check: (JsonValue, JsonPath) => Either[DecodeFailure, Unit]
  ): Either[DecodeFailure, Unit] =
    val values                                                 = fields.values(name)
    val path                                                   = at.field(name)
    @tailrec def loop(index: Int): Either[DecodeFailure, Unit] =
      if index >= values.size then Right(())
      else
        check(values(index), path.index(index)) match
          case Left(failure) => Left(failure)
          case Right(_)      => loop(index + 1)
    loop(0)
