package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** A commit, as `GET /repos/{owner}/{repo}/commits` reports it.
  *
  * Two views of authorship live side by side and mean different things. [[author]] and [[committer]] are instance
  * accounts, present only when Forgejo matched the Git address to a registered user; `details.author` and
  * `details.committer` are what the Git object itself records and are always what the repository's history says. A
  * commit from someone with no account on the instance has the latter and not the former.
  *
  * [[files]] and [[stats]] are populated by the listing endpoint used here, but not by every endpoint that returns this
  * model — `GET /repos/{owner}/{repo}/git/commits/{sha}` omits them unless asked. An empty [[files]] therefore means
  * "not reported", not "touched nothing".
  *
  * @param sha
  *   the commit's object id
  * @param url
  *   the API URL of the commit
  * @param htmlUrl
  *   the browser URL of the commit
  * @param created
  *   the commit time
  * @param author
  *   the instance account Forgejo matched the Git author to, when it matched one
  * @param committer
  *   the instance account Forgejo matched the Git committer to, when it matched one
  * @param details
  *   the Git object itself: message, tree, identities, signature
  * @param parents
  *   the commit's parents, in Git's order; empty for a root commit, two or more for a merge
  * @param files
  *   the files this commit touched, when the endpoint reports them
  * @param stats
  *   the line counts, when the endpoint reports them
  */
final case class Commit private[codeberg4s] (
    sha: CommitSha,
    url: Option[String],
    htmlUrl: Option[String],
    created: Option[Instant],
    author: Option[User],
    committer: Option[User],
    details: Option[CommitDetails],
    parents: Vector[CommitRef],
    files: Vector[CommitFile],
    stats: Option[CommitStats],
)
