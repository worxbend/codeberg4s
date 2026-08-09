package com.worxbend.codeberg4s.codec

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter

import scala.annotation.tailrec

/** A parsed JSON document.
  *
  * jsoniter-scala derives a codec per target type and has no document model of its own, which is exactly right for a
  * schema you control — and exactly wrong for this one. `docs/HAZARDS.md` §1 measures that no response definition in
  * the pinned spec declares `required`, that `nullable` never appears, and that live payloads send JSON `null` for
  * fields the spec types as arrays and objects. A derived codec answers that by failing; a document model answers it by
  * letting [[JsonFields]] treat absent, `null` and wrong-kind as one and the same, and letting the DTO decide which
  * fields the domain actually needs.
  *
  * So the boundary is two steps rather than one: parse into this model, then assemble the DTO from it. The parse is
  * jsoniter's, through the hand-written [[JsonValue.codec]] below.
  *
  * ==Numbers come in two cases, and neither of them is a `Double`==
  *
  * The model before this one parsed every number as a `Double`, which silently loses precision above 2^53 — fine for
  * Forgejo's row ids today, but a latent defect rather than a decision. What replaced it made every number a
  * `BigDecimal`, which is exact and costs: measured with `scripts/alloc-bench.sh`, parsing a thousand small integers
  * allocated 88.5 bytes an element against 26.6 for the same array of booleans, so roughly sixty of those bytes were
  * the `BigDecimal` and the `java.math.BigDecimal` inside it. A response is mostly numbers, and every row id, count and
  * timestamp offset in it was paying that.
  *
  * So a number is now one of two cases:
  *
  *   - [[JsonValue.Int64]] holds a `Long`, and is what a number written without a fractional part or an exponent
  *     becomes when it fits in 64 bits — which is every identifier, count and offset a Forgejo response carries;
  *   - [[JsonValue.Decimal]] holds a `BigDecimal`, and is what everything else becomes: a fractional value, an exponent
  *     form, or a whole number too large for a `Long`. Still exact, still never a `Double`.
  *
  * Which case a parsed number lands in follows the text that was on the wire and nothing else, so [[Json.render]]
  * writes back what it read. [[JsonValue.Num]] builds and reads either one without a caller having to know which.
  */
sealed trait JsonValue:

  /** Whether this is the JSON literal `null`. */
  def isNull: Boolean = this match
    case JsonValue.Null => true
    case _              => false

  /** The string, when this is one. */
  def strOpt: Option[String] = this match
    case JsonValue.Str(value) => Some(value)
    case _                    => None

  /** The number, when this is one, as an exact decimal.
    *
    * This builds a `BigDecimal` for an [[JsonValue.Int64]], which is the case almost every number a response carries
    * lands in — so it undoes, at this one call, the saving the two cases exist for. Reach for [[longOpt]] instead
    * whenever a `Long` is what the caller wanted anyway, which in this library it always is.
    */
  def numOpt: Option[BigDecimal] = this match
    case JsonValue.Int64(value)   => Some(BigDecimal(value))
    case JsonValue.Decimal(value) => Some(value)
    case _                        => None

  /** The number, when this is one, truncated toward zero.
    *
    * Truncation is what a wire `int64` field wants and what the previous accessor did — `numOpt.map(_.toLong)` — so a
    * fractional value still answers with its whole part rather than with `None`. Nothing is allocated for an
    * [[JsonValue.Int64]] beyond the `Option` itself.
    */
  def longOpt: Option[Long] = this match
    case JsonValue.Int64(value)   => Some(value)
    case JsonValue.Decimal(value) => Some(value.toLong)
    case _                        => None

  /** Whether this is a number, of either case. */
  def isNum: Boolean = this match
    case JsonValue.Int64(_) | JsonValue.Decimal(_) => true
    case _ => false

  /** The boolean, when this is one. */
  def boolOpt: Option[Boolean] = this match
    case JsonValue.Bool(value) => Some(value)
    case _                     => None

  /** The object's fields in document order, when this is an object. */
  def objOpt: Option[Vector[(String, JsonValue)]] = this match
    case JsonValue.Obj(fields) => Some(fields)
    case _                     => None

  /** The array's elements, when this is an array. */
  def arrOpt: Option[Vector[JsonValue]] = this match
    case JsonValue.Arr(values) => Some(values)
    case _                     => None

  /** The value at `name`, when this is an object that has it.
    *
    * Total, like every accessor here: an absent key, a non-object receiver and an explicit `null` are all `None`. There
    * is deliberately no throwing `apply`; a JSON document is remote input and this library has no accessor that can
    * fail on it.
    *
    * This takes the first field named `name`, which in a parsed document is also the only one: [[JsonValue.Obj]]
    * explains why a document that names a field twice is rejected instead of being read.
    */
  def field(name: String): Option[JsonValue] =
    objOpt.flatMap(_.collectFirst { case (key, value) if key.contentEquals(name) => value }).filterNot(_.isNull)

  /** The object's field names in document order; empty when this is not an object. */
  def keys: Vector[String] = objOpt.fold(Vector.empty)(_.map((name, _) => name))

  /** The JSON kind, for a failure message that says what was found rather than only what was wanted. */
  def kind: String = this match
    case JsonValue.Null    => "null"
    case JsonValue.Bool(_) => "a boolean"
    case JsonValue.Int64(_) | JsonValue.Decimal(_) => "a number"
    case JsonValue.Str(_) => "a string"
    case JsonValue.Arr(_) => "an array"
    case JsonValue.Obj(_) => "an object"

object JsonValue:

  /** The JSON literal `null`. Distinct from a Scala `null`, which this library never produces. */
  case object Null extends JsonValue

  final case class Bool(value: Boolean) extends JsonValue

  /** A whole number that fits in 64 bits — the case nearly every number in a Forgejo response lands in.
    *
    * A parsed document puts a number here when it was written on the wire without a fractional part and without an
    * exponent, and its digits fit a `Long`. `12` is one of these; `12.0` and `1.2e1` are [[Decimal]], because that is
    * what they were written as and [[Json.render]] has to be able to write them back.
    *
    * '''Build one through [[Num]] rather than directly.''' [[Num.apply]] is what decides which of the two cases a value
    * belongs in, and the two are only ever distinguishable if that decision is made in one place:
    * `Decimal(BigDecimal(5))` renders as `5`, exactly as `Int64(5)` does, yet is a different value from it. This is the
    * same unpoliced invariant [[Obj]] carries about a repeated key, and it is unpoliced for the same reason — a
    * constructor cannot refuse a value that is perfectly well formed on its own.
    */
  final case class Int64(value: Long) extends JsonValue

  /** Every number that is not an [[Int64]]: a fractional value, an exponent form, or a whole number past 64 bits.
    *
    * Exact, and deliberately not a `Double` — the model this replaced parsed `9007199254740993` into a `Double` and
    * gave back `9007199254740992` without saying so.
    *
    * The note on [[Int64]] about building through [[Num]] applies here too, and matters more: handing this constructor
    * a value that is a whole number inside the `Long` range produces a document that does not compare equal to the one
    * [[Json.parse]] reads back from its own rendering.
    */
  final case class Decimal(value: BigDecimal) extends JsonValue

  final case class Str(value: String) extends JsonValue

  final case class Arr(values: Vector[JsonValue]) extends JsonValue

  /** An object, keeping its fields in document order.
    *
    * Order is preserved because a request body is rendered from one of these, and a stable field order makes a recorded
    * request assertable.
    *
    * '''A repeated key is a rejected document, not a resolved one.''' JSON does not forbid `{"id":1,"id":2}`, and the
    * three ways of reading this type had drifted into three different answers for it: [[JsonValue.field]] scans and
    * finds the first, `fields.toMap` keeps the last, and [[Json.render]] writes both back out. Rather than pick a
    * winner, [[Json.parse]] refuses the document and says `duplicated field "id"`, so no value parsed by this library
    * ever carries a repeated key and the three readings cannot disagree.
    *
    * Refusing is the narrower promise, and it is the one this library can keep. Choosing a winner would mean silently
    * dropping a value the sender wrote, with no way for a caller to learn that it happened — the same trade
    * [[JsonDecoder.arrayOf]] already refuses when it fails a page rather than skip an element it cannot read. It is
    * also not the leniency `docs/HAZARDS.md` §1 argues for: that leniency is for wire shapes measured coming out of a
    * real Forgejo — `null` for an array, `""` for absent — whereas Forgejo serialises from Go structs and maps and
    * cannot emit a repeated key at all. A response that has one was rewritten between the server and here, which is
    * worth a failure rather than a guess.
    *
    * The one thing this does not police is an object built in code: the constructor takes the vector as given, and
    * [[Json.render]] writes whatever it is handed. Handing it a repeated key produces a request body this library would
    * refuse to read back, so do not.
    */
  final case class Obj(fields: Vector[(String, JsonValue)]) extends JsonValue

  /** Builds and reads a number of either case, so that a caller who has one does not have to know which.
    *
    * This is the only constructor that keeps [[Int64]] and [[Decimal]] apart correctly, and it is why they were split
    * without every call site in the library having to change: `JsonValue.Num(7)` still builds a number and
    * `case JsonValue.Num(value)` still matches one.
    */
  object Num:

    /** A whole number. */
    def apply(value: Long): JsonValue = Int64(value)

    /** A whole number. */
    def apply(value: Int): JsonValue = Int64(value.toLong)

    /** A number from a `Double`, rendered without a fractional part when it has none.
      *
      * `BigDecimal(102.0)` keeps a scale of one and renders `102.0`, which Forgejo's integer fields reject and which
      * would silently change every request body carrying an identifier. A whole value therefore becomes an [[Int64]],
      * which renders `102`.
      *
      * The bound is strict at the top and not at the bottom because `Long.MaxValue` has no exact `Double` — the nearest
      * one is 2^63, one past the largest `Long` — whereas `Long.MinValue` is exactly -2^63 and does. Without the strict
      * bound a `Double` of 2^63 would silently become `Long.MaxValue`, which is the class of quiet rounding this whole
      * type exists to avoid.
      */
    def apply(value: Double): JsonValue =
      if value.isWhole && value >= Long.MinValue.toDouble && value < Long.MaxValue.toDouble then Int64(value.toLong)
      else Decimal(BigDecimal(value))

    /** A number from an exact decimal, put into whichever case renders the same text back.
      *
      * A `BigDecimal` of scale zero that fits a `Long` writes itself as plain digits — `BigDecimal(5)` renders `5` — so
      * it becomes an [[Int64]] and parsing that rendering returns the same value. Everything else keeps its scale and
      * stays a [[Decimal]]: `BigDecimal("1.0")` renders `1.0`, `BigDecimal("1E+3")` renders `1E+3`, and both read back
      * as themselves.
      */
    def apply(value: BigDecimal): JsonValue =
      value.scale match
        case 0 if value.isValidLong => Int64(value.toLong)
        case _                      => Decimal(value)

    /** The number, whichever case it is, as an exact decimal.
      *
      * Present so that `case JsonValue.Num(value)` keeps meaning what it meant when `Num` was a single case class
      * holding a `BigDecimal`. It allocates one for an [[Int64]], so a match that only wants a `Long` should say
      * [[JsonValue.longOpt]] or name the two cases instead.
      */
    def unapply(value: JsonValue): Option[BigDecimal] = value.numOpt

  object Arr:
    /** An array from any collection of values. */
    def from(values: IterableOnce[JsonValue]): Arr = Arr(Vector.from(values))

    /** An array written out element by element. */
    def apply(first: JsonValue, rest: JsonValue*): Arr = Arr(first +: Vector.from(rest))

  object Obj:
    /** An object from any collection of fields, keeping their order. */
    def from(fields: IterableOnce[(String, JsonValue)]): Obj = Obj(Vector.from(fields))

    /** An object written out field by field. */
    def apply(first: (String, JsonValue), rest: (String, JsonValue)*): Obj = Obj(first +: Vector.from(rest))

  /** How deep a document may nest before it is rejected.
    *
    * The reader below is recursive, so an adversarially nested body would otherwise exhaust the stack — a response is
    * remote input, and this library's whole failure contract is that a remote party cannot crash a caller. Forgejo's
    * deepest real payload is a pull request carrying two repositories carrying their owners, nowhere near this.
    */
  val MaxDepth: Int = 128

  /** How many fields an object may have before the repeated-key check stops comparing names and starts hashing them.
    *
    * Both strategies give the same answer; they cost differently. Comparing every pair allocates nothing and is the
    * cheaper of the two while an object is narrow, which the objects nested inside a response mostly are — a
    * repository's `permissions` has three fields and its `internal_tracker` three. The comparisons grow with the square
    * of the width, so a wide object gets a table instead, at the price of one array.
    *
    * Eight is where the pair count (28) stops being obviously smaller than the work of allocating and filling a table.
    * It is a cost split, not a limit: an object of any width is checked, and none is rejected for being wide.
    */
  private val MaxComparedFields: Int = 8

  /** The jsoniter codec for the document model.
    *
    * Hand-written rather than derived: [[JsonValue]] is a recursive sum type whose `Obj` case is an ordered field list,
    * which no derivation config expresses.
    */
  given codec: JsonValueCodec[JsonValue] with

    override def nullValue: JsonValue = JsonValue.Null

    override def decodeValue(in: JsonReader, default: JsonValue): JsonValue = read(in, 0)

    override def encodeValue(value: JsonValue, out: JsonWriter): Unit = write(value, out)

  /** Returned by jsoniter only for a JSON null, which [[read]] intercepts first. Never observed. */
  private val UnreachableString: String = ""

  /** As [[UnreachableString]], for the number reader. */
  private val UnreachableNumber: java.lang.Number = java.lang.Long.valueOf(0L)

  private def read(in: JsonReader, depth: Int): JsonValue =
    if depth > MaxDepth then in.decodeError(s"the document nests deeper than $MaxDepth levels")
    else
      // `null` is the one token jsoniter wants left consumed: readNullOrError
      // reads the remaining "ull" and errors otherwise. Every other reader
      // below re-reads the token itself and so needs it put back.
      in.nextToken() match
        case 'n' => in.readNullOrError(Null, "expected the literal null")
        // The argument to readString and readNumber is what jsoniter returns
        // for a JSON null. Generated codecs pass null there; these defaults are
        // unreachable instead, because the 'n' branch above has already taken
        // every null — which keeps a null literal out of the codebase.
        case '"' => in.rollbackToken(); Str(in.readString(UnreachableString))
        case 't' | 'f' => in.rollbackToken(); Bool(in.readBoolean())
        case '[' => in.rollbackToken(); readArray(in, depth)
        case '{' => in.rollbackToken(); readObject(in, depth)
        case _   => in.rollbackToken(); readNumber(in)

  /** Reads a number into whichever of the two number cases matches how it was written.
    *
    * `readNumber` is jsoniter's own answer to this question and does the hard part: it returns a `java.lang.Long` for a
    * number written without a fractional part or an exponent that fits in 64 bits, a `java.math.BigInteger` for one
    * that does not, and a `java.math.BigDecimal` for everything else. Reading a `BigDecimal` unconditionally, which is
    * what this used to do, is what made every row id cost one.
    *
    * Those three classes are the whole of what 2.40.1 returns — checked against the library rather than recalled. The
    * fourth branch is there because `java.lang.Number` is a plain abstract class that anyone may extend, so the match
    * has to be total; reaching it would mean jsoniter had grown a return type this reader does not know how to keep
    * exactly, and answering with an approximation is the one thing this type must not do.
    *
    * One allocation is left on this path and is jsoniter's rather than this reader's: the `java.lang.Long` it boxes to
    * return, which dies immediately. Avoiding it needs a look-ahead the reader interface does not offer — the only way
    * to it is to try `readLong` behind a mark and catch the failure, which hands a remote party a document that throws
    * once per fractional number.
    */
  private def readNumber(in: JsonReader): JsonValue =
    in.readNumber(UnreachableNumber) match
      case whole: java.lang.Long       => Int64(whole.longValue)
      case exact: java.math.BigDecimal => Decimal(BigDecimal(exact))
      case big: java.math.BigInteger   => Decimal(BigDecimal(BigInt(big)))
      case other                       => in.decodeError(s"read a number as an unsupported ${other.getClass.getName}")

  private def readArray(in: JsonReader, depth: Int): JsonValue =
    if !in.isNextToken('[') then in.decodeError("expected an array")
    else if in.isNextToken(']') then Arr(Vector.empty)
    else
      in.rollbackToken()
      val values = Vector.newBuilder[JsonValue]

      @tailrec def loop(): Unit =
        values.addOne(read(in, depth + 1))
        if in.isNextToken(',') then loop()
        else if !in.isCurrentToken(']') then in.arrayEndOrCommaError()

      loop()
      Arr(values.result())

  private def readObject(in: JsonReader, depth: Int): JsonValue =
    if !in.isNextToken('{') then in.decodeError("expected an object")
    else if in.isNextToken('}') then Obj(Vector.empty)
    else
      in.rollbackToken()
      val fields = Vector.newBuilder[(String, JsonValue)]

      @tailrec def loop(): Unit =
        val name = in.readKeyAsString()
        fields.addOne(name -> read(in, depth + 1))
        if in.isNextToken(',') then loop()
        else if !in.isCurrentToken('}') then in.objectEndOrCommaError()

      loop()
      val entries = fields.result()
      rejectRepeatedKey(in, entries)
      Obj(entries)

  /** Fails the whole document when one object names the same field twice — the rule [[Obj]] documents.
    *
    * Two strategies, because this runs on every object of every response. Up to [[MaxComparedFields]] the check
    * compares the names against each other, which allocates nothing; a wider object is indexed by hash instead, because
    * comparing every pair of a 500-key object is a quarter of a million comparisons and a remote party chooses that
    * width.
    */
  private def rejectRepeatedKey(in: JsonReader, entries: Vector[(String, JsonValue)]): Unit =
    val size = entries.size

    if size <= MaxComparedFields then compareFields(in, entries, size) else indexFields(in, entries, size)

  /** The pairwise check: every field against the ones before it, `size * (size - 1) / 2` comparisons and no allocation. */
  private def compareFields(in: JsonReader, entries: Vector[(String, JsonValue)], size: Int): Unit =
    @tailrec def compare(index: Int, earlier: Int): Unit =
      if index >= size then ()
      else if earlier >= index then compare(index + 1, 0)
      else if entries(earlier)._1.contentEquals(entries(index)._1) then repeatedKeyError(in, entries(index)._1)
      else compare(index, earlier + 1)

    compare(1, 0)

  /** The hashed check: one open-addressed table of field positions, probed linearly.
    *
    * A slot holds a field's position plus one, so that a fresh array of zeroes already reads as an empty table. Names
    * are still compared in full on a hash hit — a hash agreeing is not two names agreeing, and a check that took it for
    * one would reject a document over a collision.
    */
  private def indexFields(in: JsonReader, entries: Vector[(String, JsonValue)], size: Int): Unit =
    // A power of two, so a probe wraps with a mask; at least twice the field
    // count, so that the table stays under half full and a probe stays short.
    val slots = new Array[Int](Integer.highestOneBit(size) * 2)
    val mask  = slots.length - 1

    @tailrec def probe(index: Int, slot: Int): Unit =
      val taken = slots(slot) - 1

      if taken < 0 then slots(slot) = index + 1
      else if entries(taken)._1.contentEquals(entries(index)._1) then repeatedKeyError(in, entries(index)._1)
      else probe(index, (slot + 1) & mask)

    @tailrec def indexFrom(index: Int): Unit =
      if index < size then
        // The high bits of a String hash carry most of its entropy for the
        // short, similar names a JSON object has, and the mask below keeps only
        // the low ones. Folding one half onto the other is what java.util.HashMap
        // does about that, for the same reason.
        val hash = entries(index)._1.hashCode

        probe(index, (hash ^ (hash >>> 16)) & mask)
        indexFrom(index + 1)

    indexFrom(0)

  /** The failure a repeated field produces, worded as jsoniter's own `duplicatedKeyError` words it.
    *
    * That method is not called here because it formats the name out of the reader's character buffer, which by the time
    * an object is complete holds the last string the reader saw rather than the offending key. `Json.parse` bounds the
    * message at `Json.MaxReasonLength`, so a pathologically long key cannot turn this into a payload dump.
    */
  private def repeatedKeyError(in: JsonReader, name: String): Nothing =
    in.decodeError(s"""duplicated field "$name"""")

  private def write(value: JsonValue, out: JsonWriter): Unit =
    value match
      case Null            => out.writeNull()
      case Bool(flag)      => out.writeVal(flag)
      case Int64(whole)    => out.writeVal(whole)
      case Decimal(number) => out.writeVal(number)
      case Str(text)       => out.writeVal(text)
      case Arr(values)     =>
        out.writeArrayStart()
        values.foreach(element => write(element, out))
        out.writeArrayEnd()
      case Obj(fields)     =>
        out.writeObjectStart()
        fields.foreach: (name, field) =>
          out.writeKey(name)
          write(field, out)
        out.writeObjectEnd()
