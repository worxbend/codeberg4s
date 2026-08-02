package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.organizations.CreateOrganization
import com.worxbend.codeberg4s.organizations.EditOrganization
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.users.account.AvatarImage

/** Forgejo's `CreateOrgOption`, `EditOrgOption`, `RenameOrgOption` and `UpdateUserAvatarOption` request models.
  *
  * One object for four models because they are the four bodies the organisation resource itself accepts and they share
  * a vocabulary — `full_name`, `description`, `email`, `website`, `location`, `visibility`,
  * `repo_admin_change_team_access` — and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire
  * spelling is written exactly once. An object rather than a case class, for the reason
  * [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]] gives: a request model is a rendering, not a value
  * anyone holds.
  *
  * '''Derived from `spec/swagger.v1.json`.''' No golden capture of any of these requests exists — every one of them
  * needs a token and `modules/codec/test/resources/golden` was harvested anonymously.
  *
  * ==The create emits only what it was told, unlike most creates in this library==
  *
  * [[com.worxbend.codeberg4s.repositories.admin.wire.RepositoryOptionDto.renderCreate]] emits every Boolean
  * unconditionally, so that what a created repository does is a property of the request rather than of the Forgejo
  * version answering it. This one does not, and the difference is in the models: `CreateRepoOption`'s booleans are
  * plain, while `CreateOrgOption.repo_admin_change_team_access` is a setting whose instance-wide default an operator
  * may have changed on purpose. Asserting `false` for a caller who said nothing would silently override that. It is
  * therefore an `Option[Boolean]` on
  * [[com.worxbend.codeberg4s.organizations.CreateOrganization.repoAdminChangeTeamAccess]] and is emitted only when set.
  *
  * ==`username` is the create's name key==
  *
  * Forgejo stores organisations in the user table, so `CreateOrgOption` spells the handle `username`. It is an
  * [[com.worxbend.codeberg4s.organizations.OrgName]] on the way in; see
  * [[com.worxbend.codeberg4s.organizations.CreateOrganization]] for why.
  */
private[codeberg4s] object OrganizationOptionDto:

  /** The wire key the create sends the organisation handle under. */
  val UsernameKey: String = "username"

  /** The wire key the rename sends the new handle under. */
  val NewNameKey: String = "new_name"

  /** The wire key the avatar upload sends its base64 payload under. */
  val ImageKey: String = "image"

  /** The wire key of the display name, on both the create and the edit. */
  val FullNameKey: String = "full_name"

  /** The wire key deciding whether repository administrators may change team access. */
  val RepoAdminChangeTeamAccessKey: String = "repo_admin_change_team_access"

  /** Renders `command` as the JSON body to `POST /orgs`.
    *
    * `username` is the model's only required property and is always emitted; everything else appears only when the
    * caller set it. See the object note for why the Boolean is not forced.
    */
  def renderCreate(command: CreateOrganization): String =
    val fields = List(
      Some(UsernameKey -> ujson.Str(command.name.value)),
      command.fullName.map(text                  => FullNameKey -> ujson.Str(text)),
      command.description.map(text               => "description" -> ujson.Str(text)),
      command.email.map(address                  => "email" -> ujson.Str(address)),
      command.website.map(url                    => "website" -> ujson.Str(url)),
      command.location.map(place                 => "location" -> ujson.Str(place)),
      command.visibility.map(level               => "visibility" -> ujson.Str(level.wireName)),
      command.repoAdminChangeTeamAccess.map(flag => RepoAdminChangeTeamAccessKey -> ujson.Bool(flag)),
    ).flatten

    ujson.write(ujson.Obj.from(fields))

  /** Renders `command` as the JSON body to `PATCH /orgs/{org}`.
    *
    * Emits only what the command set, so [[com.worxbend.codeberg4s.organizations.EditOrganization.Empty]] renders as
    * `{}` — a well-formed request that changes nothing.
    */
  def renderEdit(command: EditOrganization): String =
    val fields = List(
      command.fullName.map(text                  => FullNameKey -> ujson.Str(text)),
      command.description.map(text               => "description" -> ujson.Str(text)),
      command.email.map(address                  => "email" -> ujson.Str(address)),
      command.website.map(url                    => "website" -> ujson.Str(url)),
      command.location.map(place                 => "location" -> ujson.Str(place)),
      command.visibility.map(level               => "visibility" -> ujson.Str(level.wireName)),
      command.repoAdminChangeTeamAccess.map(flag => RepoAdminChangeTeamAccessKey -> ujson.Bool(flag)),
    ).flatten

    ujson.write(ujson.Obj.from(fields))

  /** Renders `RenameOrgOption` — the body of `POST /orgs/{org}/rename`.
    *
    * One key, always emitted: the model declares `new_name` required, and there is no other property. The value is an
    * already-validated [[com.worxbend.codeberg4s.organizations.OrgName]], so what travels here can be a path segment
    * afterwards — which matters, because after this call it '''is''' the path segment every other operation uses.
    */
  def renderRename(newName: OrgName): String =
    ujson.write(ujson.Obj(NewNameKey -> ujson.Str(newName.value)))

  /** Renders `UpdateUserAvatarOption` — the body of `POST /orgs/{org}/avatar`.
    *
    * One key, always emitted. The value is already base64 by construction; see
    * [[com.worxbend.codeberg4s.users.account.AvatarImage]] for why the encoding happens there and not here, and
    * [[com.worxbend.codeberg4s.organizations.OrganizationApi.updateAvatar]] for why that type and not a second one.
    */
  def renderAvatar(image: AvatarImage): String =
    ujson.write(ujson.Obj(ImageKey -> ujson.Str(image.base64)))
