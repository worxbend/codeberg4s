package com.worxbend.codeberg4s.core

/** The port for the handful of endpoints that answer bytes rather than text.
  *
  * Kept separate from [[HttpPort]] on purpose. Almost every operation in this library wants a decoded `String`, and
  * folding a byte channel into [[HttpPort]] would either force every existing implementation — including the fakes that
  * make core testable without a network — to grow a method none of them need, or invite a default implementation that
  * silently fails. A second, narrow trait keeps both honest: an adapter that can serve bytes says so by implementing
  * this, and one that cannot simply does not.
  *
  * The failure contract matches [[HttpPort]]: any HTTP status, including `5xx`, arrives as a `Right`; a `Left` means no
  * complete response arrived. The one difference is which bound applies to the body — these operations fetch archives,
  * so an adapter applies [[com.worxbend.codeberg4s.CodebergConfig.maxDownloadBodyBytes]] here rather than the smaller
  * [[com.worxbend.codeberg4s.CodebergConfig.maxResponseBodyBytes]].
  *
  * @tparam F
  *   the effect the client runs in
  */
trait BinaryHttpPort[F[_]]:

  /** Sends `request` and returns the response body as bytes.
    *
    * @param request
    *   what to send; its body is built the same way as for a textual request
    * @param redactedUri
    *   the safe rendering of the target, for an adapter that reports what it dialled
    */
  def sendBinary(request: CodebergRequest, redactedUri: String): F[Either[TransportFailure, BinaryResponse]]
