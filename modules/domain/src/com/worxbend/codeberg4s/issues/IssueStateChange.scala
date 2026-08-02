package com.worxbend.codeberg4s.issues

/** The lifecycle transition an [[EditIssue]] asks for.
  *
  * Deliberately not [[LifecycleState]] and not [[StateFilter]], though all three are about open and closed. A caller
  * editing an issue can only ask for a transition, never for a state: they cannot supply the `closed_at` that
  * [[LifecycleState.Closed]] carries — the instance decides it — and [[StateFilter.All]] means nothing as an
  * instruction. Giving the transition its own two-case type is what stops either of those from being expressible.
  *
  * [[CreateMilestone]] and [[EditMilestone]] send this same type. Forgejo's `CreateMilestoneOption.state` declares the
  * identical `["open", "closed"]` enum and means the identical thing, so the concept is shared rather than duplicated
  * under a second name; only the model it is attached to differs.
  */
enum IssueStateChange:

  /** Reopen a closed issue. Sends `state: "open"`. */
  case Reopen

  /** Close an open issue. Sends `state: "closed"`; the instance stamps `closed_at` itself. */
  case Close

  /** The value to put in `EditIssueOption.state`. */
  def wireValue: String =
    this match
      case Reopen => LifecycleState.OpenWire
      case Close  => LifecycleState.ClosedWire
