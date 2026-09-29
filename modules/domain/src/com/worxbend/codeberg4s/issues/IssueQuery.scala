package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.{SearchKeyword, ValidationError}

import java.time.Instant

/** The filters `GET /repos/{owner}/{repo}/issues` accepts, as one value rather than as eleven parameters.
  *
  * Built by starting from [[IssueQuery.Empty]] and naming what should change:
  *
  * {{{
  * IssueQuery.Empty
  *   .withState(StateFilter.All)
  *   .onlyOf(IssueKind.Issues)
  *   .withLabels(Vector(bug))
  *   .updatedSince(lastSync)
  * }}}
  *
  * '''Only what is set is sent.''' Every field here is absent by default and an absent field contributes no query
  * parameter at all, which matters because Forgejo's defaults are not this library's to guess: omitting `state` means
  * open issues only, and sending `state=` empty is not the same request. See [[StateFilter]].
  *
  * ==This listing returns pull requests too, unless told otherwise==
  *
  * Forgejo stores a pull request as an issue, and `GET /repos/{owner}/{repo}/issues` lists '''both''' unless the `type`
  * parameter narrows it. A caller who wants issues in the everyday sense says so with [[onlyOf]] and
  * [[IssueKind.Issues]]; the default is the instance's, which is everything.
  *
  * ==Comma-joined lists cannot carry a comma==
  *
  * `labels` and `milestones` reach the wire as one comma-separated parameter each, because that is the encoding Forgejo
  * declares, and it has no escape: a label named `a,b` would be sent as two labels `a` and `b`, and there is no
  * spelling that says otherwise. That is why the elements are [[LabelName]] and [[MilestoneTitle]] rather than `String`
  * — both refuse a comma at construction — and why a label that really does carry a comma on the instance cannot be
  * filtered by through this endpoint at all. It can still be '''read''': a label read back is a plain `String` for
  * exactly that reason.
  *
  * The builder methods exist because `.scalafix.conf` bans default arguments, and an eleven-argument `copy` at every
  * call site would be worse than either. They are `with…`-style and each returns a new query; the type is immutable and
  * safe to share.
  *
  * @param state
  *   which lifecycle states to include; absent means Forgejo's own default of open only
  * @param labels
  *   label names, all of which an issue must carry. Joined with commas on the wire, which is why the element type is
  *   [[LabelName]] and not `String`
  * @param milestones
  *   milestone titles to restrict to, joined with commas for the same reason
  * @param kind
  *   issues only, pull requests only, or — absent — both; Forgejo's `type`. See [[IssueKind]]
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
  * @param mentionedBy
  *   only issues in which this login was mentioned; Forgejo's `mentioned_by`
  * @param text
  *   full-text search over title and body; Forgejo's `q`. Set through [[matching]], which normalises it
  * @param sort
  *   the ordering; absent takes the instance's default of [[IssueSearchSort.Latest]]. The `sort` parameter of this
  *   listing declares the same nine values as the cross-repository search, so the one enum serves both
  */
final case class IssueQuery(
    state: Option[StateFilter],
    labels: Vector[LabelName],
    milestones: Vector[MilestoneTitle],
    kind: Option[IssueKind],
    since: Option[Instant],
    before: Option[Instant],
    createdBy: Option[String],
    assignedBy: Option[String],
    mentionedBy: Option[String],
    text: Option[String],
    sort: Option[IssueSearchSort],
):

  /** Restricts the listing to `filter`. */
  def withState(filter: StateFilter): IssueQuery = copy(state = Some(filter))

  /** Requires every one of `names`; an empty vector removes the filter. */
  def withLabels(names: Vector[LabelName]): IssueQuery = copy(labels = names)

  /** Restricts to issues in any of `titles`; an empty vector removes the filter. */
  def withMilestones(titles: Vector[MilestoneTitle]): IssueQuery = copy(milestones = titles)

  /** Restricts the listing to issues, or to pull requests; see [[IssueKind]] and the class note. */
  def onlyOf(which: IssueKind): IssueQuery = copy(kind = Some(which))

  /** Restricts to issues updated at or after `moment`. */
  def updatedSince(moment: Instant): IssueQuery = copy(since = Some(moment))

  /** Restricts to issues updated at or before `moment`. */
  def updatedBefore(moment: Instant): IssueQuery = copy(before = Some(moment))

  /** Restricts to issues opened by `login`. */
  def authoredBy(login: String): IssueQuery = copy(createdBy = Some(login))

  /** Restricts to issues assigned to `login`. */
  def assignedTo(login: String): IssueQuery = copy(assignedBy = Some(login))

  /** Restricts to issues mentioning `login`. */
  def mentioning(login: String): IssueQuery = copy(mentionedBy = Some(login))

  /** Restricts to issues whose title or body matches `keywords`.
    *
    * The keyword is trimmed, a blank one is treated as no keyword at all — it clears the filter rather than sending
    * `q=` — and a control character is rejected; see [[com.worxbend.codeberg4s.SearchKeyword]] for each of those. This
    * is the one builder on the type that can fail, which is why it alone returns an `Either`: every other filter is
    * already a validated type or a closed enum.
    *
    * @return
    *   the query, or a [[ValidationError]] on the `"text"` field
    */
  def matching(keywords: String): Either[ValidationError, IssueQuery] =
    SearchKeyword.normalize("text", keywords).map(normalized => copy(text = normalized))

  /** Orders the results; see [[IssueSearchSort]]. */
  def sortedBy(order: IssueSearchSort): IssueQuery = copy(sort = Some(order))

object IssueQuery:

  /** No filters at all — the listing Forgejo serves by default, which is the repository's open issues '''and''' open
    * pull requests.
    *
    * The starting point for every query: there is no zero-argument constructor, because a query with defaults would
    * hide which of them are this library's and which are the instance's.
    */
  val Empty: IssueQuery =
    IssueQuery(
      state       = None,
      labels      = Vector.empty,
      milestones  = Vector.empty,
      kind        = None,
      since       = None,
      before      = None,
      createdBy   = None,
      assignedBy  = None,
      mentionedBy = None,
      text        = None,
      sort        = None,
    )
