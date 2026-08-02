package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** One status a check reported against a commit — an element of `GET /repos/{owner}/{repo}/commits/{ref}/statuses`.
  *
  * A commit accumulates statuses rather than having one: every CI system, linter and bot that knows about the
  * repository can write its own, and they are distinguished by [[context]]. Several statuses may share a context, in
  * which case the newest is the current one — which is what [[CombinedCommitStatus]] folds them into.
  *
  * @param id
  *   the instance-local row identifier of this status
  * @param state
  *   the verdict, absent when the instance sent a word this library does not recognise; see [[CommitStatusState.parse]]
  * @param context
  *   the check's own name for itself, such as `ci/woodpecker/push`. The key statuses are grouped and superseded by
  * @param description
  *   the one-line summary a UI shows next to the state
  * @param targetUrl
  *   where a human should be sent to see the check — a build log, usually
  * @param creator
  *   the account whose token wrote the status, absent for a status written by the instance itself
  * @param created
  *   when the status was first written
  * @param updated
  *   when it was last rewritten
  * @param url
  *   the API URL of the status, when the endpoint reports one
  */
final case class CommitStatus(
    id: Long,
    state: Option[CommitStatusState],
    context: Option[String],
    description: Option[String],
    targetUrl: Option[String],
    creator: Option[User],
    created: Option[Instant],
    updated: Option[Instant],
    url: Option[String],
)
