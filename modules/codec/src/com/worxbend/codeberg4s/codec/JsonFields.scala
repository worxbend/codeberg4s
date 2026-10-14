package com.worxbend.codeberg4s.codec

import scala.annotation.tailrec

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
  * all, or an array where an object was expected — are still failures, and are reported by [[JsonDecoder.objectOf]]
  * before this view is ever built.
  *
  * ==Why this is the parser's own vector and not a map==
  *
  * [[JsonValue.Obj]] already holds an object's fields as a `Vector[(String, JsonValue)]` in document order, because a
  * request body is rendered from one of these and a stable field order makes a recorded request assertable. This view
  * used to copy that vector into a `Map` — once per object, at every level of every response — and then look fields up
  * by hash.
  *
  * Building the map was the single largest allocation in the decode path. Measured with `scripts/alloc-bench.sh` on a
  * page of fifty repositories: 1,944,816 bytes to decode the page, of which the `toMap` calls were 819,600 — 42% of the
  * whole decode, spent copying a list the parser had already built into a structure thrown away one object later.
  *
  * So the vector is kept and a field is found by looking through it. How it is looked through depends on how wide the
  * object is, and the split is the one [[JsonValue]] already makes for its repeated-key check, at the same width and
  * for the same reason:
  *
  *   - a narrow object has its names compared one after another. That allocates nothing and beats a hash lookup
  *     outright at this width, and most of the objects a Forgejo response nests are this narrow — a repository's
  *     `permissions` has three fields and its `internal_tracker` three.
  *   - a wide object has its field positions indexed by name hash once, at construction, and a lookup probes that index
  *     instead. This is what keeps a wide object off the quadratic path: `RepositoryDto` reads 63 fields out of a
  *     64-key object, and scanning for each of them would compare about two thousand names to assemble one repository.
  *     The original index's small `Array[Int]` added 35,200 bytes on that fifty-repository page, leaving a historical
  *     saving of 785,200 bytes. The bounded collision index also carries private metadata; compare the current normal
  *     workload benchmark rather than treating that older allocation measurement as a current guarantee.
  *
  * '''No field can be shadowed by another.''' Both strategies answer with the *first* field of a given name — a scan
  * because it stops there, the index because a repeated name retains its earlier position — and [[JsonValue.field]]
  * answers the same way. The question does not arise in a parsed document at all: [[Json.parse]] rejects one that names
  * a field twice, for the reasons set out on [[JsonValue.Obj]].
  *
  * Wide indexes cap linear probing and switch hostile collision clusters to ordered positions; see [[FieldIndex]].
  * Instances are immutable and safe to share.
  *
  * @param entries
  *   the object's fields in document order, named as they were on the wire (snake_case)
  */
final case class JsonFields(entries: Vector[(String, JsonValue)]):

  /** Bounded name index for a wide object, absent for a narrow one; see `JsonFields.index`. */
  private val index: Option[FieldIndex] = JsonFields.index(entries)

  /** The raw value at `name`, absent when the key is missing or explicitly `null`. */
  def value(name: String): Option[JsonValue] =
    lookup(name).filterNot(_.isNull)

  /** The first field named `name`, or `None` when the object has no such field. */
  private def lookup(name: String): Option[JsonValue] =
    val at = index match
      case None        => scanFor(name, 0)
      case Some(built) => built.lookup(entries, name)

    if at < 0 then None else Some(entries(at)._2)

  /** The position of the first field named `name` at or after `index`, or `-1`.
    *
    * An indexed loop rather than `entries.indexWhere`, because this runs once per field of every object of every
    * response and the closure `indexWhere` takes would be allocated at each of those call sites.
    */
  @tailrec
  private def scanFor(name: String, index: Int): Int =
    if index >= entries.length then -1
    else if entries(index)._1.contentEquals(name) then index
    else scanFor(name, index + 1)

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

  /** The number at `name`, truncated to a `Long`; absent when it does not fit one.
    *
    * An identifier beyond 2^53 keeps its precision on the way through: the document model reads a whole number straight
    * into a `Long` and anything else into a `BigDecimal`, neither of which rounds. The model before it parsed every
    * number as a `Double` and would have lost the identifier silently. A whole number past `Long.MaxValue` is treated
    * like a value of the wrong kind rather than wrapped — see [[JsonValue.longOpt]] — so a field the domain needs then
    * fails at its own name in `toDomain` instead of arriving as a different identifier.
    */
  def number(name: String): Option[Long] =
    value(name).flatMap(_.longOpt)

  /** The boolean at `name`. */
  def boolean(name: String): Option[Boolean] =
    value(name).flatMap(_.boolOpt)

  /** The nested object at `name`, as another view. */
  def nested(name: String): Option[JsonFields] =
    value(name).flatMap(_.objOpt).map(JsonFields.apply)

  /** The elements of the array at `name`; empty when the key is absent, `null`, or not an array. */
  def values(name: String): Vector[JsonValue] =
    value(name).flatMap(_.arrOpt).fold(Vector.empty)(_.toVector)

  /** The elements of the array at `name` that are strings; non-strings are dropped rather than failing. */
  def texts(name: String): Vector[String] =
    values(name).flatMap(_.strOpt)

  /** The elements of the array at `name` that are objects, each as another view. */
  def nestedAll(name: String): Vector[JsonFields] =
    values(name).flatMap(_.objOpt).map(JsonFields.apply)

  /** The fields as a map, for the few payloads whose keys are data rather than a schema.
    *
    * An EditorConfig response, a hook's `config` and a language breakdown all have keys the caller has never heard of
    * and must enumerate, and a map is the shape their DTO exposes. Everything else reads named fields through the
    * accessors above and must not call this: it copies the vector, which is the cost this type exists to avoid.
    *
    * A repeated key cannot reach here — [[Json.parse]] rejects the document first — so nothing is dropped by the copy.
    */
  def toMap: Map[String, JsonValue] =
    entries.toMap

object JsonFields:

  /** How many fields an object may have before a lookup stops comparing names and starts probing a hash index.
    *
    * The same number [[JsonValue]] splits its repeated-key check at, for the same reason: below this width comparing
    * names is cheaper than building and probing a table, and above it the comparisons grow with the square of the
    * width, because a DTO reads about as many fields as the object has. It is a cost split and not a limit — an object
    * of any width is readable, and none is rejected for being wide.
    *
    * `inline` so that it is a compile-time constant rather than a field of this object. [[Empty]] below builds a view,
    * which reads this while the object is still initialising, and a plain `val` declared after it would read as zero.
    */
  private inline val MaxScannedFields = 8

  /** An empty view — every accessor answers as though the object had no keys. */
  val Empty: JsonFields = JsonFields(Vector.empty)

  /** Narrow views keep the allocation-free scan; wider views share the parser's bounded collision fallback. */
  private def index(entries: Vector[(String, JsonValue)]): Option[FieldIndex] =
    if entries.size <= MaxScannedFields then None else Some(FieldIndex.build(entries))

  /** Builds a decoder for a type assembled field by field from one JSON object.
    *
    * `build` must be total; every accessor above answers rather than fails, so it can be. The "is this even an object?"
    * question belongs to [[JsonDecoder.objectOf]], which rejects an array, a number, or the bare literal `null` with a
    * message naming what was found. A `null` in a field position is a different matter and is absence, per the
    * accessors above.
    *
    * @param build
    *   assembles the value from the object's fields
    */
  def reader[A](build: JsonFields => A): JsonDecoder[A] =
    JsonDecoder.objectOf(build)
