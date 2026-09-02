package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.WireVocabulary

/** The verdict one CI check reported for a commit.
  *
  * Forgejo's `CommitStatusState`, whose own description names six values: `pending`, `success`, `error`, `failure`,
  * `warning` and `skipped`. The spec types the field as a bare string with no enum, so the list comes from that
  * description rather than from a machine-readable constraint — [[parse]] is lenient for that reason.
  *
  * The distinction between [[Error]] and [[Failure]] is the one that matters when acting on a status: a failure is the
  * check saying no, an error is the check itself breaking.
  */
enum CommitStatusState(val wireName: String) extends WireVocabulary:

  /** The check has not finished. */
  case Pending extends CommitStatusState("pending")

  /** The check passed. */
  case Success extends CommitStatusState("success")

  /** The check could not run to a conclusion. */
  case Error extends CommitStatusState("error")

  /** The check ran and reported a negative result. */
  case Failure extends CommitStatusState("failure")

  /** The check passed with reservations. */
  case Warning extends CommitStatusState("warning")

  /** The check did not apply and was not run. */
  case Skipped extends CommitStatusState("skipped")

object CommitStatusState:

  /** Parses Forgejo's spelling of a status state.
    *
    * Answers `None` for anything unrecognised. The wire field is an unconstrained string, so a future Forgejo — or a
    * third-party integration writing statuses through the API — can put a seventh word here, and losing one commit's
    * status word must not cost the caller the rest of the listing.
    */
  def parse(value: String): Option[CommitStatusState] =
    WireVocabulary.parse(values, value)
