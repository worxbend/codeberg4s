package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.repositories.RepoSlug

import java.time.Instant

/** The name an access token is listed under, and one of the two ways to address it for deletion.
  *
  * Validated as a URI path segment, because `DELETE /users/{username}/tokens/{token}` interpolates it: a value
  * containing `/` would reach an endpoint this API surface never offered. That is the same boundary
  * [[com.worxbend.codeberg4s.users.Username]] draws, and for the same reason.
  *
  * '''Names are not unique on their own.''' Forgejo scopes a token name to the owning account, and the web UI does not
  * stop two tokens from sharing one. Addressing a token by name is therefore ambiguous in a way addressing it by
  * [[AccessTokenId]] is not — see [[AccessTokenRef]] for what that costs.
  */
opaque type AccessTokenName = String

object AccessTokenName:

  /** The field name a rejected value is reported under. */
  private val Field: String = "accessTokenName"

  /** Parses a token name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..` — everything that would forge or corrupt a request
    * path. The dot segments need stating separately because they carry no slash, so the slash rule never sees them, and
    * they survive percent-encoding untouched.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"accessTokenName"` field
    */
  def from(value: String): Either[ValidationError, AccessTokenName] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.contains('/') then Left(ValidationError(Field, "must not contain a slash"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else if trimmed.equals(".") || trimmed.equals("..") then Left(ValidationError(Field, "must not be '.' or '..'"))
    else Right(trimmed)

  extension (name: AccessTokenName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name

/** How `DELETE /users/{username}/tokens/{token}` addresses the token to delete.
  *
  * The spec describes that path parameter as "token to be deleted, identified by ID and if not available by name", so
  * one segment carries two different kinds of value. Modelling the choice makes it visible at the call site — and it is
  * not a cosmetic distinction, because it decides whether the delete may be repeated:
  *
  *   - [[ById]] names a row the instance never reuses. Repeating the request converges: the token is gone, and a second
  *     attempt after a lost success answers `404`, which by then is true.
  *   - [[ByName]] names something a caller can recreate. If the first attempt succeeded, its response was lost, and a
  *     token of the same name was created in between, the retry deletes '''that''' token — a credential the caller may
  *     have just started using. Nothing in the response distinguishes the two outcomes.
  *
  * `UserTokenApi.delete` reads this and chooses the retry eligibility accordingly; see its Scaladoc.
  */
enum AccessTokenRef:

  /** Address the token by its row identifier. Unambiguous, and safe to repeat. */
  case ById(id: AccessTokenId)

  /** Address the token by its name. Ambiguous when two tokens share one, and not safe to repeat. */
  case ByName(name: AccessTokenName)

  /** The value to interpolate into the request path. */
  def pathSegment: String =
    this match
      case ById(id)     => id.value.toString
      case ByName(name) => name.value

/** An access token as `GET /users/{username}/tokens` reports it — everything about the token except the token.
  *
  * '''Derived from `spec/swagger.v1.json`'s `AccessToken`, not from a captured response''' — the path needs a token and
  * the golden harvest was anonymous.
  *
  * ==The material is not here, and cannot be==
  *
  * The wire model has a `sha1` property carrying the token's plaintext value, and Forgejo populates it on exactly one
  * response: the `201` of `POST /users/{username}/tokens`. Every listing sends it empty. This model therefore has no
  * field for it — not an `Option[ApiToken]` that is always `None`, which would invite a caller to believe some other
  * call might fill it in. The one response that does carry material is [[CreatedAccessToken]], which is a different
  * type for that reason alone.
  *
  * [[lastEight]] is what remains: the final eight characters, which Forgejo shows in its web UI so a human can tell two
  * tokens apart. Eight characters of a token are not a credential and are safe to log, but they are also not a
  * fingerprint — nothing stops two tokens from ending the same way.
  *
  * @param id
  *   the row identifier, and the unambiguous way to address the token for deletion
  * @param name
  *   the label the token was created under
  * @param scopes
  *   what the token may do, read through [[TokenScope.parse]] so a scope this release does not model still arrives
  * @param lastEight
  *   the final eight characters of the material, for telling two tokens apart. Not a credential
  * @param repositories
  *   the repositories the token is confined to. '''Empty means unrestricted''', not "no access": Forgejo sends `null`
  *   for a token that is not limited to a set of repositories, which is the common case
  * @param createdAt
  *   when the token was issued
  */
final case class AccessToken(
    id: AccessTokenId,
    name: Option[AccessTokenName],
    scopes: Vector[TokenScope],
    lastEight: Option[String],
    repositories: Vector[RepoSlug],
    createdAt: Option[Instant],
)

/** The `201` of `POST /users/{username}/tokens` — the one response in this API that carries a usable credential.
  *
  * ==Read this before using it==
  *
  * [[token]] is the plaintext value of a brand-new personal access token. It is returned '''once'''. No later call
  * retrieves it: `GET /users/{username}/tokens` sends the same token with its material empty, and there is no endpoint
  * that reveals it again. A caller who does not store it has to delete the token and create another.
  *
  * It is modelled as [[com.worxbend.codeberg4s.auth.ApiToken]] rather than as a `String`, for two reasons. The first is
  * that it is exactly what [[com.worxbend.codeberg4s.auth.Auth]] takes, so a caller minting a token for another
  * component hands this value straight over with no conversion and no opportunity to put it in a log line on the way.
  * The second is redaction: `ApiToken` renders itself as `***` from `toString`, from string interpolation, and
  * therefore from the generated `toString` of this case class. A `String` field would print in full the first time
  * anyone logged the result of a creation call.
  *
  * ==Where the material can and cannot reach==
  *
  * It reaches the caller and nothing else. [[com.worxbend.codeberg4s.CallContext]] carries a redacted URI and never a
  * response body; [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] snippets a body only on the failure path,
  * and a body that failed to decode produced no token; and the mask makes every accidental rendering safe even so.
  * `UserTokenApiSuite` asserts all of that rather than asserting the intention.
  *
  * @param token
  *   the credential, available on this response and never again
  * @param details
  *   everything a listing would also have shown: the identifier, the name, the scopes and the repository restriction
  */
final case class CreatedAccessToken(token: ApiToken, details: AccessToken)

/** What `POST /users/{username}/tokens` needs to mint a token.
  *
  * '''Derived from `spec/swagger.v1.json`'s `CreateAccessTokenOption`''', which declares `name` required and `scopes`
  * and `repositories` optional. No golden capture exists — the path needs a token.
  *
  * Built through [[CreateAccessToken.named]] and refined with [[granting]] and [[limitedTo]], so a value of this type
  * is always a request the instance can act on. The constructor, `apply` and `copy` are private for that reason.
  *
  * ==A token with no scopes is a token that can do nothing==
  *
  * Forgejo accepts an empty `scopes` array and issues the token anyway. The result authorises nothing, and the first
  * sign of that is a `403` from an unrelated call. This type does not prevent it — refusing would be inventing a rule
  * the instance does not have — but [[granting]] is how a caller states the intent, and [[scopes]] being empty is worth
  * asserting on before the request goes out.
  *
  * @param name
  *   the label the token is listed under
  * @param scopes
  *   what the token may do. Empty asks for a token that can do nothing; see the note above
  * @param repositories
  *   the repositories to confine the token to. Empty leaves it unconfined, which is Forgejo's default
  */
final case class CreateAccessToken private (
    name: AccessTokenName,
    scopes: Vector[TokenScope],
    repositories: Vector[RepoSlug],
):

  /** The same request, granting `granted` in addition to whatever it already granted.
    *
    * Duplicates are removed, so granting the same scope twice sends it once. Order is otherwise preserved, because a
    * rendered body that changes between two identical calls is a body no test can assert on.
    */
  def granting(granted: TokenScope*): CreateAccessToken =
    copy(scopes = (scopes ++ granted.toVector).distinct)

  /** The same request, confined to `repositories` in addition to whatever it was already confined to.
    *
    * Duplicates are removed. Calling this at all is what turns an unconfined token into a confined one; there is no way
    * back to unconfined except by building the request again.
    */
  def limitedTo(confined: RepoSlug*): CreateAccessToken =
    copy(repositories = (repositories ++ confined.toVector).distinct)

object CreateAccessToken:

  /** Describes a token to mint, granting nothing and confined to nothing.
    *
    * @param name
    *   the label to list the token under
    * @return
    *   the request, or whatever [[AccessTokenName.from]] rejected
    */
  def named(name: String): Either[ValidationError, CreateAccessToken] =
    AccessTokenName.from(name).map(label => CreateAccessToken(label, Vector.empty, Vector.empty))
