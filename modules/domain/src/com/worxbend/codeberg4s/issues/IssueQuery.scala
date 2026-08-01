package com.worxbend.codeberg4s.issues

import java.time.Instant

/** The filters `GET /repos/{owner}/{repo}/issues` accepts, as one value rather than as eight parameters.
  *
  * Built by starting from [[IssueQuery.Empty]] and naming what should change:
  *
  * {{{
  * IssueQuery.Empty
  *   .withState(StateFilter.All)
  *   .withLabels(Vector(bug))
  *   .updatedSince(lastSync)
  * }}}
  *
  * '''Only what is set is sent.''' Every field here is absent by default and an absent field contributes no query
  * parameter at all, which matters because Forgejo's defaults are not this library's to guess: omitting `state` means
  * open issues only, and sending `state=` empty is not the same request. See [[StateFilter]].
  *
  * The builder methods exist because `.scalafix.conf` bans default arguments, and a nine-argument `copy` at every call
  * site would be worse than either. They are `with…`-style and each returns a new query; the type is immutable and safe
  * to share.
  *
  * @param state
  *   which lifecycle states to include; absent means Forgejo's own default of open only
  * @param labels
  *   label names, all of which an issue must carry. Joined with commas on the wire, which is why the element type is
  *   [[LabelName]] and not `String`
  * @param milestones
  *   milestone titles to restrict to, joined with commas for the same reason
  * @param since
  *   only issues '''updated''' at or after this instant. Forgejo compares against `updated_at`, not `created_at`, which
  *   is what makes it usable as an incremental-sync cursor
  * @param before
  *   only issues updated at or before this instant
  * @param createdBy
  *   only issues opened by this login
  * @param assignedBy
  *   only issues assigned to this login. Forgejo's parameter is named `assigned_by`, which is a misnomer in the API
  *   itself — it filters by assignee
  * @param text
  *   full-text search over title and body; Forgejo's `q`
  */
final case class IssueQuery(
    state: Option[StateFilter],
    labels: Vector[LabelName],
    milestones: Vector[MilestoneTitle],
    since: Option[Instant],
    before: Option[Instant],
    createdBy: Option[String],
    assignedBy: Option[String],
    text: Option[String],
):

  /** Restricts the listing to `filter`. */
  def withState(filter: StateFilter): IssueQuery = copy(state = Some(filter))

  /** Requires every one of `names`; an empty vector removes the filter. */
  def withLabels(names: Vector[LabelName]): IssueQuery = copy(labels = names)

  /** Restricts to issues in any of `titles`; an empty vector removes the filter. */
  def withMilestones(titles: Vector[MilestoneTitle]): IssueQuery = copy(milestones = titles)

  /** Restricts to issues updated at or after `moment`. */
  def updatedSince(moment: Instant): IssueQuery = copy(since = Some(moment))

  /** Restricts to issues updated at or before `moment`. */
  def updatedBefore(moment: Instant): IssueQuery = copy(before = Some(moment))

  /** Restricts to issues opened by `login`. */
  def authoredBy(login: String): IssueQuery = copy(createdBy = Some(login))

  /** Restricts to issues assigned to `login`. */
  def assignedTo(login: String): IssueQuery = copy(assignedBy = Some(login))

  /** Restricts to issues whose title or body matches `keywords`. */
  def matching(keywords: String): IssueQuery = copy(text = Some(keywords))

object IssueQuery:

  /** No filters at all — the listing Forgejo serves by default, which is the repository's open issues.
    *
    * The starting point for every query: there is no zero-argument constructor, because a query with defaults would
    * hide which of them are this library's and which are the instance's.
    */
  val Empty: IssueQuery =
    IssueQuery(
      state      = None,
      labels     = Vector.empty,
      milestones = Vector.empty,
      since      = None,
      before     = None,
      createdBy  = None,
      assignedBy = None,
      text       = None,
    )
