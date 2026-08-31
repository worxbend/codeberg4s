package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

/** One issue named from outside its own repository — Forgejo's `IssueMeta`, and the body of every blocking and
  * dependency call.
  *
  * '''Derived from `spec/swagger.v1.json`''': `IssueMeta` declares `owner`, `repo` and `index`, and it is the request
  * body of all six of `POST`/`DELETE` on `…/issues/{index}/blocks` and `…/issues/{index}/dependencies`. No golden
  * capture of those requests exists.
  *
  * '''Cross-repository on purpose.''' The whole reason the body repeats an owner and a repository the URL already names
  * is that a dependency may point at an issue somewhere else entirely; a caller linking two issues in the same
  * repository still has to state which repository that is.
  *
  * The three components are the validated types the rest of this library uses, so an `IssueRef` can be rendered into a
  * request body without further checks and cannot carry a value that would forge a path.
  *
  * @param owner
  *   the owner of the repository the referenced issue lives in
  * @param repo
  *   that repository's name
  * @param number
  *   the referenced issue's per-repository number, '''not''' its instance-wide id; see [[IssueNumber]]
  */
final case class IssueRef(owner: Owner, repo: RepoName, number: IssueNumber)
