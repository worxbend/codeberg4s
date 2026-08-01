package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.HttpMethod

/** Whether a call may be attempted again at all.
  *
  * This is separate from "is this failure worth retrying": a `503` is always worth retrying, but repeating a `POST`
  * that created an issue produces a second issue. Forgejo offers no idempotency key, so the decision cannot be inferred
  * from the response and has to be stated by the caller — as a named case rather than a Boolean nobody can read at a
  * call site.
  */
enum RetryEligibility:

  /** Never attempt the call again, whatever failed. */
  case Never

  /** Attempt again only when the method is safe in the RFC 9110 sense, that is `GET` or `HEAD`. The default. */
  case IdempotentOnly

  /** Attempt again whenever the failure is retryable, even for a mutating method.
    *
    * Choosing this asserts that repeating the call is harmless — a `PUT` that sets a label, a `DELETE` that is already
    * idempotent server-side. It is never chosen by this library on a caller's behalf.
    */
  case AlwaysRetry

  /** Whether this eligibility permits repeating a call made with `method`. */
  def allows(method: HttpMethod): Boolean =
    this match
      case Never          => false
      case IdempotentOnly => method.isSafe
      case AlwaysRetry    => true
