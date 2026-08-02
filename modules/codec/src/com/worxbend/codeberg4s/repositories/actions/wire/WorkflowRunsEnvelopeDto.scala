package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.JsonFields

/** The wrapper the run and task listings put around their results.
  *
  * ==Two of this group's listings are not arrays==
  *
  * `GET /repos/{owner}/{repo}/actions/runs` and `GET /repos/{owner}/{repo}/actions/tasks` return
  * `{"total_count": n, "workflow_runs": [...]}` — `ListActionRunResponse` and `ActionTaskResponse` in
  * `spec/swagger.v1.json`, two definitions with identical shapes and different element types. Every other listing in
  * this group returns the bare array the rest of the API returns, which is exactly the trap
  * [[com.worxbend.codeberg4s.wire.SearchEnvelopeDto]] documents for `/repos/search`: an endpoint that looks like the
  * ones that work and is not.
  *
  * The key is `workflow_runs` in both, including the one whose elements are tasks. That is Forgejo's spelling, not a
  * transcription error.
  *
  * ==`total_count` is carried and not used==
  *
  * [[com.worxbend.codeberg4s.core.ApiPipeline.callPage]] builds a [[com.worxbend.codeberg4s.paging.Page]] from the
  * response '''headers''', so [[com.worxbend.codeberg4s.paging.Page.totalCount]] reports `X-Total-Count` and never this
  * field. The two should agree; nothing forces them to, and a caller who needs the body's own count reads it from a DTO
  * rather than from a page.
  *
  * @param totalCount
  *   how many items the whole collection holds, as the body reports it
  * @param entries
  *   the results; empty when `workflow_runs` is absent, `null`, or not an array
  */
final case class WorkflowRunsEnvelopeDto[A](totalCount: Option[Long], entries: Vector[A])

object WorkflowRunsEnvelopeDto:

  /** The key both listings put their results under, whatever those results are. */
  val EntriesKey: String = "workflow_runs"

  /** Reads the envelope around any element type that already has a reader.
    *
    * Elements are decoded one at a time, so an element that fails fails the whole envelope — the same contract a bare
    * list body has. The path-reporting trade-off is [[com.worxbend.codeberg4s.wire.SearchEnvelopeDto]]'s and is
    * described there.
    */
  given [A](using upickle.default.Reader[A]): upickle.default.Reader[WorkflowRunsEnvelopeDto[A]] =
    JsonFields.reader: fields =>
      WorkflowRunsEnvelopeDto(
        totalCount = fields.number("total_count"),
        entries    = fields.values(EntriesKey).map(element => element.transform(upickle.default.reader[A])),
      )
