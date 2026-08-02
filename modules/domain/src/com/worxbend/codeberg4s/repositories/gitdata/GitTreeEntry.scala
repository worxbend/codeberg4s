package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.ContentPath

/** One entry of a tree listing — `GET /repos/{owner}/{repo}/git/trees/{sha}`.
  *
  * Forgejo calls this `GitEntry`. [[path]] is relative to the tree that was asked for: for a non-recursive listing it
  * is a single name, and for a recursive one it is the full path from that tree down, which is why it is a
  * [[com.worxbend.codeberg4s.repositories.ContentPath]] and not a bare name.
  *
  * @param path
  *   the entry's path, relative to the requested tree
  * @param sha
  *   the object id of the entry's own object — a blob for a file, a tree for a directory, a commit for a submodule
  * @param kind
  *   what that object is, absent when the instance sent a spelling this library does not recognise; see
  *   [[GitObjectKind.parse]]
  * @param mode
  *   the six-digit octal Git file mode as text, for example `100644` for a regular file and `040000` for a directory.
  *   Kept verbatim: the leading zero is significant and a numeric type would eat it
  * @param size
  *   the blob's size in bytes, `0` for a tree and for an entry whose size the instance did not report
  * @param url
  *   the API URL of the entry's object, when the endpoint reports one
  */
final case class GitTreeEntry(
    path: ContentPath,
    sha: CommitSha,
    kind: Option[GitObjectKind],
    mode: Option[String],
    size: Long,
    url: Option[String],
)
