package com.worxbend.codeberg4s.repositories

import java.time.Instant

/** The commit a branch points at, as the branch endpoints report it.
  *
  * Forgejo's `PayloadCommit`, which is a different model from the [[Commit]] returned by
  * `GET /repos/{owner}/{repo}/commits`: it identifies the commit as `id` rather than `sha`, its author and committer
  * are [[GitIdentity]] values rather than accounts, and it carries no parents, files or stats. The two are kept
  * separate here rather than merged, because merging them would mean a model whose every field is optional and whose
  * shape depends on which endpoint produced it.
  *
  * @param sha
  *   the commit's object id, from the wire's `id`
  * @param message
  *   the full commit message, subject and body, exactly as Git stores it — for `forgejo/forgejo` this is routinely
  *   several kilobytes of release notes
  * @param url
  *   the browser URL of the commit, when the endpoint reports one
  * @param author
  *   who wrote the change
  * @param committer
  *   who applied it; the same as [[author]] unless the commit was rebased, cherry-picked or applied on someone's behalf
  * @param verification
  *   the instance's signature verdict, when it reports one
  * @param timestamp
  *   when the commit was made
  */
final case class CommitSummary private[codeberg4s] (
    sha: CommitSha,
    message: Option[String],
    url: Option[String],
    author: Option[GitIdentity],
    committer: Option[GitIdentity],
    verification: Option[CommitVerification],
    timestamp: Option[Instant],
)
