package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure

import com.github.plokhotnyuk.jsoniter_scala.core.ReaderConfig
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString

import scala.util.control.NonFatal

/** The single door between a response body and a wire DTO.
  *
  * Nothing else in this library calls jsoniter's `readFromString`. Keeping the call in one place is what lets the
  * module promise that a decoding failure is always a [[com.worxbend.codeberg4s.core.DecodeFailure]] value and never an
  * escaping `JsonReaderException` — the promise ADR-0003 makes and `SCALA_CODE_STYLE.md` restates as "malformed and
  * unexpected JSON produces a failure value, never an exception".
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

  /** Decodes a body into `A`.
    *
    * '''Never throws.''' Everything jsoniter can raise — a body that is not JSON, a truncated body, an empty body, a
    * document nested past [[JsonValue.MaxDepth]] — is caught and returned as a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]] whose `message` is the parser's own explanation trimmed to
    * [[MaxReasonLength]].
    *
    * @param body
    *   the raw response body, exactly as received
    */
  def decode[A](body: String)(using decoder: JsonDecoder[A]): Either[DecodeFailure, A] =
    parse(body).flatMap(decoder.decode)

  /** The same decoding, as the [[com.worxbend.codeberg4s.core.Decode]] port core consumes.
    *
    * Use this to hand a DTO to a use case without core learning that jsoniter exists. Instances are stateless and safe
    * to share between threads.
    */
  def decoder[A](using JsonDecoder[A]): Decode[A] =
    (body: String) => decode[A](body)

  /** Parses a body into the document model, without interpreting it.
    *
    * The bare literal `null` is a successful parse producing [[JsonValue.Null]], not a `null` reference — which is the
    * whole reason this library models JSON rather than mapping it onto Scala types at the parser. Whether `null` is an
    * acceptable document is the decoder's question, and [[JsonDecoder.objectOf]] answers no.
    */
  def parse(body: String): Either[DecodeFailure, JsonValue] =
    try Right(readFromString[JsonValue](body, Config)(using JsonValue.codec))
    catch case NonFatal(error) => Left(DecodeFailure(JsonPath.Root, reasonOf(error)))

  /** Renders a document to its compact wire form. The only place this library serialises JSON. */
  def render(value: JsonValue): String =
    writeToString(value)(using JsonValue.codec)

  private def reasonOf(error: Throwable): String =
    Option(error.getMessage).map(bound).getOrElse(error.getClass.getName)

  private def bound(message: String): String =
    val trimmed = message.trim.linesIterator.next()
    if trimmed.length <= MaxReasonLength then trimmed else s"${trimmed.take(MaxReasonLength)}$Ellipsis"
