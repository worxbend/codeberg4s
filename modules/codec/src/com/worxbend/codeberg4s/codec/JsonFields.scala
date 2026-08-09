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
  *     The index costs one small `Array[Int]`, which on the same fifty-repository page adds 35,200 bytes back — so the
  *     measured saving over the whole decode is 785,200 bytes rather than the full 819,600.
  *
  * '''No field can be shadowed by another.''' Both strategies answer with the *first* field of a given name — a scan
  * because it stops there, the index because a colliding name inserted later probes past the earlier one — and
  * [[JsonValue.field]] answers the same way. The question does not arise in a parsed document at all: [[Json.parse]]
  * rejects one that names a field twice, for the reasons set out on [[JsonValue.Obj]].
  *
  * Instances are immutable and safe to share.
  *
  * @param entries
  *   the object's fields in document order, named as they were on the wire (snake_case)
  */
final case class JsonFields(entries: Vector[(String, JsonValue)]):

  /** Field positions by name hash for a wide object, empty for a narrow one; see `JsonFields.index`. */
  private val slots: Array[Int] = JsonFields.index(entries)

  /** The raw value at `name`, absent when the key is missing or explicitly `null`. */
  def value(name: String): Option[JsonValue] =
    lookup(name).filterNot(_.isNull)

  /** The first field named `name`, or `None` when the object has no such field. */
  private def lookup(name: String): Option[JsonValue] =
    val at = if slots.isEmpty then scanFor(name, 0) else probeFor(name, JsonFields.slotFor(name, slots.length))

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

  /** The position [[slots]] records for `name`, or `-1`, probing on from `slot` while the slot is taken by another
    * name.
    *
    * Names are compared in full on a hash hit: a hash agreeing is not two names agreeing, and answering on the hash
    * alone would return a neighbouring field's value whenever two names in one object collided.
    */
  @tailrec
  private def probeFor(name: String, slot: Int): Int =
    val taken = slots(slot) - 1

    if taken < 0 then -1
    else if entries(taken)._1.contentEquals(name) then taken
    else probeFor(name, (slot + 1) & (slots.length - 1))

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
    * An identifier beyond 2^53 keeps its precision on the way through: the document model reads a whole number straight
    * into a `Long` and anything else into a `BigDecimal`, neither of which rounds. The model before it parsed every
    * number as a `Double` and would have lost the identifier silently.
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

  /** An open-addressed table of field positions, keyed by the hash of the field name.
    *
    * A narrow object gets `Array.emptyIntArray`, the standard library's shared zero-length array, so that being narrow
    * costs no allocation at all — and so that this answer cannot depend on a field of this object being initialised.
    *
    * A slot holds a position plus one, so a fresh array of zeroes already reads as an empty table. Insertion runs
    * forwards through `entries`, which is what makes a probe answer with the first of two fields sharing a name: the
    * earlier one already occupies the slot the later one would want, and the later one is pushed past it.
    */
  private def index(entries: Vector[(String, JsonValue)]): Array[Int] =
    val size = entries.length

    if size <= MaxScannedFields then Array.emptyIntArray
    else
      // A power of two, so a probe wraps with a mask; at least twice the field
      // count, so the table stays under half full and a probe stays short.
      val slots = new Array[Int](Integer.highestOneBit(size) * 2)

      @tailrec def place(position: Int, slot: Int): Unit =
        if slots(slot) < 1 then slots(slot) = position + 1
        else place(position, (slot + 1) & (slots.length - 1))

      @tailrec def placeFrom(position: Int): Unit =
        if position < size then
          place(position, slotFor(entries(position)._1, slots.length))
          placeFrom(position + 1)

      placeFrom(0)
      slots

  /** Where a name's probe starts in a table of `length` slots.
    *
    * The high bits of a `String` hash carry most of its entropy for the short, similar names a JSON object has, and the
    * mask keeps only the low ones. Folding one half onto the other is what `java.util.HashMap` does about that, for the
    * same reason.
    */
  private def slotFor(name: String, length: Int): Int =
    val hash = name.hashCode

    (hash ^ (hash >>> 16)) & (length - 1)

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
