package com.worxbend.codeberg4s.users.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.UserVisibility

/** Forgejo's `User` model, field for field.
  *
  * Every key observed on `golden/user/user-single.json`, `golden/user/user-single-org-shaped.json` and the elements of
  * `golden/user/user-search.json` is present, including the two the spec does not declare (`username`, which duplicates
  * `login`) and the two that describe the instance's auth source (`loginName`, `sourceId`). Keeping them costs nothing
  * and means the DTO can be diffed against a captured payload without a mental exception list.
  *
  * `lastLogin` and `created` stay as raw strings here; [[com.worxbend.codeberg4s.codec.Timestamps]] turns them into
  * instants during conversion, where the zero-time sentinel is folded into absence.
  */
final case class UserDto(
    id: Option[Long],
    login: Option[String],
    loginName: Option[String],
    sourceId: Option[Long],
    fullName: Option[String],
    email: Option[String],
    avatarUrl: Option[String],
    htmlUrl: Option[String],
    language: Option[String],
    isAdmin: Option[Boolean],
    lastLogin: Option[String],
    created: Option[String],
    restricted: Option[Boolean],
    active: Option[Boolean],
    prohibitLogin: Option[Boolean],
    location: Option[String],
    pronouns: Option[String],
    website: Option[String],
    description: Option[String],
    visibility: Option[String],
    followersCount: Option[Long],
    followingCount: Option[Long],
    starredReposCount: Option[Long],
    username: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Fails only on `id` and `login`. Those two are what every consumer of an embedded user needs — a login is what
    * becomes an [[com.worxbend.codeberg4s.repositories.Owner]], and an id is what distinguishes two accounts after a
    * rename. Everything else is genuinely optional and stays optional.
    *
    * Counts absent from the payload become `0` rather than failing: a reduced embedded user carries no
    * `followers_count`, and reading that as "zero followers" is the same answer the API would give. `visibility` that
    * is unrecognised becomes `None`, per [[com.worxbend.codeberg4s.users.UserVisibility.parse]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, User] =
    for
      identifier <- Wire.required(at, "id", id)
      handle     <- Wire.required(at, "login", login)
    yield User(
      id                       = identifier,
      login                    = handle,
      fullName                 = fullName,
      email                    = email,
      avatarUrl                = avatarUrl,
      htmlUrl                  = htmlUrl,
      language                 = language,
      location                 = location,
      pronouns                 = pronouns,
      website                  = website,
      description              = description,
      visibility               = visibility.flatMap(UserVisibility.parse),
      isAdmin                  = isAdmin.getOrElse(false),
      isActive                 = active.getOrElse(false),
      isRestricted             = restricted.getOrElse(false),
      isProhibitedFromLogin    = prohibitLogin.getOrElse(false),
      followersCount           = followersCount.getOrElse(0L),
      followingCount           = followingCount.getOrElse(0L),
      starredRepositoriesCount = starredReposCount.getOrElse(0L),
      createdAt                = Timestamps.parseOptional(created),
      lastLoginAt              = Timestamps.parseOptional(lastLogin),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, User] =
    toDomainAt(JsonPath.Root)

object UserDto:

  /** Reads a `User` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[UserDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Used both by the reader above and by every DTO that embeds a user, so the
    * field spellings exist in exactly one place.
    */
  def fromFields(fields: JsonFields): UserDto =
    UserDto(
      id                = fields.number("id"),
      login             = fields.text("login"),
      loginName         = fields.text("login_name"),
      sourceId          = fields.number("source_id"),
      fullName          = fields.text("full_name"),
      email             = fields.text("email"),
      avatarUrl         = fields.text("avatar_url"),
      htmlUrl           = fields.text("html_url"),
      language          = fields.text("language"),
      isAdmin           = fields.boolean("is_admin"),
      lastLogin         = fields.text("last_login"),
      created           = fields.text("created"),
      restricted        = fields.boolean("restricted"),
      active            = fields.boolean("active"),
      prohibitLogin     = fields.boolean("prohibit_login"),
      location          = fields.text("location"),
      pronouns          = fields.text("pronouns"),
      website           = fields.text("website"),
      description       = fields.text("description"),
      visibility        = fields.text("visibility"),
      followersCount    = fields.number("followers_count"),
      followingCount    = fields.number("following_count"),
      starredReposCount = fields.number("starred_repos_count"),
      username          = fields.text("username"),
    )
