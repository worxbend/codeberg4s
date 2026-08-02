package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.CommitVerification
import com.worxbend.codeberg4s.repositories.ContentEntry

/** What an editing endpoint answers with: the commit it wrote, and the file as it now stands.
  *
  * Forgejo's `FileResponse`, which is the response of `POST /repos/{owner}/{repo}/diffpatch` and of the file-creation
  * endpoints. Every member is optional because every field of the wire model is, and because a patch that touched
  * several files cannot report one [[content]]: the diffpatch endpoint routinely answers with a [[commit]] and no
  * [[content]] at all, which is a successful result and not a partial one.
  *
  * [[content]] reuses [[com.worxbend.codeberg4s.repositories.ContentEntry]] — the same model the contents endpoint
  * returns — because the wire sends the identical `ContentsResponse` object in both places.
  *
  * @param commit
  *   the commit the operation created
  * @param content
  *   the resulting file, when the endpoint reported a single one
  * @param verification
  *   the instance's signature verdict for the new commit, when it reports one
  */
final case class FileChange(
    commit: Option[FileCommit],
    content: Option[ContentEntry],
    verification: Option[CommitVerification],
)
