package com.worxbend.codeberg4s.codec

/** A total, read-only view over the fields of one decoded JSON object.
  *
  * Every accessor answers `None` (or an empty collection) instead of failing, which is the only workable reading of the
  * Forgejo response contract: `docs/HAZARDS.md` §1 measures that **no** response definition in the pinned spec declares
  * `required` and that live payloads send JSON `null` for fields the spec types as arrays and objects. An accessor
  * therefore treats these three wire shapes as one and the same:
  *
  *   - the key is absent,
  *   - the key is present with the value `null`,
  *   - the key is present with a value of the wrong JSON kind.
  *
  * The third case is deliberate leniency, not an oversight. A single field that Forgejo starts sending as a string
  * instead of a number must not cost the caller the other sixty fields of the same object. What it must not do is
  * silently invent data: a field the domain genuinely needs is reported by the DTO's `toDomain`, which returns
  * [[com.worxbend.codeberg4s.core.DecodeFailure]] naming the field. Structural failures — a body that is not JSON at
  * all, or an array where an object was expected — are still failures, and are raised by upickle before this view is
  * ever built.
  *
  * Instances are immutable and safe to share.
  *
  * @param underlying
  *   the decoded object, keyed by the wire (snake_case) field name
  */
final case class JsonFields(underlying: Map[String, ujson.Value]):

  /** The raw value at `name`, absent when the key is missing or explicitly `null`. */
  def value(name: String): Option[ujson.Value] =
    underlying.get(name).filterNot(_.isNull)

  /** The string at `name`, verbatim — the empty string is preserved. Use [[text]] to fold Forgejo's `""`-for-absent
    * convention away.
    */
  def rawText(name: String): Option[String] =
    value(name).flatMap(_.strOpt)

  /** The string at `name`, with blank treated as absent.
    *
    * Forgejo returns `""` rather than `null` for unset text — `login_name`, `language`, `location`, `website` and
    * `description` are all `""` on `golden/user/user-single.json`. Collapsing the two spellings here keeps the
    * distinction out of the domain.
    */
  def text(name: String): Option[String] =
    rawText(name).filter(_.trim.nonEmpty)

  /** The number at `name`, truncated to a `Long`.
    *
    * ujson parses every JSON number as a `Double`, so an identifier beyond 2^53 would lose precision. Forgejo
    * identifiers are database row ids and nowhere near that bound; if that ever changes it will change here, in one
    * place.
    */
  def number(name: String): Option[Long] =
    value(name).flatMap(_.numOpt).map(_.toLong)

  /** The boolean at `name`. */
  def boolean(name: String): Option[Boolean] =
    value(name).flatMap(_.boolOpt)

  /** The nested object at `name`, as another view. */
  def nested(name: String): Option[JsonFields] =
    value(name).flatMap(_.objOpt).map(entries => JsonFields(entries.toMap))

  /** The elements of the array at `name`; empty when the key is absent, `null`, or not an array. */
  def values(name: String): Vector[ujson.Value] =
    value(name).flatMap(_.arrOpt).fold(Vector.empty)(_.toVector)

  /** The elements of the array at `name` that are strings; non-strings are dropped rather than failing. */
  def texts(name: String): Vector[String] =
    values(name).flatMap(_.strOpt)

  /** The elements of the array at `name` that are objects, each as another view. */
  def nestedAll(name: String): Vector[JsonFields] =
    values(name).flatMap(_.objOpt).map(entries => JsonFields(entries.toMap))

object JsonFields:

  /** An empty view — every accessor answers as though the object had no keys. */
  val Empty: JsonFields = JsonFields(Map.empty)

  /** Builds an upickle `Reader` for a type assembled field by field from one JSON object.
    *
    * `build` must be total. The reader delegates the "is this even an object?" question to upickle's own map reader, so
    * a body that is an array, a number or malformed fails there, with upickle's message and with the JSON path that
    * [[Json.decode]] recovers — no exception is raised by this module.
    *
    * A body that is the bare literal `null` never reaches `build` at all — upickle short-circuits it — and is rejected
    * by [[Json.decode]] instead. A `null` in a '''field''' position is a different matter and is absence, per the
    * accessors above.
    *
    * @param build
    *   assembles the value from the object's fields
    */
  def reader[A](build: JsonFields => A): upickle.default.Reader[A] =
    upickle.default
      .reader[Map[String, ujson.Value]]
      .map(entries => build(JsonFields(entries)))
