package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.JsonValue

/** Builds the JSON scalars and arrays this group's request bodies are made of.
  *
  * [[JsonValue.Num]] holds a `BigDecimal`, so an `int64` identifier reaches the wire exactly — the previous document
  * model held a `Double` and represented integers exactly only up to 2^53. Building the scalars here rather than at
  * each call site means the conversion happens once.
  *
  * Internal to this group's wire package, and a candidate to move into `com.worxbend.codeberg4s.codec` once a second
  * endpoint group writes a request body.
  */
private[codeberg4s] object WireNumbers:

  /** One identifier as a JSON number. */
  def identifier(value: Long): JsonValue =
    JsonValue.Num(value)

  /** One whole number as a JSON number, for an `int64` wire field that is not an identifier — a duration in seconds,
    * say. Kept distinct from [[identifier]] so a reader of a request builder can tell which is which; the `Double`
    * caveat above applies to both.
    */
  def whole(value: Long): JsonValue =
    JsonValue.Num(value)

  /** A JSON array of identifiers, in the order given. */
  def identifiers(values: Vector[Long]): JsonValue =
    JsonValue.Arr.from(values.map(identifier))

  /** A JSON array of strings, in the order given. */
  def strings(values: Vector[String]): JsonValue =
    JsonValue.Arr.from(values.map(JsonValue.Str.apply))
