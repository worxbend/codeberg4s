package com.worxbend.codeberg4s.repositories.actions

import java.util.Locale

/** Where a run, a job or a runner task is in its lifecycle.
  *
  * '''A closed set, and the spec says which one.''' Unlike most Forgejo string fields, this one is enumerated:
  * `spec/swagger.v1.json` declares `enum: [unknown, waiting, running, success, failure, cancelled, skipped, blocked]`
  * on the `status` filter of both `GET /repos/{owner}/{repo}/actions/runs` and
  * `GET /repos/{owner}/{repo}/actions/tasks`. The `status` '''field''' of `ActionRun`, `ActionRunJob` and `ActionTask`
  * is declared as a bare `type: string`, but it is the same value out of the same Go type, so the same eight cases
  * describe it.
  *
  * Forgejo merges what GitHub splits into a status and a conclusion: `running` is a status, `success` is a conclusion,
  * and both arrive in this one field. There is therefore no separate conclusion type in this library, and
  * [[ActionStatus.isFinished]] is how a caller asks the question a conclusion would answer.
  *
  * Note that `unknown` is a real value the server sends, not this library's fallback for an unrecognised one — see
  * [[ActionStatus.parse]].
  */
enum ActionStatus:

  /** The instance has no status for this item. A real wire value, not a decoding artefact. */
  case Unknown

  /** Queued, waiting for a runner to pick it up. */
  case Waiting

  /** Being executed by a runner right now. */
  case Running

  /** Finished, and everything passed. */
  case Success

  /** Finished, and something failed. */
  case Failure

  /** Stopped before finishing, either by a caller or by the instance. */
  case Cancelled

  /** Not executed, because a condition on it was not met. */
  case Skipped

  /** Held back — a fork pull request awaiting approval, or a job whose `needs` have not completed. */
  case Blocked

  /** Whether the item has reached a state it will not leave.
    *
    * [[ActionStatus.Unknown]] counts as '''not''' finished: a status the instance cannot name is no evidence that the
    * work stopped, and a caller polling until completion must keep polling rather than conclude the run is over.
    */
  def isFinished: Boolean =
    this match
      case Success | Failure | Cancelled | Skipped => true
      case Unknown | Waiting | Running | Blocked   => false

object ActionStatus:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything outside the enumerated set rather than failing, for the reason
    * [[com.worxbend.codeberg4s.repositories.CommitFileStatus.parse]] gives: a status a later Forgejo release adds must
    * not cost the caller the whole run. That is a different answer from [[ActionStatus.Unknown]], which is the value
    * the server sends when '''it''' cannot name the status.
    *
    * Matching is case-insensitive and trims, because nothing but the spec guarantees the casing.
    */
  def parse(value: String): Option[ActionStatus] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "unknown"   => Some(Unknown)
      case "waiting"   => Some(Waiting)
      case "running"   => Some(Running)
      case "success"   => Some(Success)
      case "failure"   => Some(Failure)
      case "cancelled" => Some(Cancelled)
      case "skipped"   => Some(Skipped)
      case "blocked"   => Some(Blocked)
      case _           => None

  extension (status: ActionStatus)

    /** The lowercase spelling Forgejo uses on the wire, and the one the `status` filter takes. */
    def wireValue: String =
      status match
        case Unknown   => "unknown"
        case Waiting   => "waiting"
        case Running   => "running"
        case Success   => "success"
        case Failure   => "failure"
        case Cancelled => "cancelled"
        case Skipped   => "skipped"
        case Blocked   => "blocked"
