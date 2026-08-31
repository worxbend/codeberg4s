package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

/** Everything `POST /repos/{owner}/{repo}/forks` may be told, as one value.
  *
  * Derived from `CreateForkOption` in `spec/swagger.v1.json`, which declares two optional properties and nothing
  * required — an empty body forks the repository under the authenticated account, keeping its name. No golden capture
  * of this request exists; `golden/repository/forks-list.json` captures the '''listing''' of forks, which is a
  * different operation.
  *
  * '''Forking is asynchronous on the server.''' The operation answers `202 Accepted` with the new repository, not
  * `201`, and the Git data is copied afterwards. A repository that comes back from this call may therefore still be
  * empty for a while; `docs/HAZARDS.md` §5 is about paging, but the same "the response is a promise, not a state"
  * caution applies here.
  *
  * @param name
  *   the name for the fork. Absent keeps the upstream name, which is what makes the `409` in the spec — "The repository
  *   with the same name already exists" — the most likely failure of an unadorned fork
  * @param organization
  *   the organisation to fork into. Absent forks into the authenticated account
  */
final case class CreateFork(name: Option[RepoName], organization: Option[Owner]):

  /** Names the fork `target` instead of reusing the upstream name. */
  def named(target: RepoName): CreateFork = copy(name = Some(target))

  /** Forks into `org` instead of into the authenticated account. */
  def into(org: Owner): CreateFork = copy(organization = Some(org))

  /** Whether this command would send an empty object — the plain "fork it to me, same name" request. */
  def isEmpty: Boolean = name.isEmpty && organization.isEmpty

object CreateFork:

  /** The plain fork: into the authenticated account, under the upstream name. */
  val Empty: CreateFork = CreateFork(name = None, organization = None)
