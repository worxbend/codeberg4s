package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.{Commit, CommitFile}

/** What separates two refs — `GET /repos/{owner}/{repo}/compare/{basehead}`.
  *
  * Forgejo calls this `Compare`. The comparison is Git's symmetric-difference form, `base...head`: [[commits]] are the
  * commits reachable from the head and not from the base, and not the other way round.
  *
  * ==[[totalCommits]] is not `commits.size`==
  *
  * The endpoint declares no `page` or `limit` parameter, and Forgejo caps the commit array it returns. [[totalCommits]]
  * is the instance's own count of the whole difference, so the two disagree exactly when the comparison was truncated —
  * which is the only way a caller can find out that it was. Comparing a release tag against a long-lived branch is
  * where this bites.
  *
  * @param totalCommits
  *   how many commits separate the two refs in total, `0` when the instance did not say
  * @param commits
  *   the commits themselves, as many as the instance chose to return
  * @param files
  *   the files the difference touches. Names and statuses only — the diff itself is a different endpoint, and
  *   [[com.worxbend.codeberg4s.repositories.CommitFile]] says why
  */
final case class CommitComparison private[codeberg4s] (
    totalCommits: Long,
    commits: Vector[Commit],
    files: Vector[CommitFile],
):

  /** Whether the instance returned fewer commits than it says the comparison holds — see the note on [[totalCommits]]. */
  def isTruncated: Boolean = commits.size.toLong < totalCommits
