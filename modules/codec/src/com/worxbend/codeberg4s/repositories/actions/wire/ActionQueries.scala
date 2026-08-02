package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.actions.ActionRunQuery
import com.worxbend.codeberg4s.repositories.actions.ActionTaskQuery
import com.worxbend.codeberg4s.repositories.actions.ArtifactQuery
import com.worxbend.codeberg4s.repositories.actions.JobAttempt
import com.worxbend.codeberg4s.repositories.actions.RunnerLabel
import com.worxbend.codeberg4s.repositories.actions.RunnerVisibility

/** The query strings this group's endpoints send.
  *
  * Rendering lives beside the DTOs rather than in the API class for the reason
  * [[com.worxbend.codeberg4s.issues.wire.IssueQueries]] gives: `head_sha`, `workflow_id`, `run_number` and `visible`
  * are wire spellings, and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written
  * exactly once. It also means the shape of a request can be asserted on directly, without a stub backend.
  *
  * '''Only parameters the caller set are emitted''', with one deliberate exception: [[runners]] always states the
  * visibility it wants, so that what a listing contains is a property of the request rather than of the Forgejo version
  * answering it.
  *
  * ==Arrays are repeated keys==
  *
  * `event` and `status` on the run listing, and `status` on the task listing, are declared `type: array` in
  * `spec/swagger.v1.json`. They are therefore emitted as one key per value — `?status=failure&status=cancelled` — and
  * not as a comma-joined string. That is why [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list of pairs
  * and not a map. `labels` on the job search is the exception: the spec declares it `type: string`, and Forgejo splits
  * it on commas itself, which is what [[com.worxbend.codeberg4s.repositories.actions.RunnerLabel]] rejects a comma for.
  */
private[codeberg4s] object ActionQueries:

  /** The `page` and `limit` parameters for a paged listing.
    *
    * '''Both, always''', for the reason [[com.worxbend.codeberg4s.issues.wire.IssueQueries.paging]] states: a limit
    * sent without a page is silently ignored by some Forgejo endpoints, which is how a client accidentally pulls an
    * unbounded collection.
    */
  def paging(params: PageParams): List[(String, String)] =
    List("page" -> params.page.value.toString, "limit" -> params.size.value.toString)

  /** The filters of the run listing, in the order the spec declares them. */
  def runs(query: ActionRunQuery): List[(String, String)] =
    query.events.toList.map(event => "event" -> event) ++
      query.statuses.toList.map(status => "status" -> status.wireValue) ++
      List(
        query.runNumber.map(number    => "run_number" -> number.toString),
        query.headSha.map(sha         => "head_sha" -> sha.value),
        query.ref.map(reference       => "ref" -> reference),
        query.workflowId.map(workflow => "workflow_id" -> workflow.value),
      ).flatten

  /** The filter of the task listing. */
  def tasks(query: ActionTaskQuery): List[(String, String)] =
    query.statuses.toList.map(status => "status" -> status.wireValue)

  /** The filter both artifact listings take. */
  def artifacts(query: ArtifactQuery): List[(String, String)] =
    query.name.toList.map(name => "name" -> name)

  /** The `visible` parameter of the runner listing. Always emitted; see the object note. */
  def runners(visibility: RunnerVisibility): List[(String, String)] =
    List("visible" -> visibility.wireValue)

  /** The `labels` parameter of the job search, comma-joined because the spec declares it as one string.
    *
    * Empty when the caller named no labels, which asks for every job rather than for none.
    */
  def runnerJobs(labels: Vector[RunnerLabel]): List[(String, String)] =
    if labels.isEmpty then Nil else List("labels" -> labels.map(_.value).mkString(","))

  /** The `attempt` parameter of the job-logs endpoint.
    *
    * Empty when the caller named no attempt, which is how the endpoint is asked for the latest one — see
    * [[com.worxbend.codeberg4s.repositories.actions.JobAttempt]] for why that is not spelled `attempt=0`.
    */
  def jobLogs(attempt: Option[JobAttempt]): List[(String, String)] =
    attempt.toList.map(value => "attempt" -> value.value.toString)
