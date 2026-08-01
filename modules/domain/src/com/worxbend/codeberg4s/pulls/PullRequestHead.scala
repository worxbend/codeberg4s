package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.Owner

/** The branch a pull request is opened '''from''', in the spelling `CreatePullRequestOption.head` expects.
  *
  * Forgejo overloads one string with two meanings: a bare `branch` names a branch of the same repository, and
  * `owner:branch` names a branch of a fork. `golden/pull/single-open.json` is the second kind — head `fix-pep691` in
  * `trim21/forgejo` against base `forgejo` in `forgejo/forgejo`.
  *
  * There is no `from(value: String)` here, and that is the point. The two spellings are produced by the two
  * constructors below out of values that are '''already''' validated — an
  * [[com.worxbend.codeberg4s.repositories.Owner]] cannot contain a `/` or a control character, and a
  * [[com.worxbend.codeberg4s.repositories.BranchName]] cannot contain a traversal segment — so this type never has to
  * parse a colon back out of a string and never has to decide what `a:b:c` meant. Both constructors are total, which is
  * why neither returns an `Either`.
  */
opaque type PullRequestHead = String

object PullRequestHead:

  /** A branch of the repository the pull request is opened against. */
  def branch(name: BranchName): PullRequestHead = name.value

  /** A branch of `owner`'s fork, rendered as Forgejo's `owner:branch`. */
  def crossRepository(owner: Owner, name: BranchName): PullRequestHead = s"${owner.value}:${name.value}"

  extension (head: PullRequestHead)

    /** The value to put in `CreatePullRequestOption.head`, or in the listing's `head` query parameter. */
    def value: String = head
