package com.worxbend.codeberg4s.core

import scala.concurrent.duration.FiniteDuration

/** A response whose body is bytes rather than text.
  *
  * A few endpoints answer a ZIP rather than text: an Actions artifact and a workflow run's logs. This is what
  * [[BinaryHttpPort]] hands back for those.
  *
  * '''It is now a near-duplicate of [[CodebergResponse]].''' It exists because [[CodebergResponse]] used to carry a
  * `String`, which would have destroyed a ZIP before any [[Decode]] could see it. Now that [[CodebergResponse]] carries
  * a [[ResponseBody]] the two types say the same thing, and the download endpoints could be served by the ordinary
  * pipeline. Merging them changes the published signature of the download API, so it is a change of its own rather than
  * a rider on the one that made it possible.
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
    CodebergResponse(status, headers, ResponseBody.Empty).retryAfter

  /** The request id the instance echoed, when it echoed one. */
  def requestId: Option[String] =
    CodebergResponse(status, headers, ResponseBody.Empty).requestId

  /** How large the body is. Cheaper to read than to render, and the thing worth logging. */
  def size: Int = bytes.length

  /** Structural, on the status, the headers and then the bytes.
    *
    * Written out because the array's own `equals` in Scala is '''identity''': the equality a case class generates would
    * compare [[bytes]] by reference, so two responses carrying byte-identical archives would compare unequal and hash
    * differently. That is a wrong answer with no warning attached — in an assertion, or in a `Set` — which is why the
    * bytes are compared with `java.util.Arrays.equals` here. The status and the headers are compared first because they
    * are the cheap half; the archive may be megabytes.
    *
    * The class is `final`, so no subclass can exist and the type test below is the whole of the compiler-generated
    * `canEqual`; calling `canEqual` as well would add nothing. Removing `final` would change that.
    */
  override def equals(other: Any): Boolean =
    other match
      case that: BinaryResponse =>
        status.equals(that.status) && headers.equals(that.headers) && java.util.Arrays.equals(bytes, that.bytes)
      case _                    => false

  override def hashCode(): Int =
    31 * (31 * status + headers.hashCode) + java.util.Arrays.hashCode(bytes)

  /** Deliberately does not render the body: a ZIP in a log line helps nobody. */
  override def toString: String = s"BinaryResponse(status=$status, size=$size)"
