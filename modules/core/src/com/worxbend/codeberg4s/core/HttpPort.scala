package com.worxbend.codeberg4s.core

/** The port every transport adapter implements — the single hole through which this library reaches the network.
  *
  * '''Failure contract.''' A `Left` means no complete response was produced: DNS, TLS, connection, timeout, or a body
  * that passed [[com.worxbend.codeberg4s.CodebergConfig.maxResponseBodyBytes]] and was abandoned part-read. Every HTTP
  * status, including `4xx` and `5xx`, arrives as a `Right`; deciding what a status means belongs to [[StatusMapping]],
  * not to the adapter. An implementation must therefore not throw and must not translate a status into a failure.
  *
  * '''Security contract.''' `redactedUri` is what gets recorded in a [[com.worxbend.codeberg4s.CallContext]], so an
  * implementation must log or report that value and never the URI it actually dialled — see [[Redaction.uri]]. Adding
  * the `Authorization` header is the adapter's job and its only credential-handling responsibility.
  *
  * @tparam F
  *   the effect the client runs in
  */
trait HttpPort[F[_]]:

  /** Sends `request` and returns the response, or the classified reason no response arrived.
    *
    * @param request
    *   the call to make, with unencoded path segments and no credentials
    * @param redactedUri
    *   the URI as it may be shown to a human, already percent-encoded and stripped of credential material
    */
  def send(request: CodebergRequest, redactedUri: String): F[Either[TransportFailure, CodebergResponse]]
