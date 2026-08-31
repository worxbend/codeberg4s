package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.issues.{LabelId, MilestoneId, StateFilter}
import com.worxbend.codeberg4s.repositories.BranchName

/** The filters `GET /repos/{owner}/{repo}/pulls` accepts, as one value rather than as seven parameters.
  *
  * Built by starting from [[PullRequestQuery.Empty]] and naming what should change:
  *
  * {{{
  * PullRequestQuery.Empty
  *   .withState(StateFilter.All)
  *   .sortedBy(PullRequestSort.RecentUpdate)
  *   .withBase(mainBranch)
  * }}}
  *
  * '''Only what is set is sent.''' Every field here is absent by default and an absent field contributes no query
  * parameter at all, which matters because Forgejo's defaults are not this library's to guess: omitting `state` means
  * open pull requests only, and sending `state=` empty is not the same request. See
  * [[com.worxbend.codeberg4s.issues.StateFilter]], which this group reuses rather than forking — the endpoint takes the
  * same `open`/`closed`/`all` vocabulary as the issue listing.
  *
  * The builder methods exist because `.scalafix.conf` bans default arguments, and a seven-argument `copy` at every call
  * site would be worse than either. Each returns a new query; the type is immutable and safe to share.
  *
  * @param state
  *   which lifecycle states to include; absent means Forgejo's own default of open only. Note that `closed` here
  *   includes '''merged''' pull requests, because Forgejo's `state` does — see [[PullRequestState]]
  * @param sort
  *   the ordering to ask for; absent means the instance's default
  * @param milestone
  *   restrict to one milestone, by id. An id and not a title, unlike the issue listing's `milestones` parameter, which
  *   is Forgejo's inconsistency and not this library's
  * @param labels
  *   restrict to pull requests carrying '''all''' of these labels, by id. Sent as a repeated `labels` parameter rather
  *   than as one comma-joined value — the spec declares `collectionFormat: multi` here, where the issue listing
  *   declares a comma-joined string
  * @param poster
  *   restrict to pull requests opened by this login
  * @param base
  *   restrict to pull requests merging into this branch
  * @param head
  *   restrict to pull requests merging from this branch; see [[PullRequestHead]] for the `owner:branch` form
  */
final case class PullRequestQuery(
    state: Option[StateFilter],
    sort: Option[PullRequestSort],
    milestone: Option[MilestoneId],
    labels: Vector[LabelId],
    poster: Option[String],
    base: Option[BranchName],
    head: Option[PullRequestHead],
):

  /** Restricts the listing to `filter`. */
  def withState(filter: StateFilter): PullRequestQuery = copy(state = Some(filter))

  /** Asks for `ordering` instead of the instance's default. */
  def sortedBy(ordering: PullRequestSort): PullRequestQuery = copy(sort = Some(ordering))

  /** Restricts to pull requests in the milestone `id`. */
  def inMilestone(id: MilestoneId): PullRequestQuery = copy(milestone = Some(id))

  /** Requires every one of `ids`; an empty vector removes the filter. */
  def withLabels(ids: Vector[LabelId]): PullRequestQuery = copy(labels = ids)

  /** Restricts to pull requests opened by `login`. */
  def authoredBy(login: String): PullRequestQuery = copy(poster = Some(login))

  /** Restricts to pull requests merging into `branch`. */
  def withBase(branch: BranchName): PullRequestQuery = copy(base = Some(branch))

  /** Restricts to pull requests merging from `branch`. */
  def withHead(branch: PullRequestHead): PullRequestQuery = copy(head = Some(branch))

object PullRequestQuery:

  /** No filters at all — the listing Forgejo serves by default, which is the repository's open pull requests.
    *
    * The starting point for every query: there is no zero-argument constructor, because a query with defaults would
    * hide which of them are this library's and which are the instance's.
    */
  val Empty: PullRequestQuery =
    PullRequestQuery(
      state     = None,
      sort      = None,
      milestone = None,
      labels    = Vector.empty,
      poster    = None,
      base      = None,
      head      = None,
    )
