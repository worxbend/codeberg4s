package com.worxbend.codeberg4s.repositories.actions

/** One job of a run — a single `jobs:` entry of the workflow file, executed on one runner.
  *
  * '''Derived from `spec/swagger.v1.json`'s `ActionRunJob` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * ==Labels are strings here, on purpose==
  *
  * [[runsOn]] is a `Vector[String]` rather than a `Vector[RunnerLabel]`. [[RunnerLabel]] exists to keep a comma out of
  * the '''filter''' that joins labels with commas; applying it to a value read back from the instance would mean
  * dropping a label Forgejo genuinely holds because this library dislikes its spelling. Reading is lenient, filtering
  * is strict.
  *
  * @param id
  *   the identifier the job-logs endpoint addresses this job by
  * @param runId
  *   the run this job belongs to
  * @param status
  *   where the job is; absent when the instance sent a value outside the enumerated set
  * @param needs
  *   the names of the jobs this one waits for, as the workflow file spells them
  * @param runsOn
  *   the runner labels the job requires — see the note above
  * @param attempt
  *   how many times the job has been executed, counting the current execution. This is the value the job-logs endpoint
  *   takes as [[JobAttempt]]
  * @param taskId
  *   the runner task that most recently executed the job. Not a [[JobId]], and no endpoint here accepts it
  * @param handle
  *   an opaque identifier for one attempt of one job, which the instance uses to correlate logs
  * @param ownerId
  *   the numeric id of the account owning the repository; `0` on the wire, and absent here, for a repository-owned job
  * @param repoId
  *   the numeric id of the repository the job ran for
  */
final case class ActionRunJob private[codeberg4s] (
    id: JobId,
    name: Option[String],
    runId: Option[RunId],
    status: Option[ActionStatus],
    needs: Vector[String],
    runsOn: Vector[String],
    attempt: Option[JobAttempt],
    taskId: Option[Long],
    handle: Option[String],
    ownerId: Option[Long],
    repoId: Option[Long],
)
