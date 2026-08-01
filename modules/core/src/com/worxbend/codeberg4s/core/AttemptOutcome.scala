package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CodebergError

import scala.concurrent.duration.FiniteDuration

/** What one attempt produced, together with anything the server said about when to come back.
  *
  * The backoff hint travels beside the result rather than inside [[com.worxbend.codeberg4s.CodebergError]] because it
  * is not part of the failure a caller finally sees: once the retry engine has honoured or discarded it, it has no
  * further meaning, and putting it in the error ADT would force every caller to reason about a field that is almost
  * always empty.
  *
  * @param result
  *   the value the attempt produced, or why it failed
  * @param retryAfter
  *   the server's `Retry-After`, when it sent one; see [[CodebergResponse.retryAfter]]
  */
final case class AttemptOutcome[A](result: Either[CodebergError, A], retryAfter: Option[FiniteDuration])

object AttemptOutcome:

  /** An attempt that produced `value` and no backoff hint. */
  def succeeded[A](value: A): AttemptOutcome[A] = AttemptOutcome(Right(value), None)

  /** An attempt that failed with no backoff hint. */
  def failed[A](error: CodebergError): AttemptOutcome[A] = AttemptOutcome(Left(error), None)
