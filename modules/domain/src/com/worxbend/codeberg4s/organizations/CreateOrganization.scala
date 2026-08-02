package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.users.UserVisibility

/** What `POST /orgs` is told — Forgejo's `CreateOrgOption`.
  *
  * {{{
  * OrgName.from("worxbend").map: handle =>
  *   CreateOrganization
  *     .named(handle)
  *     .displayedAs("Worxbend")
  *     .describedAs("tools we keep having to write twice")
  *     .visibleAs(UserVisibility.Limited)
  * }}}
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured request.''' Creating an organisation needs a token and
  * `modules/codec/test/resources/golden` was harvested anonymously, so no fixture backs this shape. The eight fields
  * below are the eight properties the pinned spec declares, and `username` is the one it marks `required`.
  *
  * ==The handle is an [[OrgName]], and it is what the whole group takes afterwards==
  *
  * The wire key is `username`, because Forgejo stores organisations in the user table — the same reason
  * `GET /users/{name}` answers for an organisation. It is nevertheless an [[OrgName]] here and not a
  * [[com.worxbend.codeberg4s.users.Username]]: what is being created is an organisation, and the value this command
  * carries is the value every other operation in the group will be handed. See [[OrgName]] for why the three
  * name-shaped types are kept apart.
  *
  * ==Nothing here can be trusted to survive==
  *
  * [[OrganizationApi.rename]] exists, so the handle chosen here is not an identifier. Code that stores the created
  * organisation should store [[Organization.id]] alongside it.
  *
  * @param name
  *   the handle the organisation will answer to, sent as `username`
  * @param fullName
  *   the display name, absent to leave it blank
  * @param description
  *   the description shown on the organisation's page
  * @param email
  *   the contact address to publish
  * @param website
  *   the website to link
  * @param location
  *   the location to show
  * @param visibility
  *   who may see the organisation. Absent leaves Forgejo's default, which the spec documents as `public`. The type is
  *   [[com.worxbend.codeberg4s.users.UserVisibility]] because Forgejo has exactly one `VisibleType`; see
  *   [[Organization]] for why that enum is named for users and is not narrower than the wire type
  * @param repoAdminChangeTeamAccess
  *   whether a repository administrator may change which teams reach that repository. Absent leaves the instance's
  *   default rather than asserting `false`
  */
final case class CreateOrganization(
    name: OrgName,
    fullName: Option[String],
    description: Option[String],
    email: Option[String],
    website: Option[String],
    location: Option[String],
    visibility: Option[UserVisibility],
    repoAdminChangeTeamAccess: Option[Boolean],
):

  /** Sets the display name. */
  def displayedAs(text: String): CreateOrganization = copy(fullName = Some(text))

  /** Sets the description. */
  def describedAs(text: String): CreateOrganization = copy(description = Some(text))

  /** Publishes a contact address. */
  def contactableAt(address: String): CreateOrganization = copy(email = Some(address))

  /** Links a website. */
  def linkingTo(url: String): CreateOrganization = copy(website = Some(url))

  /** States a location. */
  def locatedAt(place: String): CreateOrganization = copy(location = Some(place))

  /** Chooses who may see the organisation. */
  def visibleAs(level: UserVisibility): CreateOrganization = copy(visibility = Some(level))

  /** States whether repository administrators may change team access, rather than leaving the instance's default. */
  def repositoryAdminsChangeTeamAccess(allowed: Boolean): CreateOrganization =
    copy(repoAdminChangeTeamAccess = Some(allowed))

object CreateOrganization:

  /** Starts a command from the one thing Forgejo insists on.
    *
    * Cannot fail: the argument is an already-validated [[OrgName]], so there is nothing left for this constructor to
    * check. Whether the instance will '''accept''' the handle — its own length and character rules, and whether it is
    * already taken — is the instance's judgement and arrives as a `422`.
    */
  def named(name: OrgName): CreateOrganization =
    CreateOrganization(
      name                      = name,
      fullName                  = None,
      description               = None,
      email                     = None,
      website                   = None,
      location                  = None,
      visibility                = None,
      repoAdminChangeTeamAccess = None,
    )
