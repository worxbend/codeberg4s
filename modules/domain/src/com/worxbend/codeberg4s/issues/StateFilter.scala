package com.worxbend.codeberg4s.issues

/** Which lifecycle states a listing should return.
  *
  * A named case rather than a Boolean, per `SCALA_CODE_STYLE.md`: `list(slug, includeClosed = true)` makes every caller
  * and every reviewer reconstruct what `true` meant, and it cannot express "open only" and "everything" as the distinct
  * requests they are.
  *
  * Leaving the filter unset is a fourth thing again, and not a synonym for [[StateFilter.All]] — Forgejo defaults
  * `state` to `open` on both `GET /repos/{owner}/{repo}/issues` and `GET /repos/{owner}/{repo}/milestones`, so a caller
  * who wants closed items has to say so. That is why [[IssueQuery.state]] is an `Option` of this type rather than this
  * type with a default.
  */
enum StateFilter:

  /** Only items that are still open — what Forgejo returns when `state` is omitted. */
  case Open

  /** Only items that have been closed. */
  case Closed

  /** Open and closed alike. */
  case All

  /** The value to put in the `state` query parameter. */
  def wireValue: String =
    this match
      case Open   => "open"
      case Closed => "closed"
      case All    => "all"
