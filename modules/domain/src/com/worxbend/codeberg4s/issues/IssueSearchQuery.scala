package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.repositories.Owner

import java.time.Instant

/** Everything `GET /repos/issues/search` may be asked, as one value.
  *
  * Eighteen query parameters, of which sixteen are optional filters — the shape [[CreateIssue]] and [[IssueQuery]] are
  * command types for. A method taking them positionally would be unreadable at every call site.
  *
  * ==Five filters mean "the authenticated account"==
  *
  * [[assigned]], [[created]], [[mentioned]], [[reviewRequested]] and [[reviewed]] are all relative to whoever the token
  * belongs to, and all five default to `false` on the wire. They are plain `Boolean`s rather than options because
  * `false` and absent are the same request here: the spec gives each a `default: false`, so omitting the parameter and
  * sending `false` ask the same question. Only a `true` is emitted, which keeps a query string that a caller can read.
  *
  * '''Anonymously, those five do nothing useful.''' The endpoint's security is optional, so an unauthenticated search
  * works; a filter relative to an account that does not exist simply matches nothing.
  *
  * ==Everything else==
  *
  * The remaining filters are options, and an absent one is not an empty one — omitting [[state]] gets Forgejo's default
  * of open issues, `state=` gets a `422`.
  *
  * @param state
  *   which lifecycle states to include; absent asks for the instance's default, which is open only
  * @param labels
  *   labels to match, joined into one comma-separated parameter; see [[LabelName]] for why they are validated
  * @param milestones
  *   milestone titles to match, joined the same way; see [[MilestoneTitle]]
  * @param text
  *   the `q` search string
  * @param priorityRepoId
  *   a repository whose matches should be ranked first. A raw `Long` because it is a repository's instance-wide id,
  *   which this group does not own a type for
  * @param kind
  *   issues only, pull requests only, or both; see [[IssueKind]]
  * @param since
  *   only issues updated at or after this instant
  * @param before
  *   only issues updated at or before this instant
  * @param assigned
  *   only issues assigned to the authenticated account
  * @param created
  *   only issues the authenticated account opened
  * @param mentioned
  *   only issues mentioning the authenticated account
  * @param reviewRequested
  *   only pull requests where the authenticated account's review was requested
  * @param reviewed
  *   only pull requests the authenticated account has reviewed
  * @param owner
  *   restrict to one repository owner
  * @param team
  *   restrict to one team, which Forgejo accepts only together with an organisation [[owner]]
  * @param sort
  *   the ordering; absent takes the instance's default of [[IssueSearchSort.Latest]]
  */
final case class IssueSearchQuery(
    state: Option[StateFilter],
    labels: Vector[LabelName],
    milestones: Vector[MilestoneTitle],
    text: Option[String],
    priorityRepoId: Option[Long],
    kind: Option[IssueKind],
    since: Option[Instant],
    before: Option[Instant],
    assigned: Boolean,
    created: Boolean,
    mentioned: Boolean,
    reviewRequested: Boolean,
    reviewed: Boolean,
    owner: Option[Owner],
    team: Option[String],
    sort: Option[IssueSearchSort],
):

  /** Restricts the search to `filter`'s lifecycle states. */
  def withState(filter: StateFilter): IssueSearchQuery = copy(state = Some(filter))

  /** Restricts the search to issues carrying any of `names`. */
  def withLabels(names: Vector[LabelName]): IssueSearchQuery = copy(labels = names)

  /** Restricts the search to issues in any of `titles`. */
  def withMilestones(titles: Vector[MilestoneTitle]): IssueSearchQuery = copy(milestones = titles)

  /** Sets the free-text term. */
  def matching(keywords: String): IssueSearchQuery = copy(text = Some(keywords))

  /** Ranks matches from the repository `id` first. */
  def prioritising(id: Long): IssueSearchQuery = copy(priorityRepoId = Some(id))

  /** Restricts the search to issues, or to pull requests; see [[IssueKind]]. */
  def onlyOf(which: IssueKind): IssueSearchQuery = copy(kind = Some(which))

  /** Restricts the search to issues updated at or after `moment`. */
  def updatedSince(moment: Instant): IssueSearchQuery = copy(since = Some(moment))

  /** Restricts the search to issues updated at or before `moment`. */
  def updatedBefore(moment: Instant): IssueSearchQuery = copy(before = Some(moment))

  /** Restricts the search to issues assigned to the authenticated account. */
  def assignedToMe: IssueSearchQuery = copy(assigned = true)

  /** Restricts the search to issues the authenticated account opened. */
  def createdByMe: IssueSearchQuery = copy(created = true)

  /** Restricts the search to issues mentioning the authenticated account. */
  def mentioningMe: IssueSearchQuery = copy(mentioned = true)

  /** Restricts the search to pull requests awaiting the authenticated account's review. */
  def awaitingMyReview: IssueSearchQuery = copy(reviewRequested = true)

  /** Restricts the search to pull requests the authenticated account has reviewed. */
  def reviewedByMe: IssueSearchQuery = copy(reviewed = true)

  /** Restricts the search to repositories owned by `handle`. */
  def ownedBy(handle: Owner): IssueSearchQuery = copy(owner = Some(handle))

  /** Restricts the search to a team; Forgejo wants an organisation [[owner]] alongside. */
  def inTeam(name: String): IssueSearchQuery = copy(team = Some(name))

  /** Orders the results; see [[IssueSearchSort]]. */
  def sortedBy(order: IssueSearchSort): IssueSearchQuery = copy(sort = Some(order))

object IssueSearchQuery:

  /** No filters at all — what the instance returns by default, which is open issues from everywhere. */
  val Empty: IssueSearchQuery =
    IssueSearchQuery(
      state           = None,
      labels          = Vector.empty,
      milestones      = Vector.empty,
      text            = None,
      priorityRepoId  = None,
      kind            = None,
      since           = None,
      before          = None,
      assigned        = false,
      created         = false,
      mentioned       = false,
      reviewRequested = false,
      reviewed        = false,
      owner           = None,
      team            = None,
      sort            = None,
    )
