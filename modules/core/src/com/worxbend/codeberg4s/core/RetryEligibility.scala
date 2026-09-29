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
    * Choosing this asserts that repeating the call is harmless: that *n* attempts leave the instance in exactly the
    * state one would have. The library chooses it on the caller's behalf for a fixed set of operations, and each call
    * site that does so says why in its own Scaladoc:
    *
    *   - a `DELETE` addressed by a server-issued id — an issue comment, a webhook, a deploy key, a label, a milestone,
    *     a team. The instance never reuses such an id, so a repeat can only find the resource already gone;
    *   - a `PUT` or `PATCH` that replaces whole state or sets a named resource to a stated value — a webhook edit, the
    *     whole set of a repository's topics or flags, a secret's value, a star, a watch, a follow;
    *   - a bodyless mark-read call on the notifications group, where a repeat is a no-op;
    *   - a rendering endpoint (`POST /markdown`) that stores nothing.
    *
    * '''The consequence a caller must expect.''' Retrying a mutation whose first attempt succeeded but whose response
    * was lost — a `503` from a gateway after the instance had already acted — means the repeat is answered for a state
    * that has already changed: a repeated `DELETE` earns a `404`, a repeated `PUT` a `200` for work already done. A
    * caller who treats that `404` as "the resource never existed" will draw the wrong conclusion; on these operations
    * it can equally mean "the first attempt did it". No `POST` that creates something, and no `PATCH` that edits by
    * applying a delta, is ever placed here by the library — repeating those would create or apply twice.
    */
  case AlwaysRetry

  /** Whether this eligibility permits repeating a call made with `method`. */
  def allows(method: HttpMethod): Boolean =
    this match
      case Never          => false
      case IdempotentOnly => method.isSafe
      case AlwaysRetry    => true
