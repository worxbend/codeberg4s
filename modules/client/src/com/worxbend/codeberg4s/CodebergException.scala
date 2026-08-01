package com.worxbend.codeberg4s

import scala.util.control.NoStackTrace

/** The bridge that carries a [[CodebergError]] through `Future`'s failure channel.
  *
  * The published API has two rails (ADR-0005). The typed rail hands back `Either[CodebergError, A]`; the convenience
  * rail hands back `Future[A]` and fails it with this exception. Both carry exactly the same value, so nothing is lost
  * by choosing the idiomatic-for-`Future` rail:
  *
  * {{{
  * client.repos.get(owner, name).recover:
  *   case CodebergException(CodebergError.Api(_, 404, _)) => fallbackRepository
  * }}}
  *
  * '''Security contract.''' The message is [[CodebergError.describe]], which is built only from the redacted
  * [[CallContext]] and from server-supplied text. A token or password therefore cannot reach `getMessage`, a stack
  * trace, or any logger that prints an uncaught failure — which is why the exception is constructed from `describe`
  * rather than from the raw ADT rendering.
  *
  * '''No stack trace.''' The exception is a value carrier, not a defect report: it is created at the boundary of a
  * pipeline the caller never appears in, so its stack trace would name this library's internals and nothing the caller
  * could act on. [[scala.util.control.NoStackTrace]] also makes the failure path allocation-cheap, which matters for a
  * client that classifies a `404` as an ordinary answer. The information a bug report needs — operation, redacted URI,
  * status, JSON path — is in [[error]].
  *
  * @param error
  *   the failure, in full; pattern match on it rather than parsing the message
  */
final case class CodebergException(error: CodebergError) extends RuntimeException(error.describe) with NoStackTrace
