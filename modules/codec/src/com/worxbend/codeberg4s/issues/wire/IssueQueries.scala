package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.issues.{
  CommentQuery,
  IssueQuery,
  IssueSearchQuery,
  StateFilter,
  TrackedTimeQuery,
  UploadAttachment
}

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

  /** The filters of `GET /repos/{owner}/{repo}/issues`, in the order the spec declares them.
    *
    * `labels` and `milestones` are joined with commas, which is Forgejo's encoding and has no escape; that is why the
    * elements are [[com.worxbend.codeberg4s.issues.LabelName]] and [[com.worxbend.codeberg4s.issues.MilestoneTitle]],
    * which reject a comma at construction.
    *
    * `since` and `before` are rendered by [[Timestamps.render]] in the RFC-3339 form Go parses — a malformed one comes
    * back as a `422` carrying a raw Go parse error, per `docs/HAZARDS.md` §4.
    */
  def issues(query: IssueQuery): List[(String, String)] =
    List(
      query.state.map(filter => "state" -> filter.wireValue),
      Option.when(query.labels.nonEmpty)("labels" -> query.labels.map(_.value).mkString(",")),
      query.text.map(keywords => "q" -> keywords),
      Option.when(query.milestones.nonEmpty)("milestones" -> query.milestones.map(_.value).mkString(",")),
      query.since.map(moment     => "since" -> Timestamps.render(moment)),
      query.before.map(moment    => "before" -> Timestamps.render(moment)),
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

  /** The filters of `GET /repos/issues/search`, in the order the spec declares them.
    *
    * Sixteen optional parameters, none of which is emitted unless the caller set it. Five of them — `assigned`,
    * `created`, `mentioned`, `review_requested` and `reviewed` — are booleans the spec gives a `default: false`, so
    * only a `true` is emitted: sending `assigned=false` and omitting it ask the same question, and a query string
    * carrying five explicit falsehoods is harder to read for no gain.
    *
    * `labels` and `milestones` are comma-joined, and `since` and `before` go through [[Timestamps.render]], exactly as
    * in [[issues]].
    */
  def search(query: IssueSearchQuery): List[(String, String)] =
    List(
      query.state.map(filter => "state" -> filter.wireValue),
      Option.when(query.labels.nonEmpty)("labels"         -> query.labels.map(_.value).mkString(",")),
      Option.when(query.milestones.nonEmpty)("milestones" -> query.milestones.map(_.value).mkString(",")),
      query.text.map(keywords     => "q" -> keywords),
      query.priorityRepoId.map(id => "priority_repo_id" -> id.toString),
      query.kind.map(which        => "type" -> which.wireValue),
      query.since.map(moment      => "since" -> Timestamps.render(moment)),
      query.before.map(moment     => "before" -> Timestamps.render(moment)),
      Option.when(query.assigned)("assigned"                -> "true"),
      Option.when(query.created)("created"                  -> "true"),
      Option.when(query.mentioned)("mentioned"              -> "true"),
      Option.when(query.reviewRequested)("review_requested" -> "true"),
      Option.when(query.reviewed)("reviewed"                -> "true"),
      query.owner.map(handle => "owner" -> handle.value),
      query.team.map(name    => "team" -> name),
      query.sort.map(order   => "sort" -> order.wireValue),
    ).flatten

  /** The `since` and `before` window of the repository comment listing and of the issue timeline.
    *
    * Both operations declare exactly these two, and both reject a malformed one with a `422` carrying a raw Go parse
    * error — which is why an unset filter contributes nothing rather than an empty value.
    */
  def comments(query: CommentQuery): List[(String, String)] =
    List(
      query.since.map(moment  => "since" -> Timestamps.render(moment)),
      query.before.map(moment => "before" -> Timestamps.render(moment)),
    ).flatten

  /** The filters of `GET /repos/{owner}/{repo}/issues/{index}/times`, in the order the spec declares them. */
  def trackedTimes(query: TrackedTimeQuery): List[(String, String)] =
    List(
      query.userName.map(login => "user" -> login),
      query.since.map(moment   => "since" -> Timestamps.render(moment)),
      query.before.map(moment  => "before" -> Timestamps.render(moment)),
    ).flatten

  /** The optional `name` and `updated_at` of an attachment upload.
    *
    * Both attachment `POST`s put these in the '''query string''', not in the multipart body — that is how
    * `spec/swagger.v1.json` declares them, alongside the one `formData` part. `name` is only sent when the caller wants
    * the recorded name to differ from the file name; see [[com.worxbend.codeberg4s.issues.UploadAttachment]].
    */
  def attachmentUpload(upload: UploadAttachment): List[(String, String)] =
    List(
      upload.storedName.map(stored => "name" -> stored),
      upload.updatedAt.map(moment  => "updated_at" -> Timestamps.render(moment)),
    ).flatten
