package com.worxbend.codeberg4s.pulls

import java.util.Locale

/** How `POST /repos/{owner}/{repo}/pulls/{index}/update` should bring a pull request's branch up to date with its base,
  * and the `default_update_style` a repository falls back to.
  *
  * This operation writes to the '''head''' branch, and the two cases differ in whether that write is destructive:
  *
  *   - [[Merge]] records a merge commit of the base into the head. Every existing commit keeps its sha, so anyone who
  *     has the branch checked out can fast-forward;
  *   - [[Rebase]] replays the head's commits onto the base and '''force-pushes''' the result. Every commit gets a new
  *     sha, and every existing checkout of the branch — and every review pinned to one of the old commits — is left
  *     pointing at history that no longer exists.
  *
  * Forgejo has no third option and no "leave it to the repository's preference": its handler reads the `style` query
  * parameter and treats anything other than the literal `rebase` as a merge. Naming one of these is therefore exactly
  * as expressive as omitting the parameter, and considerably harder to get wrong by accident, which is why
  * [[com.worxbend.codeberg4s.pulls.PullRequestApi.updateBranch]] requires it.
  *
  * The same two words are what `default_update_style` on `EditRepoOption` accepts — `spec/swagger.v1.json` types that
  * property as a bare string and names them in its description — so this enum serves
  * [[com.worxbend.codeberg4s.repositories.admin.EditRepository.updatingBy]] as well.
  */
enum UpdateStyle:

  /** Merge the base branch into the head, leaving existing commits untouched. Forgejo's `merge`. */
  case Merge

  /** Replay the head's commits onto the base and force-push. Rewrites history; see the class note above. Forgejo's
    * `rebase`.
    */
  case Rebase

  /** The value to put in the `style` query parameter, or in `default_update_style`. */
  def wireValue: String =
    this match
      case Merge  => "merge"
      case Rebase => "rebase"

object UpdateStyle:

  /** Parses Forgejo's spelling, case-insensitively and trimming. Answers `None` for anything else. */
  def parse(value: String): Option[UpdateStyle] =
    val normalized = value.trim.toLowerCase(Locale.ROOT)

    values.find(_.wireValue.contentEquals(normalized))
