package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.issues.StateFilter
import com.worxbend.codeberg4s.paging.PageParams

/** The query strings this group's listing endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the same reason the DTOs do: `state`, `labels`,
  * `created_by` and `assigned_by` are wire spellings, and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]]
  * says a wire spelling is written exactly once. It also means the shape of a request can be asserted on directly,
  * without a stub backend.
  *
  * '''Only parameters the caller set are emitted.''' An absent filter is not the same request as an empty one —
  * omitting `state` gets Forgejo's default of open issues, and `state=` gets a `422` — so nothing here has a default to
  * fall back on.
  *
  * Parameter order is fixed rather than incidental. Forgejo does not care, but a stable order makes a recorded request
  * comparable between runs.
  */
private[codeberg4s] object IssueQueries:

  /** The `page` and `limit` parameters for a paged listing.
    *
    * '''Both, always.''' `golden/MANIFEST.md` records that `limit` alone is silently ignored on some Forgejo endpoints
    * — `?limit=2` against `/forks` returned all 862 forks, and adding `page=1` made the limit take effect — so sending
    * a limit without a page is how a client accidentally pulls an unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)

  /** The filters of `GET /repos/{owner}/{repo}/issues`, in the order the spec declares them.
    *
    * `labels` and `milestones` are joined with commas, which is Forgejo's encoding and has no escape; that is why the
    * elements are [[com.worxbend.codeberg4s.issues.LabelName]] and [[com.worxbend.codeberg4s.issues.MilestoneTitle]],
    * which reject a comma at construction.
    *
    * `since` and `before` are rendered by [[WireInstant]] in the RFC-3339 form Go parses — a malformed one comes back
    * as a `422` carrying a raw Go parse error, per `docs/HAZARDS.md` §4.
    */
  def issues(query: IssueQuery): List[(String, String)] =
    List(
      query.state.map(filter => "state" -> filter.wireValue),
      Option.when(query.labels.nonEmpty)("labels" -> query.labels.map(_.value).mkString(",")),
      query.text.map(keywords => "q" -> keywords),
      Option.when(query.milestones.nonEmpty)("milestones" -> query.milestones.map(_.value).mkString(",")),
      query.since.map(moment     => "since" -> WireInstant.render(moment)),
      query.before.map(moment    => "before" -> WireInstant.render(moment)),
      query.createdBy.map(login  => "created_by" -> login),
      query.assignedBy.map(login => "assigned_by" -> login),
    ).flatten

  /** The `state` filter of `GET /repos/{owner}/{repo}/milestones`.
    *
    * Always emitted, unlike the issue filters: the milestone listing takes a state and nothing else worth naming, so a
    * caller who wants Forgejo's default asks for [[com.worxbend.codeberg4s.issues.StateFilter.Open]] explicitly rather
    * than by omission.
    */
  def milestones(state: StateFilter): List[(String, String)] =
    List("state" -> state.wireValue)
