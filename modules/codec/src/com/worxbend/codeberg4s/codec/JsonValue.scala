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
  * Numbers are `BigDecimal` rather than `Double`. The previous model parsed every number as a `Double`, which silently
  * loses precision above 2^53 — fine for Forgejo's row ids today, but a latent defect rather than a decision.
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

  /** The number, when this is one. */
  def numOpt: Option[BigDecimal] = this match
    case JsonValue.Num(value) => Some(value)
    case _                    => None

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
    */
  def field(name: String): Option[JsonValue] =
    objOpt.flatMap(_.collectFirst { case (key, value) if key.contentEquals(name) => value }).filterNot(_.isNull)

  /** The object's field names in document order; empty when this is not an object. */
  def keys: Vector[String] = objOpt.fold(Vector.empty)(_.map((name, _) => name))

  /** The JSON kind, for a failure message that says what was found rather than only what was wanted. */
  def kind: String = this match
    case JsonValue.Null    => "null"
    case JsonValue.Bool(_) => "a boolean"
    case JsonValue.Num(_)  => "a number"
    case JsonValue.Str(_)  => "a string"
    case JsonValue.Arr(_)  => "an array"
    case JsonValue.Obj(_)  => "an object"

object JsonValue:

  /** The JSON literal `null`. Distinct from a Scala `null`, which this library never produces. */
  case object Null extends JsonValue

  final case class Bool(value: Boolean) extends JsonValue

  final case class Num(value: BigDecimal) extends JsonValue

  final case class Str(value: String) extends JsonValue

  final case class Arr(values: Vector[JsonValue]) extends JsonValue

  /** An object, keeping its fields in document order.
    *
    * Order is preserved because a request body is rendered from one of these, and a stable field order makes a recorded
    * request assertable.
    */
  final case class Obj(fields: Vector[(String, JsonValue)]) extends JsonValue

  object Num:
    def apply(value: Long): Num = Num(BigDecimal(value))

    def apply(value: Int): Num = Num(BigDecimal(value))

    /** A number from a `Double`, rendered without a fractional part when it has none.
      *
      * `BigDecimal(102.0)` keeps a scale of one and renders `102.0`, which Forgejo's integer fields reject and which
      * would silently change every request body carrying an identifier. A whole value therefore becomes a whole
      * `BigDecimal`.
      */
    def apply(value: Double): Num =
      if value.isWhole then Num(BigDecimal(value.toLong)) else Num(BigDecimal(value))

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
  private val UnreachableNumber: BigDecimal = BigDecimal(0)

  private def read(in: JsonReader, depth: Int): JsonValue =
    if depth > MaxDepth then in.decodeError(s"the document nests deeper than $MaxDepth levels")
    else
      // `null` is the one token jsoniter wants left consumed: readNullOrError
      // reads the remaining "ull" and errors otherwise. Every other reader
      // below re-reads the token itself and so needs it put back.
      in.nextToken() match
        case 'n' => in.readNullOrError(Null, "expected the literal null")
        // The argument to readString and readBigDecimal is what jsoniter returns
        // for a JSON null. Generated codecs pass null there; these defaults are
        // unreachable instead, because the 'n' branch above has already taken
        // every null — which keeps a null literal out of the codebase.
        case '"' => in.rollbackToken(); Str(in.readString(UnreachableString))
        case 't' | 'f' => in.rollbackToken(); Bool(in.readBoolean())
        case '[' => in.rollbackToken(); readArray(in, depth)
        case '{' => in.rollbackToken(); readObject(in, depth)
        case _   => in.rollbackToken(); Num(in.readBigDecimal(UnreachableNumber))

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
      Obj(fields.result())

  private def write(value: JsonValue, out: JsonWriter): Unit =
    value match
      case Null        => out.writeNull()
      case Bool(flag)  => out.writeVal(flag)
      case Num(number) => out.writeVal(number)
      case Str(text)   => out.writeVal(text)
      case Arr(values) =>
        out.writeArrayStart()
        values.foreach(element => write(element, out))
        out.writeArrayEnd()
      case Obj(fields) =>
        out.writeObjectStart()
        fields.foreach: (name, field) =>
          out.writeKey(name)
          write(field, out)
        out.writeObjectEnd()
