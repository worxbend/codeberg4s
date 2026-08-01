package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.CommitFileStatus

/** One file a pull request touches, as `GET /repos/{owner}/{repo}/pulls/{index}/files` reports it.
  *
  * Forgejo's `ChangedFile`, which is '''not''' the `CommitAffectedFiles` that
  * [[com.worxbend.codeberg4s.repositories.CommitFile]] models: that one has two keys and no counts, this one carries
  * the line arithmetic and three URLs. The two are kept apart rather than merged, because merging them would produce a
  * model whose fields are populated depending on which endpoint answered.
  *
  * [[status]] '''is''' shared, though — it is the same vocabulary Forgejo derives from Git's status letters, so this
  * model reuses the repository wave's [[com.worxbend.codeberg4s.repositories.CommitFileStatus]] per `docs/LEDGER.md`
  * rather than declaring a second enum with the same seven cases. `golden/pull/files-list.json` shows `"changed"`.
  *
  * No diff text reaches this model. The patch is a separate document — `GET /repos/{owner}/{repo}/pulls/{index}.diff` —
  * and it is not JSON, so it is not this endpoint's to return.
  *
  * @param filename
  *   the file's path relative to the repository root, as Git records it
  * @param status
  *   what the pull request does to the file, absent when the instance sent a value this library does not recognise; see
  *   [[com.worxbend.codeberg4s.repositories.CommitFileStatus.parse]]
  * @param additions
  *   lines added; `0` when the instance did not report a count
  * @param deletions
  *   lines removed
  * @param changes
  *   Forgejo's own total. Usually `additions + deletions`, and read from the payload rather than recomputed, because a
  *   client that recomputes a server-side number quietly disagrees with the web UI
  * @param previousFilename
  *   where the file was before, populated only for a rename
  * @param contentsUrl
  *   the API URL of the file's contents at the pull request's head commit
  * @param rawUrl
  *   the browser URL of the raw file at that same commit
  */
final case class ChangedFile(
    filename: String,
    status: Option[CommitFileStatus],
    additions: Long,
    deletions: Long,
    changes: Long,
    previousFilename: Option[String],
    htmlUrl: Option[String],
    contentsUrl: Option[String],
    rawUrl: Option[String],
)
