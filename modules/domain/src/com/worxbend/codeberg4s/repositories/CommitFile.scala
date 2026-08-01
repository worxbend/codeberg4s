package com.worxbend.codeberg4s.repositories

/** One file a commit touched.
  *
  * Forgejo names this `CommitAffectedFiles` and gives it two keys and no diff. The patch itself is a different endpoint
  * (`GET /repos/{owner}/{repo}/git/commits/{sha}.diff`), which is why nothing here carries content.
  *
  * @param filename
  *   the file's path relative to the repository root, as Git records it
  * @param status
  *   what the commit did to the file, absent when the instance sent a value this library does not recognise; see
  *   [[CommitFileStatus.parse]]
  */
final case class CommitFile(filename: String, status: Option[CommitFileStatus])
