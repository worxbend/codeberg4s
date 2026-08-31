package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.users.Username

/** The path prefixes the five API classes of this package share.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]], shared with the whole library.
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

  /** The `/orgs/{org}` prefix. */
  def organizationPath(org: OrgName): List[String] =
    List(OrgsSegment, org.value)

  /** The `/teams/{id}` prefix; rooted at the instance, not below the organisation. See the object note. */
  def teamPath(id: TeamId): List[String] =
    List(TeamsSegment, id.value.toString)

  /** The `/users/{username}` prefix, for the two operations that name a person rather than an organisation. */
  def userPath(username: Username): List[String] =
    List("users", username.value)
