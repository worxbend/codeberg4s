package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier in this group that Forgejo expresses as a positive integer.
  *
  * [[RunId]], [[JobId]], [[ArtifactId]], [[TaskId]] and [[JobAttempt]] are all `int64` on the wire, and the first three
  * end up interpolated into a request path. A number cannot forge a path, so the point is confusion rather than
  * escaping: a run's instance-wide `id`, its per-repository `index_in_repo`, the `task_id` of one of its jobs and the
  * id of an artifact it produced are all `Long`, all plausible values for one another, and all reachable from the same
  * response. Passing one where another belongs gets a `404` that reads like a missing resource rather than like a
  * caller bug.
  *
  * This duplicates `com.worxbend.codeberg4s.issues.NumericId` and `com.worxbend.codeberg4s.pulls.PullIds`, both of
  * which are private to their own group and therefore unreachable from here. `docs/LEDGER.md` already lists that kind
  * of helper under "helpers awaiting promotion"; the right fix is one shared validator in the domain module root, not a
  * widened internal.
  */
private[actions] object ActionIds:

  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue"))
    else Right(value)

/** The instance-wide identifier of one Actions run — the `{run_id}` of `/repos/{owner}/{repo}/actions/runs/{run_id}`.
  *
  * This is '''not''' the run's `index_in_repo`, the per-repository counter the web UI shows as `#42` and the run
  * listing filters on with `run_number`. Both are `int64`, both are on every run object, and the API accepts only this
  * one in a path.
  */
opaque type RunId = Long

object RunId:

  /** Parses a run identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"runId"` field
    */
  def from(value: Long): Either[ValidationError, RunId] =
    ActionIds.from("runId", value)

  extension (id: RunId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The instance-wide identifier of one job of a run — the `{job_id}` of `/repos/{owner}/{repo}/actions/jobs/{job_id}`.
  *
  * Distinct from the job's `task_id`, which is the identifier of the runner task that most recently executed the job
  * and which no endpoint in this group accepts.
  */
opaque type JobId = Long

object JobId:

  /** Parses a job identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"jobId"` field
    */
  def from(value: Long): Either[ValidationError, JobId] =
    ActionIds.from("jobId", value)

  extension (id: JobId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The identifier of one artifact produced by a run — the `{artifact_id}` of
  * `/repos/{owner}/{repo}/actions/artifacts/{artifact_id}`.
  */
opaque type ArtifactId = Long

object ArtifactId:

  /** Parses an artifact identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"artifactId"` field
    */
  def from(value: Long): Either[ValidationError, ArtifactId] =
    ActionIds.from("artifactId", value)

  extension (id: ArtifactId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The identifier of one runner task, as `GET /repos/{owner}/{repo}/actions/tasks` reports it.
  *
  * No endpoint in this group takes a task id in a path — the type exists so that a task's id cannot be mistaken for a
  * [[RunId]] or a [[JobId]], which is exactly the confusion the three types are here to prevent.
  */
opaque type TaskId = Long

object TaskId:

  /** Parses a task identifier. Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"taskId"` field
    */
  def from(value: Long): Either[ValidationError, TaskId] =
    ActionIds.from("taskId", value)

  extension (id: TaskId)

    /** The identifier as a `Long`. */
    def value: Long = id

/** Which execution of a job to read logs for — the `attempt` query parameter of the job-logs endpoint.
  *
  * One-based, matching the `attempt` field of the job listing. Zero is rejected rather than being silently read as "the
  * latest": omitting the parameter is how a caller asks for the latest attempt, and the two must not be spelled the
  * same way.
  */
opaque type JobAttempt = Long

object JobAttempt:

  /** Parses an attempt number. Rejects anything below `1`.
    *
    * @return
    *   the attempt, or a [[ValidationError]] on the `"jobAttempt"` field
    */
  def from(value: Long): Either[ValidationError, JobAttempt] =
    ActionIds.from("jobAttempt", value)

  extension (attempt: JobAttempt)

    /** The attempt as a `Long`, ready to be rendered into a query parameter. */
    def value: Long = attempt
