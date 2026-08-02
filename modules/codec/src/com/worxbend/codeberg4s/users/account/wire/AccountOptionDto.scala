package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.users.account.AvatarImage
import com.worxbend.codeberg4s.users.account.CreateRepository
import com.worxbend.codeberg4s.users.account.EmailAddress
import com.worxbend.codeberg4s.users.account.OAuth2ApplicationDefinition
import com.worxbend.codeberg4s.users.account.ObjectFormat
import com.worxbend.codeberg4s.users.account.TrustModel
import com.worxbend.codeberg4s.users.account.UpdateUserSettings

/** Every request body the `/user` account endpoints send, rendered in one place.
  *
  * Six of Forgejo's request models live here — `CreateOAuth2ApplicationOptions`, `UpdateUserAvatarOption`,
  * `CreateEmailOption`, `DeleteEmailOption`, `UserSettingsOptions` and `CreateRepoOption`. Objects rather than case
  * classes, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]] gives: a request model is a
  * rendering, not a value anyone holds. They share a file because each is small and because rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] — a wire spelling is written exactly once — is easier to check
  * when the spellings sit together.
  *
  * '''No credential is written down here.''' Unlike [[com.worxbend.codeberg4s.repositories.hooks.wire.HookOptionDto]]
  * and [[com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto]], nothing this file renders carries a
  * secret: the account endpoints that involve one — the Actions secret `PUT` and the hook bodies — reuse those
  * renderers rather than growing a second copy. The one credential in this group travels the other way, out of a
  * creation response; see [[com.worxbend.codeberg4s.users.account.ClientSecret]].
  */
private[codeberg4s] object AccountOptionDto:

  /** The wire key the avatar image is sent under. */
  val ImageKey: String = "image"

  /** The wire key both email bodies send their addresses under. Note that add and delete use the same one. */
  val EmailsKey: String = "emails"

  /** Renders an OAuth2 application definition as the JSON body of the create `POST` and of the update `PATCH`.
    *
    * All three properties are always emitted, including `redirect_uris` when it is empty and `confidential_client` when
    * it is `false`. That is not the "only what the caller set" rule the `PATCH` bodies elsewhere in this library
    * follow, and the difference is the endpoint's: `spec/swagger.v1.json` gives the update the '''create''' model,
    * which has no notion of an unmentioned key, so a property left out is stored as its zero value rather than left
    * alone. Emitting everything makes what the application ends up looking like a property of the request instead of of
    * the Forgejo version answering it — see [[com.worxbend.codeberg4s.users.account.OAuth2ApplicationDefinition]].
    */
  def renderApplication(definition: OAuth2ApplicationDefinition): String =
    ujson.write(
      ujson.Obj(
        "name"                -> ujson.Str(definition.name),
        "redirect_uris"       -> ujson.Arr.from(definition.redirectUris.map(ujson.Str.apply)),
        "confidential_client" -> ujson.Bool(definition.isConfidentialClient),
      )
    )

  /** Renders an avatar as the JSON body of `POST /user/avatar`.
    *
    * '''One string, not a multipart upload.''' The endpoint's body schema is `UpdateUserAvatarOption`, whose single
    * `image` property is documented as base64; see [[com.worxbend.codeberg4s.users.account.AvatarImage]] for the
    * argument and for what happens to a value that is not valid base64.
    */
  def renderAvatar(image: AvatarImage): String =
    ujson.write(ujson.Obj(ImageKey -> ujson.Str(image.base64)))

  /** Renders the addresses of `POST /user/emails` or of `DELETE /user/emails`.
    *
    * `CreateEmailOption` and `DeleteEmailOption` are two definitions with one property each, and the property has the
    * same name and the same type in both — so one renderer serves both, and the difference between adding and removing
    * lives entirely in the method. An empty vector would render `{"emails": []}`, which asks the instance to change
    * nothing; it never arrives here, because [[com.worxbend.codeberg4s.users.account.UserAccountApi.addEmails]] and its
    * counterpart take one address plus a varargs tail, so an empty request cannot be spelled.
    */
  def renderEmails(addresses: Vector[EmailAddress]): String =
    ujson.write(ujson.Obj(EmailsKey -> ujson.Arr.from(addresses.map(address => ujson.Str(address.value)))))

  /** Renders a settings change as the JSON body of `PATCH /user/settings`.
    *
    * '''Only what the caller set is emitted''', so [[com.worxbend.codeberg4s.users.account.UpdateUserSettings.Empty]]
    * renders as `{}` and changes nothing. A text field set to the empty string '''is''' emitted, because that is how a
    * value is cleared and it is a different request from not mentioning the key at all.
    */
  def renderSettings(command: UpdateUserSettings): String =
    val fields = List(
      command.fullName.map(value           => "full_name" -> ujson.Str(value)),
      command.website.map(value            => "website" -> ujson.Str(value)),
      command.location.map(value           => "location" -> ujson.Str(value)),
      command.description.map(value        => "description" -> ujson.Str(value)),
      command.pronouns.map(value           => "pronouns" -> ujson.Str(value)),
      command.language.map(value           => "language" -> ujson.Str(value)),
      command.theme.map(value              => "theme" -> ujson.Str(value)),
      command.diffViewStyle.map(value      => "diff_view_style" -> ujson.Str(value)),
      command.hidesEmail.map(value         => "hide_email" -> ujson.Bool(value)),
      command.hidesActivity.map(value      => "hide_activity" -> ujson.Bool(value)),
      command.hidesPronouns.map(value      => "hide_pronouns" -> ujson.Bool(value)),
      command.showsRepoUnitHints.map(value => "enable_repo_unit_hints" -> ujson.Bool(value)),
    ).flatten

    ujson.write(ujson.Obj.from(fields))

  /** Renders a repository creation as the JSON body of `POST /user/repos`.
    *
    * `name` is the model's one required property. The three booleans are always emitted, so what a created repository
    * looks like does not depend on the instance's defaults; everything else is emitted only when the caller stated it,
    * which is what leaves the instance's own template and branch defaults in place. See
    * [[com.worxbend.codeberg4s.users.account.CreateRepository]].
    */
  def renderRepository(command: CreateRepository): String =
    val fields = List(
      Some("name" -> ujson.Str(command.name.value)),
      command.description.map(value => "description" -> ujson.Str(value)),
      Some("private"   -> ujson.Bool(command.isPrivate)),
      Some("template"  -> ujson.Bool(command.isTemplate)),
      Some("auto_init" -> ujson.Bool(command.autoInit)),
      command.defaultBranch.map(branch  => "default_branch" -> ujson.Str(branch.value)),
      command.gitignores.map(value      => "gitignores" -> ujson.Str(value)),
      command.license.map(value         => "license" -> ujson.Str(value)),
      command.readme.map(value          => "readme" -> ujson.Str(value)),
      command.issueLabels.map(value     => "issue_labels" -> ujson.Str(value)),
      command.objectFormat.map(format   => "object_format_name" -> ujson.Str(format.wireValue)),
      command.trustModel.map(trustModel => "trust_model" -> ujson.Str(trustModel.wireValue)),
    ).flatten

    ujson.write(ujson.Obj.from(fields))
