package com.worxbend.codeberg4s.repositories

/** How much a commit changed, in lines.
  *
  * @param total
  *   [[additions]] plus [[deletions]], as the instance computes it
  * @param additions
  *   lines added
  * @param deletions
  *   lines removed
  */
final case class CommitStats private[codeberg4s] (total: Long, additions: Long, deletions: Long)
