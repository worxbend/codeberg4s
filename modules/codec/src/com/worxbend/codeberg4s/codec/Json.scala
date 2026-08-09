package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.core.ResponseBody

import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString

import scala.util.control.NonFatal

/** The single door between a response body and a wire DTO.
  *
  * Nothing else in this library asks jsoniter to read anything. Keeping the call in one place is what lets the module
  * promise that a decoding failure is always a [[com.worxbend.codeberg4s.core.DecodeFailure]] value and never an
  * escaping `JsonReaderException` — the promise ADR-0003 makes and `SCALA_CODE_STYLE.md` restates as "malformed and
  * unexpected JSON produces a failure value, never an exception".
  *
  * '''Bytes are the primary input shape.''' A response arrives as bytes and jsoniter reads bytes, so the pair of entry
  * points that take an `Array[Byte]` is the one the request pipeline uses; the `String` overloads exist for the callers
  * that genuinely start from text and pay a UTF-8 encoding to join the same path.
  *
  * '''Where a path comes from.''' Decoding is two steps: jsoniter parses the body into [[JsonValue]], then the DTO
  * assembles itself from that document. Only the first step can fail structurally, and when it does the problem is the
  * document as a whole, so the failure carries [[com.worxbend.codeberg4s.JsonPath.Root]]. A field the domain genuinely
  * needs is reported by the DTO's own `toDomain`, which names it — `$.owner.login`, `$[2].sha` — because the DTO knows
  * which field it wanted and the parser does not. That is a better division than the previous one, where a path was
  * reverse-engineered from a tracing visitor's rendered string.
  *
  * The failure carries no body text: the request pipeline owns the body and adds the bounded snippet when it lifts the
  * failure into [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]].
  */
object Json:

  /** Upper bound, in characters, on the reason text taken from a parser exception.
    *
    * jsoniter's messages embed a hex dump of the bytes around the failure, which is exactly the sort of thing that must
    * not become a payload dump in a log line.
    */
  val MaxReasonLength: Int = 200

  private val Ellipsis: String = "..."

  /** Parsing is configured to keep the message short and free of payload.
    *
    * `appendHexDumpToParseException` is off because a hex dump of a response body is payload, and this library's
    * failures are logged. The nesting limit is a second guard alongside [[JsonValue.MaxDepth]] — jsoniter enforces it
    * while reading rather than after.
    */
  private val Config: ReaderConfig =
    ReaderConfig
      .withAppendHexDumpToParseException(false)
      .withMaxBufSize(1 << 22)
      .withPreferredBufSize(1 << 14)

  /** Decodes a body into `A`, from the bytes that arrived.
    *
    * This is the shape the request pipeline uses. jsoniter — like every JSON parser worth using — reads bytes, so
    * handing it the bytes is the direct route; handing it a `String` makes it encode that `String` back into a `byte[]`
    * before it can start, which is a full copy of the payload for nothing.
    *
    * '''Never throws.''' Everything jsoniter can raise — a body that is not JSON, a truncated body, an empty body, a
    * document nested past [[JsonValue.MaxDepth]] — is caught and returned as a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]] whose `message` is the parser's own explanation trimmed to
    * [[MaxReasonLength]].
    *
    * @param body
    *   the raw response body as UTF-8 bytes, exactly as received
    */
  def decode[A](body: Array[Byte])(using decoder: JsonDecoder[A]): Either[DecodeFailure, A] =
    parse(body).flatMap(decoder.decode)

  /** Decodes a body given as text.
    *
    * Kept for the callers that genuinely hold a `String` and not bytes — the error-payload parser, which is handed
    * already-decoded text, and tests written against a literal. It encodes to UTF-8 and then parses, so prefer the
    * `Array[Byte]` overload wherever the bytes are still available.
    *
    * @param body
    *   the raw response body as text, exactly as received
    */
  def decode[A](body: String)(using decoder: JsonDecoder[A]): Either[DecodeFailure, A] =
    parse(body).flatMap(decoder.decode)

  /** The same decoding, as the [[com.worxbend.codeberg4s.core.Decode]] port core consumes.
    *
    * Use this to hand a DTO to a use case without core learning that jsoniter exists. Instances are stateless and safe
    * to share between threads.
    *
    * Reads [[com.worxbend.codeberg4s.core.ResponseBody.utf8Bytes]] rather than the raw bytes, because JSON is UTF-8 by
    * RFC 8259 §8.1 and jsoniter reads UTF-8 and nothing else. For every response Forgejo has ever sent, that is the
    * array the socket produced and no work happens at all; for a server that declared something else, it is a
    * transcoding, which is still right and merely slow.
    */
  def decoder[A](using JsonDecoder[A]): Decode[A] =
    (body: ResponseBody) => decode[A](body.utf8Bytes)

  /** Parses UTF-8 bytes into the document model, without interpreting it.
    *
    * The bare literal `null` is a successful parse producing [[JsonValue.Null]], not a `null` reference — which is the
    * whole reason this library models JSON rather than mapping it onto Scala types at the parser. Whether `null` is an
    * acceptable document is the decoder's question, and [[JsonDecoder.objectOf]] answers no.
    */
  def parse(body: Array[Byte]): Either[DecodeFailure, JsonValue] =
    read(readFromArray[JsonValue](body, Config)(using JsonValue.codec))

  /** [[parse]] for a body already held as text; it is encoded to UTF-8 and parsed. */
  def parse(body: String): Either[DecodeFailure, JsonValue] =
    read(readFromString[JsonValue](body, Config)(using JsonValue.codec))

  /** Renders a document to its compact wire form. The only place this library serialises JSON. */
  def render(value: JsonValue): String =
    writeToString(value)(using JsonValue.codec)

  /** The promise that no `JsonReaderException` escapes, made once for both entry points.
    *
    * `parsed` is by-name so that the parse happens inside the `try` rather than at the call site, which is the whole
    * point of routing both overloads through here.
    */
  private def read(parsed: => JsonValue): Either[DecodeFailure, JsonValue] =
    try Right(parsed)
    catch case NonFatal(error) => Left(DecodeFailure(JsonPath.Root, reasonOf(error)))

  private def reasonOf(error: Throwable): String =
    Option(error.getMessage).map(bound).getOrElse(error.getClass.getName)

  private def bound(message: String): String =
    val trimmed = message.trim.linesIterator.next()
    if trimmed.length <= MaxReasonLength then trimmed else s"${trimmed.take(MaxReasonLength)}$Ellipsis"
