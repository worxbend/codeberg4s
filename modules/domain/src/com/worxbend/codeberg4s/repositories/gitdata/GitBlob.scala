package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.FileContent

/** A Git blob read by its object id — `GET /repos/{owner}/{repo}/git/blobs/{sha}`.
  *
  * A blob is bytes and nothing else: it has no path, no mode and no history, because those live in the trees and
  * commits that reference it. If you want a path, you wanted the contents endpoint.
  *
  * [[content]] is a [[com.worxbend.codeberg4s.repositories.FileContent]] rather than a second base64 representation of
  * its own. That type already pairs a payload with the encoding Forgejo declared for it and decodes on demand, and the
  * blob endpoint sends the identical `content`/`encoding` pair as the contents endpoint.
  *
  * @param sha
  *   the blob's object id
  * @param size
  *   the blob's size in bytes as the instance reports it, `0` when it reported none. Not derived from [[content]]:
  *   Forgejo refuses to inline a blob past its configured `default_max_blob_size` and still reports the size
  * @param content
  *   the bytes, still encoded, absent when the instance sent none
  * @param url
  *   the API URL of the blob, when the endpoint reports one
  */
final case class GitBlob private[codeberg4s] (
    sha: CommitSha,
    size: Long,
    content: Option[FileContent],
    url: Option[String],
)
