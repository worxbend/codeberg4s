package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.users.UserVisibility

/** What `PATCH /orgs/{org}` may be told — Forgejo's `EditOrgOption`.
  *
  * {{{
  * EditOrganization.Empty.describedAs("we forge").visibleAs(UserVisibility.Private)
  * }}}
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured request.''' Editing an organisation needs a token and
  * the golden harvest was anonymous. The seven properties the spec declares are all here, and it marks none of them
  * required.
  *
  * ==Absent means "leave it alone"==
  *
  * A field left `None` contributes no JSON key, so the instance does not touch that property. That is why
  * [[repoAdminChangeTeamAccess]] is an `Option[Boolean]` and not a `Boolean`: on a create, `false` can mean "leave the
  * default"; on an edit it has to be able to mean "turn this off", and a plain `Boolean` cannot say both.
  * [[EditOrganization.Empty]] is a well-formed request that changes nothing.
  *
  * ==The handle is not editable here==
  *
  * `EditOrgOption` has no name field. Renaming an organisation is [[OrganizationApi.rename]], a separate endpoint with
  * a separate body and a much sharper warning attached to it.
  *
  * @param fullName
  *   the display name to store
  * @param description
  *   the description to store
  * @param email
  *   the contact address to store
  * @param website
  *   the website to store
  * @param location
  *   the location to store
  * @param visibility
  *   who may see the organisation; see [[CreateOrganization.visibility]] for why the type is named for users
  * @param repoAdminChangeTeamAccess
  *   whether a repository administrator may change which teams reach that repository
  */
final case class EditOrganization(
    fullName: Option[String],
    description: Option[String],
    email: Option[String],
    website: Option[String],
    location: Option[String],
    visibility: Option[UserVisibility],
    repoAdminChangeTeamAccess: Option[Boolean],
):

  /** Replaces the display name. */
  def displayedAs(text: String): EditOrganization = copy(fullName = Some(text))

  /** Replaces the description. */
  def describedAs(text: String): EditOrganization = copy(description = Some(text))

  /** Replaces the published contact address. */
  def contactableAt(address: String): EditOrganization = copy(email = Some(address))

  /** Replaces the linked website. */
  def linkingTo(url: String): EditOrganization = copy(website = Some(url))

  /** Replaces the stated location. */
  def locatedAt(place: String): EditOrganization = copy(location = Some(place))

  /** Changes who may see the organisation. */
  def visibleAs(level: UserVisibility): EditOrganization = copy(visibility = Some(level))

  /** Turns "repository administrators may change team access" on or off. */
  def repositoryAdminsChangeTeamAccess(allowed: Boolean): EditOrganization =
    copy(repoAdminChangeTeamAccess = Some(allowed))

object EditOrganization:

  /** The edit that changes nothing, and the starting point for every other one. */
  val Empty: EditOrganization =
    EditOrganization(
      fullName                  = None,
      description               = None,
      email                     = None,
      website                   = None,
      location                  = None,
      visibility                = None,
      repoAdminChangeTeamAccess = None,
    )
