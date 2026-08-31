package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.{BranchName, CommitSha, Repository}

/** One end of a pull request: the branch it merges into, or the branch it merges from.
  *
  * Forgejo's `PRBranchInfo`, carried twice on every pull request as `base` and `head`. [[repository]] is a complete
  * [[com.worxbend.codeberg4s.repositories.Repository]] object, not a reduced one — on `golden/pull/single-open.json`
  * the head's repository is `trim21/forgejo`, a fork whose own `parent` is the full `forgejo/forgejo` — which is why
  * this model reuses the repository wave's type per `docs/LEDGER.md` rather than inventing a smaller one.
  *
  * @param label
  *   Forgejo's display label for the branch: the bare branch name for a same-repository pull request, and the fork's
  *   branch name for a cross-repository one
  * @param ref
  *   the Git reference this end points at. '''Not always a branch that exists''': `golden/pull/list-closed.json` shows
  *   pull request 13726 whose head `ref` is `refs/pull/13726/head`, because the fork branch it was opened from was
  *   deleted when it merged. Reading it back with `GET /repos/{owner}/{repo}/branches/{branch}` would answer `404`, so
  *   treat it as provenance rather than as something to fetch. A value
  *   [[com.worxbend.codeberg4s.repositories.BranchName]] refuses — a traversal segment, say — costs the caller this
  *   field and not the whole pull request
  * @param sha
  *   the commit at this end when the pull request was last synchronised
  * @param repositoryId
  *   the numeric id of the repository this end lives in. Worth reading even when [[repository]] is present: comparing
  *   `base.repositoryId` with `head.repositoryId` is how a caller tells a fork pull request from an internal one
  * @param repository
  *   the repository this end lives in, when the endpoint supplied it
  */
final case class PullRequestBranch private[codeberg4s] (
    label: Option[String],
    ref: Option[BranchName],
    sha: Option[CommitSha],
    repositoryId: Option[Long],
    repository: Option[Repository],
)
