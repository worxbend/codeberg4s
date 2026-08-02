package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.repositories.CommitSha

/** The filters of `GET /repos/{owner}/{repo}/actions/runs`.
  *
  * '''Only what the caller set is sent.''' An absent filter is not the same request as an empty one: omitting `status`
  * asks for every run, and sending `status=` is a request Forgejo has no reading of. Nothing here has a default to fall
  * back on, which is why [[ActionRunQuery.Empty]] renders to no parameters at all.
  *
  * {{{
  * ActionRunQuery.Empty
  *   .triggeredBy("push")
  *   .withStatus(ActionStatus.Failure)
  *   .onRef("refs/heads/main")
  * }}}
  *
  * @param events
  *   which webhook events to include — `push`, `pull_request`, `workflow_dispatch`. Sent as a repeated query parameter,
  *   one per event, because the spec declares it as an array
  * @param statuses
  *   which lifecycle states to include, likewise repeated. [[ActionStatus]] rather than `String` so a typo is a compile
  *   error instead of an empty page
  * @param runNumber
  *   the per-repository counter of one run — [[ActionRun.indexInRepo]], '''not''' [[RunId]]
  * @param headSha
  *   only runs against this commit
  * @param ref
  *   only runs involving this Git reference, for example `refs/heads/main`
  * @param workflowId
  *   only runs of this workflow file
  */
final case class ActionRunQuery(
    events: Vector[String],
    statuses: Vector[ActionStatus],
    runNumber: Option[Long],
    headSha: Option[CommitSha],
    ref: Option[String],
    workflowId: Option[WorkflowFileName],
):

  /** Adds one webhook event to the filter. */
  def triggeredBy(event: String): ActionRunQuery = copy(events = events.appended(event))

  /** Adds one lifecycle state to the filter. */
  def withStatus(status: ActionStatus): ActionRunQuery = copy(statuses = statuses.appended(status))

  /** Restricts the listing to the run carrying this per-repository counter. */
  def numbered(number: Long): ActionRunQuery = copy(runNumber = Some(number))

  /** Restricts the listing to runs against one commit. */
  def atCommit(sha: CommitSha): ActionRunQuery = copy(headSha = Some(sha))

  /** Restricts the listing to runs involving one Git reference. */
  def onRef(reference: String): ActionRunQuery = copy(ref = Some(reference))

  /** Restricts the listing to runs of one workflow file. */
  def ofWorkflow(workflow: WorkflowFileName): ActionRunQuery = copy(workflowId = Some(workflow))

object ActionRunQuery:

  /** No filters at all — every run the caller may see, in the instance's own order. */
  val Empty: ActionRunQuery =
    ActionRunQuery(
      events     = Vector.empty,
      statuses   = Vector.empty,
      runNumber  = None,
      headSha    = None,
      ref        = None,
      workflowId = None,
    )
