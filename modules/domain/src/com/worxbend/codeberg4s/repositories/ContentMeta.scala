package com.worxbend.codeberg4s.repositories

import java.time.Instant

/** What every entry in a repository's contents carries, whatever its [[ContentKind]].
  *
  * Split out of [[ContentEntry]] so the kind-specific fields can live on the case that populates them instead of being
  * four `Option`s that every caller has to reason about. See [[ContentEntry]] for why that split is not optional.
  *
  * @param name
  *   the entry's own name, without any directory prefix
  * @param path
  *   the entry's path from the repository root
  * @param sha
  *   the Git object id of the entry — a blob for a file, a tree for a directory
  * @param size
  *   the blob size in bytes; `0` for a directory, which is what Forgejo reports rather than the sum of its children
  * @param lastCommitSha
  *   the last commit that touched this path, when the instance reports it
  * @param lastCommitWhen
  *   when that commit landed, when the instance reports it
  * @param url
  *   the API URL of this entry, including the `ref` it was resolved at
  * @param htmlUrl
  *   the browser URL of this entry
  * @param gitUrl
  *   the API URL of the underlying Git object
  */
final case class ContentMeta(
    name: String,
    path: ContentPath,
    sha: CommitSha,
    size: Long,
    lastCommitSha: Option[CommitSha],
    lastCommitWhen: Option[Instant],
    url: Option[String],
    htmlUrl: Option[String],
    gitUrl: Option[String],
)
