package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.users.social.{CreateAccessToken, RemoteFollowTarget, TokenScope}

/** Forgejo's `CreateAccessTokenOption` request model — the body of `POST /users/{username}/tokens`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives.
  *
  * `name` is the model's single required property and is always emitted. `scopes` and `repositories` are emitted only
  * when the caller asked for them: an empty `scopes` array is not the same request as no `scopes` key, and sending an
  * empty `repositories` array to an instance that reads it as "confined to nothing" would mint a token that can reach
  * no repository at all.
  *
  * ==Nothing secret is written here==
  *
  * Unlike [[com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto]], this renderer carries no credential.
  * The token's material does not travel on the request — the instance generates it — which is why it exists only on the
  * response and why [[AccessTokenDto]], not this object, is where the redaction discipline lives.
  *
  * '''Derived from `spec/swagger.v1.json`''': no golden capture exists, since the endpoint needs a token.
  */
private[codeberg4s] object CreateAccessTokenOptionDto:

  /** The wire key the token's label is sent under. */
  val NameKey: String = "name"

  /** The wire key the permission list is sent under. */
  val ScopesKey: String = "scopes"

  /** The wire key the repository restriction is sent under. */
  val RepositoriesKey: String = "repositories"

  /** The wire key of a repository target's owner, inside [[RepositoriesKey]]. */
  val OwnerKey: String = "owner"

  /** The wire key of a repository target's name, inside [[RepositoriesKey]]. */
  val RepoNameKey: String = "name"

  /** Renders `command` as the JSON body to `POST`.
    *
    * Scopes are emitted through [[com.worxbend.codeberg4s.users.social.TokenScope.wireValue]] in the order the caller
    * granted them, so a rendered body is reproducible and a test can assert on it.
    */
  def render(command: CreateAccessToken): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: CreateAccessToken): List[(String, JsonValue)] =
    List(
      Some(NameKey                                               -> JsonValue.Str(command.name.value)),
      Option.when(command.scopes.nonEmpty)(ScopesKey             -> scopes(command.scopes)),
      Option.when(command.repositories.nonEmpty)(RepositoriesKey -> targets(command.repositories)),
    ).flatten

  private def scopes(granted: Vector[TokenScope]): JsonValue =
    JsonValue.Arr.from(granted.map(scope => JsonValue.Str(scope.wireValue)))

  /** Each restriction as Forgejo's `RepoTargetOption` — an `{owner, name}` object, not a `full_name` string. */
  private def targets(confined: Vector[RepoSlug]): JsonValue =
    JsonValue.Arr.from(
      confined.map(slug =>
        JsonValue.Obj(
          OwnerKey    -> JsonValue.Str(slug.owner.value),
          RepoNameKey -> JsonValue.Str(slug.name.value),
        )
      )
    )

/** Forgejo's `APRemoteFollowOption` request model — the body of `POST /user/activitypub/follow`.
  *
  * `target` is the model's only property, and it is emitted unconditionally even though the spec declares nothing
  * required: a follow request naming no target is a request the endpoint can only reject.
  *
  * '''Derived from `spec/swagger.v1.json`''': no golden capture exists, since the endpoint needs a token.
  */
private[codeberg4s] object RemoteFollowOptionDto:

  /** The wire key the remote actor is sent under. */
  val TargetKey: String = "target"

  /** Renders `target` as the JSON body to `POST`. */
  def render(target: RemoteFollowTarget): String =
    Json.render(JsonValue.Obj(TargetKey -> JsonValue.Str(target.value)))
