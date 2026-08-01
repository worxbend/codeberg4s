package com.worxbend.codeberg4s.pulls

import java.util.Locale

/** What a [[Review]] said.
  *
  * Forgejo's `ReviewStateType`, and the one enum in this group whose wire values are '''upper''' case with underscores
  * — `"APPROVED"`, `"REQUEST_REVIEW"` — rather than the lowercase spellings the rest of the API uses.
  * `golden/pull/reviews-list.json` shows two of them on one pull request; the others are read from Forgejo's own
  * `ReviewStateType` constants rather than measured, which is exactly why [[parse]] answers `None` on an unrecognised
  * value instead of failing.
  *
  * Note that a review request is itself a review here: asking someone to review produces a [[RequestReview]] row on the
  * reviews endpoint, so a caller counting approvals must filter rather than count.
  */
enum ReviewState:

  /** The reviewer approved the pull request. */
  case Approved

  /** The reviewer asked for changes; on a protected branch this blocks the merge. */
  case RequestChanges

  /** The reviewer left remarks without approving or blocking. */
  case Comment

  /** A review was '''requested''' from an account or a team; nobody has reviewed yet. */
  case RequestReview

  /** The review exists but has not been submitted — the reviewer's own unsent draft. */
  case Pending

  /** The value Forgejo puts in `PullReview.state`. */
  def wireValue: String =
    this match
      case Approved       => "APPROVED"
      case RequestChanges => "REQUEST_CHANGES"
      case Comment        => "COMMENT"
      case RequestReview  => "REQUEST_REVIEW"
      case Pending        => "PENDING"

object ReviewState:

  /** Parses Forgejo's spelling.
    *
    * Answers `None` for anything unrecognised rather than failing, for the same reason
    * [[com.worxbend.codeberg4s.repositories.CommitFileStatus.parse]] does: a state this library has not seen must not
    * cost the caller the whole page of reviews. Forgejo also sends `""` for a review with no state at all, which lands
    * in the same place. Matching is case-insensitive because nothing guarantees the casing but observation.
    */
  def parse(value: String): Option[ReviewState] =
    value.trim.toUpperCase(Locale.ROOT) match
      case "APPROVED"        => Some(Approved)
      case "REQUEST_CHANGES" => Some(RequestChanges)
      case "COMMENT"         => Some(Comment)
      case "REQUEST_REVIEW"  => Some(RequestReview)
      case "PENDING"         => Some(Pending)
      case _                 => None
