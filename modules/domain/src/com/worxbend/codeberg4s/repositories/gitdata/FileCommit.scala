package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.CommitRef
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.GitIdentity

import java.time.Instant

/** The commit an editing endpoint created, as it reports it back.
  *
  * Forgejo's `FileCommitResponse`. It is a reduced commit: it names the object it wrote and who wrote it, and nothing
  * about the diff. It is '''not''' [[com.worxbend.codeberg4s.repositories.Commit]] and not
  * [[com.worxbend.codeberg4s.repositories.CommitDetails]] — the wire models are separate definitions with different
  * keys, and the honest thing is to keep them separate here too. Fetch [[sha]] through the commit endpoints if the full
  * commit is what you want.
  *
  * @param sha
  *   the id of the commit that was written
  * @param message
  *   the commit message the instance used, which is its default when the request supplied none
  * @param author
  *   who the commit is attributed to
  * @param committer
  *   who applied it
  * @param tree
  *   the tree the commit points at
  * @param parents
  *   the commit's parents, in Git's order
  * @param created
  *   the commit time
  * @param url
  *   the API URL of the commit
  * @param htmlUrl
  *   the browser URL of the commit
  */
final case class FileCommit private[codeberg4s] (
    sha: CommitSha,
    message: Option[String],
    author: Option[GitIdentity],
    committer: Option[GitIdentity],
    tree: Option[CommitRef],
    parents: Vector[CommitRef],
    created: Option[Instant],
    url: Option[String],
    htmlUrl: Option[String],
)
