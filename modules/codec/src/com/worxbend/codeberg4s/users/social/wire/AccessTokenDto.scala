package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.users.social.AccessToken
import com.worxbend.codeberg4s.users.social.AccessTokenId
import com.worxbend.codeberg4s.users.social.AccessTokenName
import com.worxbend.codeberg4s.users.social.CreatedAccessToken
import com.worxbend.codeberg4s.users.social.TokenScope

/** Forgejo's `AccessToken` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': both token endpoints require a token of
  * their own and the golden harvest was anonymous. All six declared properties are represented and all are `Option` or
  * an empty-by-default collection, per rule 2 of [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * ==This is the one DTO in the library that can hold a full user credential==
  *
  * `sha1` carries the token's plaintext material, and Forgejo populates it on exactly one response: the `201` of
  * `POST /users/{username}/tokens`. Every listing sends it empty, which
  * [[com.worxbend.codeberg4s.codec.JsonFields.text]] folds into `None`.
  *
  * Two things follow, and both are enforced rather than intended:
  *
  *   - [[toString]] is overridden to mask it. A DTO is normally a transparent case class and printing one is a
  *     debugging convenience; here that convenience would print a working credential the first time anyone logged a
  *     decoded body. The precedent is [[com.worxbend.codeberg4s.repositories.actions.wire.RegisteredRunnerDto]], which
  *     keeps a credential as a raw `String` and relies on nothing rendering it — this type does not rely on that;
  *   - the material leaves as [[com.worxbend.codeberg4s.auth.ApiToken]] and only through [[toCreatedAt]].
  *     [[toDomainAt]], which every listing uses, has nowhere to put it, so a token that arrived on a listing cannot
  *     reach the domain even if a Forgejo release started sending one.
  *
  * @param id
  *   the `id` key
  * @param name
  *   the `name` key
  * @param scopes
  *   the `scopes` key
  * @param sha1
  *   the `sha1` key — the plaintext credential; see the note above
  * @param tokenLastEight
  *   the `token_last_eight` key
  * @param repositories
  *   the `repositories` key
  * @param created
  *   the `created_at` key as a raw string
  */
final case class AccessTokenDto(
    id: Option[Long],
    name: Option[String],
    scopes: Vector[String],
    sha1: Option[String],
    tokenLastEight: Option[String],
    repositories: Vector[RepositoryMetaDto],
    created: Option[String],
):

  /** Every field except the credential, which is replaced by a constant mask.
    *
    * See the class note. A caller of this library never holds one of these; the override exists for the library's own
    * diagnostics, which are exactly where a credential would otherwise leak.
    */
  override def toString: String =
    val material = sha1.fold("None")(_ => s"Some(${ApiToken.Redacted})")

    s"AccessTokenDto($id,$name,$scopes,$material,$tokenLastEight,$repositories,$created)"

  /** Converts to the domain '''without''' the credential, reporting failure paths relative to `at`.
    *
    * This is what a listing uses, and it drops `sha1` unconditionally — see the class note.
    *
    * `id` is the one required field: it is the unambiguous way to address the token for deletion, and a token entry
    * without one can only be deleted by a name that two tokens may share. It goes through
    * [[com.worxbend.codeberg4s.users.social.AccessTokenId.from]], so a non-positive one is reported at `$.id`.
    *
    * '''`name`, `scopes` and `repositories` are all lenient.''' A name
    * [[com.worxbend.codeberg4s.users.social.AccessTokenName.from]] rejects becomes `None`, a scope this release does
    * not model becomes [[com.worxbend.codeberg4s.users.social.TokenScope.Other]] rather than a failure, and a
    * repository entry that does not yield a usable slug is dropped. None of the three is worth failing a page over: the
    * token is still addressable by its id, which is what a caller acts on.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, AccessToken] =
    Wire
      .validated(at, "id", id)(AccessTokenId.from)
      .map: identifier =>
        AccessToken(
          id           = identifier,
          name         = name.flatMap(value => AccessTokenName.from(value).toOption),
          scopes       = scopes.map(TokenScope.parse),
          lastEight    = tokenLastEight,
          repositories = slugs,
          createdAt    = Timestamps.parseOptional(created),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, AccessToken] =
    toDomainAt(JsonPath.Root)

  /** Converts to the domain '''with''' the credential, reporting failure paths relative to `at`.
    *
    * The only conversion that reads `sha1`, and the only one `POST /users/{username}/tokens` uses. The material is
    * required here for the reason [[com.worxbend.codeberg4s.repositories.actions.wire.RegisteredRunnerDto]] gives about
    * its own token: the entire point of the call is to obtain it, and a
    * [[com.worxbend.codeberg4s.users.social.CreatedAccessToken]] whose token was absent would be a value whose only use
    * is to be checked for emptiness. A blank one is rejected by [[com.worxbend.codeberg4s.auth.ApiToken.from]] and
    * reported at `$.sha1` — and that failure never echoes the rejected input, so a malformed credential does not travel
    * in the error either.
    */
  def toCreatedAt(at: JsonPath): Either[DecodeFailure, CreatedAccessToken] =
    for
      credential <- Wire.validated(at, "sha1", sha1)(ApiToken.from)
      details    <- toDomainAt(at)
    yield CreatedAccessToken(token = credential, details = details)

  /** [[toCreatedAt]] for a payload that is the whole response body. */
  def toCreated: Either[DecodeFailure, CreatedAccessToken] =
    toCreatedAt(JsonPath.Root)

  private def slugs: Vector[RepoSlug] =
    repositories.flatMap(_.toSlug)

object AccessTokenDto:

  /** Reads an `AccessToken` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[AccessTokenDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place.
    *
    * `repositories` reuses [[com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto]], because the wire shape here is
    * Forgejo's `RepositoryMeta` — the four-key object with a bare login string under `owner` — and not a `Repository`.
    * Handing it to [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] would read `owner` as absent and then
    * fail on a field that is right there in the JSON.
    */
  def fromFields(fields: JsonFields): AccessTokenDto =
    AccessTokenDto(
      id             = fields.number("id"),
      name           = fields.text("name"),
      scopes         = fields.texts("scopes"),
      sha1           = fields.text("sha1"),
      tokenLastEight = fields.text("token_last_eight"),
      repositories   = fields.nestedAll("repositories").map(RepositoryMetaDto.fromFields),
      created        = fields.text("created_at"),
    )

  /** Converts a decoded array of tokens, reporting the position of whichever element failed. Never reads `sha1`. */
  def toDomainAll(base: JsonPath, dtos: Vector[AccessTokenDto]): Either[DecodeFailure, Vector[AccessToken]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
