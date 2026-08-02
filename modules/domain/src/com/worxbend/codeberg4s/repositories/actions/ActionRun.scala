package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.users.User

import scala.concurrent.duration.FiniteDuration

import java.time.Instant

/** One execution of a workflow, triggered by an event on a repository.
  *
  * '''Derived from `spec/swagger.v1.json`'s `ActionRun` definition, not from a captured response.''' See
  * [[ActionArtifact]] for why no fixture backs this group, and `docs/HAZARDS.md` §1 for why every field the domain can
  * live without is therefore optional.
  *
  * ==Two numbers, and only one of them addresses the run==
  *
  * [[id]] is what `/repos/{owner}/{repo}/actions/runs/{run_id}` takes. [[indexInRepo]] is the per-repository counter
  * the web UI shows and the run listing filters on with `run_number`. Both are `int64` on the wire; only the first is
  * an addressable identifier, which is why only the first has a type.
  *
  * ==Two event fields, and they answer different questions==
  *
  * [[event]] is the webhook event that fired — `push`, `pull_request`, `workflow_dispatch`. [[triggerEvent]] is the
  * entry from the workflow's own `on:` configuration that matched it. They usually agree and are not required to.
  *
  * @param status
  *   where the run is; absent when the instance sent a value outside the enumerated set, per [[ActionStatus.parse]]
  * @param workflowId
  *   the workflow file the run came from — the same string [[com.worxbend.codeberg4s.repositories.actions]]'s dispatch
  *   endpoint takes as `{workflowfilename}`
  * @param commitSha
  *   the commit the run executed against, absent when the instance sent something that is not an object id
  * @param prettyRef
  *   the branch or tag as a human reads it, for example `main` rather than `refs/heads/main`
  * @param repository
  *   which repository the run belongs to, projected from the embedded repository object down to its slug. Absent rather
  *   than failing when that object cannot name an addressable repository
  * @param triggerUser
  *   the account whose action started the run, absent for a schedule or an instance-initiated run
  * @param isForkPullRequest
  *   whether the run was triggered from a fork, which is what makes [[needApproval]] meaningful
  * @param needApproval
  *   whether the run is held until a maintainer approves it
  * @param isRefDeleted
  *   whether the branch or tag the run ran on has since been deleted
  * @param approvedBy
  *   the numeric id of the account that released a held run; absent, and `0` on the wire, when nobody did
  * @param scheduleId
  *   the cron entry that started the run, for a scheduled trigger only
  * @param duration
  *   how long the run took, as the instance measured it. Forgejo sends a Go `time.Duration`, that is a nanosecond
  *   count, which is why this is a [[scala.concurrent.duration.FiniteDuration]] and not a number of seconds
  * @param startedAt
  *   when a runner picked the run up, absent while it is still queued
  * @param stoppedAt
  *   when the run finished, absent while it is still going
  */
final case class ActionRun(
    id: RunId,
    indexInRepo: Option[Long],
    title: Option[String],
    status: Option[ActionStatus],
    workflowId: Option[WorkflowFileName],
    event: Option[String],
    triggerEvent: Option[String],
    commitSha: Option[CommitSha],
    prettyRef: Option[String],
    htmlUrl: Option[String],
    repository: Option[RepoSlug],
    triggerUser: Option[User],
    isForkPullRequest: Boolean,
    needApproval: Boolean,
    isRefDeleted: Boolean,
    approvedBy: Option[Long],
    scheduleId: Option[Long],
    duration: Option[FiniteDuration],
    createdAt: Option[Instant],
    startedAt: Option[Instant],
    stoppedAt: Option[Instant],
    updatedAt: Option[Instant],
)
