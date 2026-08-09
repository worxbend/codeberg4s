package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

/** Turns a parsed document into a wire DTO.
  *
  * This replaces the `Reader` a derivation-based library would supply, and it is deliberately not derived. Every DTO in
  * this library is assembled field by field from a [[JsonFields]] view, because `docs/HAZARDS.md` §1 measures that the
  * API sends `null` for fields the spec types as arrays and objects, and omits fields the spec declares — behaviour no
  * derived codec expresses without a configuration knob per field.
  *
  * Instances are total on their input and stateless, so they are safe to share between threads.
  *
  * @tparam A
  *   the DTO this builds
  */
trait JsonDecoder[A]:

  /** Interprets a parsed document, or says why it could not. */
  def decode(value: JsonValue): Either[DecodeFailure, A]

  /** Adapts this decoder's result, for a DTO that wraps another. */
  final def map[B](f: A => B): JsonDecoder[B] =
    (value: JsonValue) => decode(value).map(f)

object JsonDecoder:

  /** Summons the decoder for `A`. */
  def apply[A](using decoder: JsonDecoder[A]): JsonDecoder[A] = decoder

  /** A decoder for a type assembled from the fields of one JSON object.
    *
    * `build` must be total — every accessor on [[JsonFields]] answers rather than fails, so it can be. The one thing
    * this decoder rejects is a document that is not an object at all: an array where an object was expected, or the
    * bare literal `null`. That is a structural mismatch rather than a missing field, and it is worth failing on,
    * because it means the endpoint returned something other than what it documents.
    *
    * @param build
    *   assembles the value from the object's fields
    */
  def objectOf[A](build: JsonFields => A): JsonDecoder[A] =
    case JsonValue.Obj(fields) => Right(build(JsonFields(fields)))
    case other                 => Left(DecodeFailure(JsonPath.Root, s"expected an object but found ${other.kind}"))

  /** A decoder for a top-level array of objects, which is what most Forgejo list endpoints return.
    *
    * An element that is not an object fails the whole page, naming its position — a listing that silently dropped a
    * malformed element would under-report, which is worse than failing.
    */
  def arrayOf[A](element: JsonDecoder[A]): JsonDecoder[Vector[A]] =
    case JsonValue.Arr(values) =>
      ArrayElements.convert(values): (raw, at) =>
        element.decode(raw).left.map(failure => failure.copy(path = JsonPath.Root.index(at)))
    case other                 => Left(DecodeFailure(JsonPath.Root, s"expected an array but found ${other.kind}"))

  /** As [[objectOf]], for a build step that can itself fail — an envelope whose elements are decoded, say. */
  def objectOfEither[A](build: JsonFields => Either[DecodeFailure, A]): JsonDecoder[A] =
    case JsonValue.Obj(fields) => build(JsonFields(fields))
    case other                 => Left(DecodeFailure(JsonPath.Root, s"expected an object but found ${other.kind}"))

  /** Decodes every element of an already-extracted array, failing on the first element that will not decode.
    *
    * Failing rather than dropping is deliberate: a listing that silently discarded a malformed element would
    * under-report, and a caller cannot tell an under-report from a short page.
    */
  def all[A](values: Vector[JsonValue])(using element: JsonDecoder[A]): Either[DecodeFailure, Vector[A]] =
    ArrayElements.convert(values)((raw, _) => element.decode(raw))

  /** A decoder that hands the parsed document over untouched, for the few payloads whose shape is not fixed. */
  given identity: JsonDecoder[JsonValue] = (value: JsonValue) => Right(value)

  /** Every Forgejo list endpoint answers a top-level array, so a decoder for one element gives a decoder for a page.
    *
    * A derivation-based library supplies this for free; here it is one line, and it is the line that keeps every
    * `Json.decoder[Vector[SomeDto]]` call site working without each listing declaring its own.
    */
  given vector[A](using element: JsonDecoder[A]): JsonDecoder[Vector[A]] = arrayOf(element)

  /** A JSON object, as a map from field name to value, for a caller decoding a payload whose keys are data rather than
    * a schema — an EditorConfig, say.
    *
    * This is the one place in the library that pays for a map. [[JsonFields]] reads named fields straight out of the
    * vector the parser built; a caller who does not know the names has to enumerate them, and a map is the shape that
    * caller wants. Anything with a fixed set of fields should use [[JsonFields.reader]] instead.
    */
  given fields: JsonDecoder[Map[String, JsonValue]] = objectOf(_.toMap)

  /** A JSON string.
    *
    * Strict about the JSON kind, unlike the previous library, which coerced a number into a string so that
    * `{"name": 7}` decoded as `"7"`. Nothing in this library wanted that, and a silent coercion at the boundary is how
    * a wrong field reaches the domain looking right.
    */
  given string: JsonDecoder[String] =
    case JsonValue.Str(value) => Right(value)
    case other                => Left(DecodeFailure(JsonPath.Root, s"expected a string but found ${other.kind}"))

  /** A JSON boolean. */
  given boolean: JsonDecoder[Boolean] =
    case JsonValue.Bool(value) => Right(value)
    case other                 => Left(DecodeFailure(JsonPath.Root, s"expected a boolean but found ${other.kind}"))

  /** A JSON number, truncated toward zero.
    *
    * The two number cases are named rather than matched through `JsonValue.Num`, whose extractor would build a
    * `BigDecimal` for the [[JsonValue.Int64]] case that this decoder would then throw away — which is the cost the two
    * cases exist to avoid.
    */
  given long: JsonDecoder[Long] =
    case JsonValue.Int64(value)   => Right(value)
    case JsonValue.Decimal(value) => Right(value.toLong)
    case other                    => Left(DecodeFailure(JsonPath.Root, s"expected a number but found ${other.kind}"))
