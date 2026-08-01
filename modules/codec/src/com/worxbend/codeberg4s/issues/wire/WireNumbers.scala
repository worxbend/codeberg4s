package com.worxbend.codeberg4s.issues.wire

/** Builds the JSON scalars and arrays this group's request bodies are made of.
  *
  * `ujson` has one numeric case, [[ujson.Num]], and it holds a `Double`. Every identifier this library sends is an
  * `int64`, so the conversion happens somewhere; doing it here means it happens once and carries the note that goes
  * with it: a `Double` represents integers exactly only up to 2^53, and Forgejo identifiers are database row ids
  * nowhere near that bound. If that ever stops being true it stops being true in this one place.
  *
  * Internal to this group's wire package, and a candidate to move into `com.worxbend.codeberg4s.codec` once a second
  * endpoint group writes a request body.
  */
private[codeberg4s] object WireNumbers:

  /** One identifier as a JSON number. */
  def identifier(value: Long): ujson.Value =
    ujson.Num(value.toDouble)

  /** A JSON array of identifiers, in the order given. */
  def identifiers(values: Vector[Long]): ujson.Value =
    ujson.Arr.from(values.map(identifier))

  /** A JSON array of strings, in the order given. */
  def strings(values: Vector[String]): ujson.Value =
    ujson.Arr.from(values.map(ujson.Str.apply))
