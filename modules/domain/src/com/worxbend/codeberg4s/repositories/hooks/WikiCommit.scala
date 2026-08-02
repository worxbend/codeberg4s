package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.GitIdentity

/** One revision of a wiki page — a commit in the repository's `.wiki` Git repository.
  *
  * A wiki is a Git repository, so its history is made of ordinary commits and this is one of them. It is a much smaller
  * model than [[com.worxbend.codeberg4s.repositories.Commit]]: `WikiCommit` in `spec/swagger.v1.json` declares only
  * `sha`, `author`, `commiter` and `message`, with no verification, no stats and no parents.
  *
  * '''Derived from the spec, not from a captured response'''; see [[Webhook]] for why no fixture exists.
  *
  * '''The wire misspells the committer.''' Forgejo sends the key as `commiter`, with one `t`. The misspelling is part
  * of the API contract and cannot be fixed without breaking every existing client, so it is read as-is by
  * [[com.worxbend.codeberg4s.repositories.hooks.wire.WikiCommitDto]] and corrected on the way into this model — the
  * domain spells it [[committer]].
  *
  * @param sha
  *   the commit's object id, which is required: a revision that cannot be named is not a revision anyone can act on
  * @param author
  *   who wrote the change, as Git recorded it. Not necessarily an account on the instance; see
  *   [[com.worxbend.codeberg4s.repositories.GitIdentity]]
  * @param committer
  *   who committed it, which for a wiki edit made through Forgejo is the same person as [[author]]
  * @param message
  *   the commit message, which is the `message` a caller passed to [[CreateWikiPage]] or [[EditWikiPage]] when the edit
  *   came through this API
  */
final case class WikiCommit(
    sha: CommitSha,
    author: Option[GitIdentity],
    committer: Option[GitIdentity],
    message: Option[String],
)
