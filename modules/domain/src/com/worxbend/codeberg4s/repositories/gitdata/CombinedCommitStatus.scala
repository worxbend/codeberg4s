package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.Repository

/** Every status on a commit, plus the instance's single verdict over all of them — `GET /commits/{ref}/status`.
  *
  * ==Why this is not a page==
  *
  * The endpoint declares `page` and `limit`, and they window [[statuses]] — but the array is wrapped in an object that
  * also carries [[state]], [[sha]] and [[repository]], so a response cannot be turned into a
  * [[com.worxbend.codeberg4s.paging.Page]] without throwing the rest of it away. The window is therefore an argument
  * and this model is the whole answer. [[totalCount]] is the instance's own count of statuses, which is the number to
  * compare against when deciding whether another window is worth asking for.
  *
  * ==What [[state]] means==
  *
  * It is the reduction of the individual [[CommitStatusState]]s, computed by the instance and not by this library: one
  * failure makes the whole commit failed, one pending check keeps it pending, and a commit nobody reported on is
  * pending rather than successful. Recomputing it from [[statuses]] would be wrong whenever the window did not cover
  * them all.
  *
  * @param sha
  *   the commit the statuses belong to. The request may name a branch or a tag; this is what it resolved to
  * @param state
  *   the combined verdict, absent when the instance sent a word this library does not recognise
  * @param totalCount
  *   how many statuses the commit has in total, `0` when the instance did not say
  * @param statuses
  *   the statuses in the requested window, newest first as Forgejo orders them
  * @param repository
  *   the repository the commit is in, as the endpoint echoes it back
  * @param commitUrl
  *   the API URL of the commit
  * @param url
  *   the API URL of the combined status itself
  */
final case class CombinedCommitStatus(
    sha: CommitSha,
    state: Option[CommitStatusState],
    totalCount: Long,
    statuses: Vector[CommitStatus],
    repository: Option[Repository],
    commitUrl: Option[String],
    url: Option[String],
)
