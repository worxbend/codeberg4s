package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.users.Username

/** The four request shapes the five API classes of this package build, and the two path prefixes they share.
  *
  * Shared rather than repeated once per class, for the reason
  * [[com.worxbend.codeberg4s.repositories.hooks.HookRequests]] gives: five copies of the same six-line constructor call
  * is exactly the duplication `docs/LEDGER.md` records as a review-blocking defect — and a copy that quietly forgot to
  * leave `headers` empty would be indistinguishable from one that did not until a credential appeared in a log.
  *
  * ==Two roots, and the difference is not cosmetic==
  *
  * [[organizationPath]] is rooted at `/orgs/{org}` and [[teamPath]] at `/teams/{id}`. A team is addressed by its
  * instance-wide identifier and never below its organisation, because two organisations may each own a team called
  * `owners`; see [[TeamId]]. That is also why the two roots have different retry consequences — an [[OrgName]] is a
  * handle [[OrganizationApi.rename]] can move, and a [[TeamId]] is a row id nothing can.
  */
private[organizations] object OrganizationRequests:

  /** The collection segment `/orgs`, which is both a path of its own and the prefix of every organisation path. */
  val OrgsSegment: String = "orgs"

  /** The instance-rooted collection segment `/teams`. */
  val TeamsSegment: String = "teams"

  /** A `GET` with no body. */
  def read(operation: String, path: List[String], query: List[(String, String)]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** A mutating call carrying a JSON body. */
  def write(operation: String, method: HttpMethod, path: List[String], body: String): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(body)),
    )

  /** A mutating call whose whole meaning is its method and its path — every `PUT` in this group, and the deletes.
    *
    * Forgejo's membership, block and team-assignment routes take no body at all: what is being said is said by the
    * path. Sending `{}` on the chance the instance prefers it would be guesswork, and `docs/HAZARDS.md` §4 shows
    * Forgejo answering `400` to bodies it did not expect.
    */
  def bare(operation: String, method: HttpMethod, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  /** The `/orgs/{org}` prefix. */
  def organizationPath(org: OrgName): List[String] =
    List(OrgsSegment, org.value)

  /** The `/teams/{id}` prefix; rooted at the instance, not below the organisation. See the object note. */
  def teamPath(id: TeamId): List[String] =
    List(TeamsSegment, id.value.toString)

  /** The `/users/{username}` prefix, for the two operations that name a person rather than an organisation. */
  def userPath(username: Username): List[String] =
    List("users", username.value)
