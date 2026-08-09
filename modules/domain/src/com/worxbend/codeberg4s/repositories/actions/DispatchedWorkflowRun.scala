package com.worxbend.codeberg4s.repositories.actions

/** What a workflow dispatch reports back about the run it started, when it reports anything at all.
  *
  * ==Whether this exists is the caller's choice==
  *
  * `POST /repos/{owner}/{repo}/actions/workflows/{workflowfilename}/dispatches` answers `204` with an empty body unless
  * the request set `return_run_info`, in which case it answers `201` with this object. That is why
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.dispatchWorkflow]] returns an `Option` rather
  * than pretending a run description is always available — and why [[DispatchWorkflow.returningRunInfo]] exists.
  *
  * '''Derived from `spec/swagger.v1.json`'s `DispatchWorkflowRun` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * @param id
  *   the run that was started, absent when the instance did not report one
  * @param runNumber
  *   the per-repository counter of the new run — the same number as [[ActionRun.indexInRepo]], and not an addressable
  *   identifier
  * @param jobs
  *   the names of the jobs the run will execute, as the workflow file spells them
  */
final case class DispatchedWorkflowRun private[codeberg4s] (
    id: Option[RunId],
    runNumber: Option[Long],
    jobs: Vector[String],
)
