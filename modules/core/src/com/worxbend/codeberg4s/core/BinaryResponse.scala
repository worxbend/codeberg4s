package com.worxbend.codeberg4s.core

import scala.concurrent.duration.FiniteDuration

/** A response whose body is bytes rather than text.
  *
  * [[CodebergResponse]] carries a `String`, which is right for the JSON and `text/plain` endpoints that make up almost
  * all of this API — but a few answer a ZIP: an Actions artifact and a workflow run's logs. Decoding those bytes as
  * UTF-8 destroys them before any [[Decode]] could see them, so they need their own response type rather than a lossy
  * reuse of the textual one.
  *
  * Headers are lowercased by the transport, exactly as they are on [[CodebergResponse]].
  *
  * @param status
  *   the HTTP status
  * @param headers
  *   response headers, keys already lowercased
  * @param bytes
  *   the body verbatim
  */
final case class BinaryResponse(
    status: Int,
    headers: Map[String, List[String]],
    bytes: Array[Byte],
):

  /** The first value of `name`, which the transport has already lowercased. */
  def header(name: String): Option[String] =
    headers.get(name.toLowerCase(java.util.Locale.ROOT)).flatMap(_.headOption)

  /** The declared content type, when the server sent one. */
  def contentType: Option[String] = header("content-type")

  /** The `Retry-After` delay, parsed defensively — a missing or malformed header is `None`, never a failure. */
  def retryAfter: Option[FiniteDuration] =
    CodebergResponse(status, headers, "").retryAfter

  /** The request id the instance echoed, when it echoed one. */
  def requestId: Option[String] =
    CodebergResponse(status, headers, "").requestId

  /** How large the body is. Cheaper to read than to render, and the thing worth logging. */
  def size: Int = bytes.length

  /** Deliberately does not render the body: a ZIP in a log line helps nobody. */
  override def toString: String = s"BinaryResponse(status=$status, size=$size)"
