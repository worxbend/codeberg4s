package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.repositories.gitdata.RefName

/** The filters `GET /repos/{owner}/{repo}/commits` accepts beyond the paging window: where to start, which path to
  * follow, and what to leave out.
  *
  * Built by starting from [[CommitQuery.Empty]] and narrowing:
  *
  * {{{
  * CommitQuery.Empty.startingAt(release).touching(path).excluding(main)
  * }}}
  *
  * '''Only what is set is sent.''' An absent filter contributes no parameter at all: omitting `sha` lists the default
  * branch, omitting `path` lists every commit, and omitting `not` excludes nothing. The spec's three speed switches —
  * `stat`, `verification` and `files` — are not here; they change the shape of each commit rather than which commits
  * come back, and [[RepositoryApi.commits]] leaves them at the instance's defaults.
  *
  * '''This type lives in the client module rather than in the domain''', unlike its siblings
  * [[com.worxbend.codeberg4s.repositories.gitdata.CommitStatusQuery]] and
  * [[com.worxbend.codeberg4s.issues.IssueQuery]], because it was added in a change that could not touch the domain. It
  * is made of domain types and nothing else, and belongs beside them; moving it is a mechanical follow-up.
  *
  * @param sha
  *   the commit, branch or tag to start listing from — Forgejo's `sha`, which despite its name takes any ref. Absent
  *   for the repository's default branch
  * @param path
  *   only commits touching this file or directory; Forgejo's `path`
  * @param not
  *   commits reachable from this ref are left out — Forgejo's `not`, the `^ref` of `git log`
  */
final case class CommitQuery(sha: Option[RefName], path: Option[ContentPath], not: Option[RefName]):

  /** Starts the listing at `ref` instead of the default branch. */
  def startingAt(ref: RefName): CommitQuery = copy(sha = Some(ref))

  /** Restricts the listing to commits touching `target`. */
  def touching(target: ContentPath): CommitQuery = copy(path = Some(target))

  /** Leaves out every commit reachable from `ref`. */
  def excluding(ref: RefName): CommitQuery = copy(not = Some(ref))

object CommitQuery:

  /** No filters: the default branch's history, every commit, nothing excluded. */
  val Empty: CommitQuery = CommitQuery(sha = None, path = None, not = None)
