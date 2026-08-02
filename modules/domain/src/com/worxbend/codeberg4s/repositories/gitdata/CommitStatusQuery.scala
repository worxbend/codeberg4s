package com.worxbend.codeberg4s.repositories.gitdata

/** The filters of `GET /repos/{owner}/{repo}/commits/{ref}/statuses`.
  *
  * Both members are optional and both are omitted from the request when unset, because an absent parameter and an empty
  * one are different requests: omitting `state` lists every status, and `state=` is a value Forgejo has to reject.
  *
  * Built by starting from [[CommitStatusQuery.Empty]] and narrowing — `CommitStatusQuery.Empty.sortedBy(...)` — so a
  * caller never has to name a filter they do not care about, which is what a default argument would have forced and
  * what `SCALA_CODE_STYLE.md` bans.
  *
  * @param sort
  *   the ordering to ask for, absent for Forgejo's own default of newest first
  * @param state
  *   list only statuses in this state
  */
final case class CommitStatusQuery(sort: Option[CommitStatusSort], state: Option[CommitStatusState]):

  /** This query, ordered by `ordering`. */
  def sortedBy(ordering: CommitStatusSort): CommitStatusQuery = copy(sort = Some(ordering))

  /** This query, narrowed to one state.
    *
    * '''[[CommitStatusState.Skipped]] is not among the values this parameter declares.''' The spec's enum for the
    * `state` filter lists `pending`, `success`, `error`, `failure` and `warning` — five of the six words the state
    * field itself can hold — so asking for skipped statuses is a request the instance is entitled to reject with a
    * `400`. It is accepted here rather than made unrepresentable because the omission looks like a spec oversight, and
    * a library that silently dropped the filter would be worse than one that lets the instance answer.
    */
  def inState(wanted: CommitStatusState): CommitStatusQuery = copy(state = Some(wanted))

object CommitStatusQuery:

  /** No filters: every status on the commit, in Forgejo's default order. */
  val Empty: CommitStatusQuery = CommitStatusQuery(None, None)
