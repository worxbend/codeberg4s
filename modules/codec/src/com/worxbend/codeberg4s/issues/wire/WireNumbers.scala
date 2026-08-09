package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.JsonValue

/** Builds the JSON scalars and arrays this group's request bodies are made of.
  *
  * [[JsonValue.Num]] puts a whole number into the document model's `Long` case, so an `int64` identifier reaches the
  * wire exactly — a document model that held a `Double`, as an earlier one did, represents integers exactly only up to
  * 2^53. Building the scalars here rather than at each call site means the conversion happens once.
  *
  * Internal to this group's wire package, and a candidate to move into `com.worxbend.codeberg4s.codec` once a second
  * endpoint group writes a request body.
  */
private[codeberg4s] object WireNumbers:

  /** One identifier as a JSON number. */
  def identifier(value: Long): JsonValue =
    JsonValue.Num(value)

  /** One whole number as a JSON number, for an `int64` wire field that is not an identifier — a duration in seconds,
    * say. Kept distinct from [[identifier]] so a reader of a request builder can tell which is which; the exactness
    * above applies to both.
    */
  def whole(value: Long): JsonValue =
    JsonValue.Num(value)

  /** A JSON array of identifiers, in the order given. */
  def identifiers(values: Vector[Long]): JsonValue =
    JsonValue.Arr.from(values.map(identifier))

  /** A JSON array of strings, in the order given. */
  def strings(values: Vector[String]): JsonValue =
    JsonValue.Arr.from(values.map(JsonValue.Str.apply))
