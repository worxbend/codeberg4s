package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ValidationError

/** What both `POST /user/applications/oauth2` and `PATCH /user/applications/oauth2/{id}` are told.
  *
  * {{{
  * OAuth2ApplicationDefinition
  *   .named("deploy-bot")
  *   .redirectingTo("https://ci.example/oauth/callback")
  *   .confidential
  * }}}
  *
  * ==One type, because the API has one model==
  *
  * `spec/swagger.v1.json` names `CreateOAuth2ApplicationOptions` as the body schema of the create '''and''' of the
  * update, and the update declares it `required`. There is no partial-update model here and no `EditHookOption`-style
  * "only what you mention changes": the `PATCH` states the application's whole definition and whatever it does not name
  * is reset to the model's zero value. Splitting this into a create command and an edit command would invent a
  * distinction the API does not have, and an edit command with optional fields would promise a merge Forgejo does not
  * perform.
  *
  * That is also why [[com.worxbend.codeberg4s.users.account.UserApplicationApi.update]] is a replacement in its
  * Scaladoc rather than a patch, and why it is never retried — see there.
  *
  * @param name
  *   the label shown on the authorisation screen. Required by Forgejo in practice even though the model declares no
  *   `required` list, which is why [[OAuth2ApplicationDefinition.named]] is the only way to build one
  * @param redirectUris
  *   the destinations an authorisation may come back to. Empty registers an application with none, which Forgejo
  *   accepts and which no authorisation flow can then use — it is a legitimate thing to ask for and is sent as written
  * @param isConfidentialClient
  *   whether the application can keep a secret. `false` is the model's zero value and this type's default, which
  *   registers a public client
  */
final case class OAuth2ApplicationDefinition(
    name: String,
    redirectUris: Vector[String],
    isConfidentialClient: Boolean,
):

  /** Sets the redirect destinations, replacing whatever the definition carried.
    *
    * Repeated entries are kept as written rather than de-duplicated: deciding that two URIs a caller wrote are one is
    * not a decision a client library should make, and Forgejo is free to collapse them itself.
    */
  def redirectingTo(uris: String*): OAuth2ApplicationDefinition =
    copy(redirectUris = uris.toVector)

  /** Registers the application as a confidential client — one that can hold its [[ClientSecret]] safely. */
  def confidential: OAuth2ApplicationDefinition =
    copy(isConfidentialClient = true)

  /** Registers the application as a public client — a native binary or a single-page app, which holds no secret. */
  def publicClient: OAuth2ApplicationDefinition =
    copy(isConfidentialClient = false)

object OAuth2ApplicationDefinition:

  /** Starts a definition from the one value that has to be there.
    *
    * Trims the name and rejects a blank one: an application with no name is indistinguishable from every other on the
    * authorisation screen, and finding that out after a round trip is strictly worse than finding it out here.
    *
    * @return
    *   the definition, or a [[ValidationError]] on the `"oauth2ApplicationName"` field
    */
  def named(name: String): Either[ValidationError, OAuth2ApplicationDefinition] =
    val trimmed = name.trim

    if trimmed.isEmpty then Left(ValidationError("oauth2ApplicationName", "must not be blank"))
    else
      Right(
        OAuth2ApplicationDefinition(
          name                 = trimmed,
          redirectUris         = Vector.empty,
          isConfidentialClient = false,
        )
      )
