package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import java.time.Instant
import java.util.Locale

/** Where an [[Issue]] or a [[Milestone]] is in its open/closed lifecycle, with the closing instant attached to the
  * state that has one.
  *
  * Forgejo sends this as two independent fields — `state`, a string, and `closed_at`, a nullable timestamp — which is
  * exactly the shape `SCALA_CODE_STYLE.md` forbids a domain model to keep: `state: String` beside
  * `closedAt: Option[Instant]` lets a caller build an issue that is open '''and''' closed, or closed and never closed,
  * and forces every reader to remember which combinations the server actually produces. Here the closing instant lives
  * on [[LifecycleState.Closed]] and nowhere else, so an open issue cannot carry one.
  *
  * The two fields do agree on the wire — across the thirteen issues and three milestones in the golden fixtures, every
  * `"closed"` carries a `closed_at` and every `"open"` sends `closed_at: null` — but `closedAt` stays an `Option`
  * inside [[LifecycleState.Closed]] rather than becoming mandatory. Migrated and imported issues are closed without the
  * event that closed them ever having been recorded, and losing a whole issue to a missing timestamp would be a poor
  * trade for an invariant the API does not itself guarantee.
  */
enum LifecycleState:

  /** Still open. Carries no timestamp, because there is nothing to timestamp. */
  case Open

  /** Closed, together with when — absent when the instance did not record it. */
  case Closed(closedAt: Option[Instant])

  /** Whether this is [[LifecycleState.Open]]. */
  def isOpen: Boolean =
    this match
      case Open      => true
      case Closed(_) => false

  /** Whether this is [[LifecycleState.Closed]], whatever it knows about when. */
  def isClosed: Boolean = !isOpen

object LifecycleState:

  /** The value Forgejo's `StateType` uses for an open item. */
  val OpenWire: String = "open"

  /** The value Forgejo's `StateType` uses for a closed item. */
  val ClosedWire: String = "closed"

  /** Reassembles the state from the two fields the wire splits it into.
    *
    * `closedAt` is dropped for an open item rather than being carried along, which is the whole point of the type: an
    * instance that sends `state: "open"` beside a stale `closed_at` cannot smuggle the contradiction into the domain.
    *
    * @param state
    *   the raw `state` field, matched case-insensitively after trimming
    * @param closedAt
    *   the already-parsed `closed_at`, used only when `state` says the item is closed
    * @return
    *   the state, or a [[ValidationError]] on the `"state"` field when `state` is neither spelling
    */
  def from(state: String, closedAt: Option[Instant]): Either[ValidationError, LifecycleState] =
    state.trim.toLowerCase(Locale.ROOT) match
      case OpenWire   => Right(Open)
      case ClosedWire => Right(Closed(closedAt))
      case other      => Left(ValidationError("state", s"must be '$OpenWire' or '$ClosedWire', not '$other'"))
