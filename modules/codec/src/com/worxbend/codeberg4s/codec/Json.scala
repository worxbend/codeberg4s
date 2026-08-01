package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.DecodeFailure

import upickle.core.TraceVisitor

import scala.util.Try
import scala.util.matching.Regex

/** The single door between a response body and a wire DTO.
  *
  * Nothing else in this library calls upickle's `read`. Keeping the call in one place is what lets the module promise
  * that a decoding failure is always a [[com.worxbend.codeberg4s.core.DecodeFailure]] value and never an escaping
  * `upickle.core.Abort`, `upickle.core.AbortException` or `ujson.ParsingFailedException` — the promise ADR-0003 makes
  * and `SCALA_CODE_STYLE.md` restates as "malformed and unexpected JSON produces a failure value, never an exception".
  *
  * The failure carries a [[com.worxbend.codeberg4s.JsonPath]] recovered from upickle's tracing visitor, so a bug report
  * says `$.owner.login` or `$[2]` rather than "decoding failed". It carries no body text: the request pipeline owns the
  * body and adds the bounded snippet when it lifts the failure into
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]].
  */
object Json:

  /** Upper bound, in characters, on the reason text taken from a codec exception.
    *
    * upickle's own messages are short, but `missing keys in dictionary: …` grows with the number of absent fields, and
    * a decoder message must never become a payload dump.
    */
  val MaxReasonLength: Int = 200

  private val Ellipsis: String = "..."

  /** Upper bound on how far the cause chain is walked when looking for a message. Guards against a self-referential
    * `getCause`, which the JVM permits.
    */
  private val MaxCauseDepth: Int = 8

  /** One `['key']` or `[3]` step of upickle's JSON-path rendering. See `upickle.core.TraceVisitor.HasPath#path`: object
    * keys are single-quoted with `'` escaped as `\'`, array positions are bare digits.
    */
  private val PathComponent: Regex = """\[(?:'((?:\\'|[^'])*)'|(\d+))]""".r

  /** Decodes a body into `A`.
    *
    * '''Never throws.''' Every failure upickle can raise — a body that is not JSON, a truncated body, a body whose
    * shape does not match the reader, an empty body — is caught and returned as a
    * [[com.worxbend.codeberg4s.core.DecodeFailure]]. The failure's `path` is where upickle stopped, or
    * [[com.worxbend.codeberg4s.JsonPath.Root]] when the problem is the document as a whole; its `message` is upickle's
    * own explanation, trimmed to [[MaxReasonLength]].
    *
    * Tracing is requested explicitly rather than left to upickle's default so that the path stays available if that
    * default ever changes; it costs roughly 10% of parse time, which is worth it for a library whose failures are
    * reported by third parties.
    *
    * @param body
    *   the raw response body, exactly as received
    */
  def decode[A: upickle.default.Reader](body: String): Either[DecodeFailure, A] =
    Try(upickle.default.read[A](body, trace = true)).toEither.left
      .map(asFailure)
      .flatMap(rejectNull)

  /** The same decoding, as the [[com.worxbend.codeberg4s.core.Decode]] port core consumes.
    *
    * Use this to hand a DTO to a use case without core learning that upickle exists. Instances are stateless and safe
    * to share between threads.
    */
  def decoder[A: upickle.default.Reader]: Decode[A] =
    (body: String) => decode[A](body)

  /** Turns a `null` result into a failure.
    *
    * Measured against upickle 4.4.3: a body that is the bare literal `null` does '''not''' reach a reader's mapping
    * function. Most readers inherit a `visitNull` that answers `null.asInstanceOf[A]`, so `read` returns successfully
    * with a `null` reference — which would then travel into the domain and surface as a `NullPointerException` far from
    * the response that caused it. A `null` body is not a decoded value, and it is rejected here.
    *
    * Types that genuinely model absence are unaffected, because they handle `visitNull` themselves: `Option` answers
    * `None` and `ujson.Value` answers `ujson.Null`, neither of which is a `null` reference.
    */
  private def rejectNull[A](value: A): Either[DecodeFailure, A] =
    Option(value).toRight(DecodeFailure(JsonPath.Root, "the body was the JSON literal null"))

  private def asFailure(error: Throwable): DecodeFailure =
    error match
      case trace: TraceVisitor.TraceException =>
        DecodeFailure(pathOf(trace.jsonPath), reasonOf(Option(trace.getCause).getOrElse(trace)))
      case other                              =>
        DecodeFailure(JsonPath.Root, reasonOf(other))

  private def pathOf(rendered: String): JsonPath =
    PathComponent
      .findAllMatchIn(rendered)
      .foldLeft(JsonPath.Root): (path, component) =>
        Option(component.group(1)) match
          case Some(key) => path.field(key.replace("\\'", "'"))
          case None      => component.group(2).toIntOption.fold(path)(path.index)

  /** The first genuine explanation in the cause chain.
    *
    * A nested `TraceException` is skipped rather than reported: its message is a JSON path, which belongs in the
    * failure's `path`, not in its `message`. Nesting happens when a reader decodes an embedded value with its own parse
    * — see `SearchEnvelopeDto`.
    */
  private def reasonOf(error: Throwable): String =
    causes(error)
      .filterNot(isTrace)
      .flatMap(cause => Option(cause.getMessage))
      .find(_.trim.nonEmpty)
      .map(bound)
      .getOrElse(error.getClass.getName)

  private def isTrace(error: Throwable): Boolean =
    error match
      case _: TraceVisitor.TraceException => true
      case _                              => false

  private def causes(error: Throwable): LazyList[Throwable] =
    LazyList
      .unfold(Option(error))(current => current.map(cause => (cause, Option(cause.getCause))))
      .take(MaxCauseDepth)

  private def bound(message: String): String =
    val trimmed = message.trim
    if trimmed.length <= MaxReasonLength then trimmed else s"${trimmed.take(MaxReasonLength)}$Ellipsis"
