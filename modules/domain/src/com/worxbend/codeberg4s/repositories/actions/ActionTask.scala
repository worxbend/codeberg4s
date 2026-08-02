package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.repositories.CommitSha

import java.time.Instant

/** One unit of work a runner executed, as `GET /repos/{owner}/{repo}/actions/tasks` reports it.
  *
  * '''A task is not a run and not a job.''' Forgejo's task listing is the flattened, runner-side view: one entry per
  * execution, carrying the workflow file, the branch and the commit it ran against. A [[ActionRun]] groups tasks by
  * trigger and an [[ActionRunJob]] groups them by workflow job. The three overlap heavily and are addressed by
  * different endpoints, which is why they are three types.
  *
  * '''Derived from `spec/swagger.v1.json`'s `ActionTask` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * @param id
  *   the task's identifier; no endpoint in this group accepts it, and it exists so it cannot be confused with a
  *   [[RunId]] or a [[JobId]]
  * @param status
  *   where the task is; absent when the instance sent a value outside the enumerated set
  * @param workflowId
  *   the workflow file the task came from
  * @param headBranch
  *   the branch the task ran against, as a human reads it
  * @param headSha
  *   the commit the task ran against, absent when the instance sent something that is not an object id
  * @param runNumber
  *   the per-repository counter of the run this task belongs to — the same number as
  *   [[com.worxbend.codeberg4s.repositories.actions.ActionRun.indexInRepo]], and not an addressable identifier
  * @param displayTitle
  *   the title the web UI shows, usually the triggering commit's subject
  * @param runStartedAt
  *   when execution began, absent while the task is still queued
  */
final case class ActionTask(
    id: TaskId,
    name: Option[String],
    status: Option[ActionStatus],
    workflowId: Option[WorkflowFileName],
    headBranch: Option[String],
    headSha: Option[CommitSha],
    event: Option[String],
    runNumber: Option[Long],
    url: Option[String],
    displayTitle: Option[String],
    runStartedAt: Option[Instant],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
