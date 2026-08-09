package com.worxbend.codeberg4s.users.account

import java.time.Instant

/** One OAuth2 application registered by the authenticated account.
  *
  * '''Derived from `spec/swagger.v1.json`'s `OAuth2Application` definition, not from a captured response.''' The golden
  * harvest behind `modules/codec/test/resources/golden` was anonymous and every `/user/applications/oauth2` route
  * requires a token, so no fixture exists for this model. The field set and the nullability treatment are the spec read
  * literally under the rule `docs/HAZARDS.md` §1 forces on the whole API; the payloads asserted in the suites were
  * written by hand to match that definition and are not evidence that Forgejo sends exactly this.
  *
  * ==The secret is here, and it is almost always absent==
  *
  * [[clientSecret]] is present on exactly one response in the whole API: the `201` of `POST /user/applications/oauth2`.
  * A read of the same application — through [[com.worxbend.codeberg4s.users.account.UserApplicationApi.get]] or the
  * listing — carries no secret at all, because Forgejo stores it hashed. The `Option` is that fact and not a gap in the
  * payload; see [[ClientSecret]] for what a caller has to do about it, and note that its `toString` is a mask, so the
  * generated `toString` of this case class cannot print the credential either.
  *
  * @param id
  *   the instance-wide identifier every other application route takes
  * @param name
  *   the label shown to a user on the authorisation screen. Absent when the instance sent none
  * @param clientId
  *   the public half of the credentials, safe to embed in a client. Absent when the instance sent none
  * @param clientSecret
  *   the private half, present only on the creation response; see the class note
  * @param redirectUris
  *   the destinations an authorisation may come back to, in the order the instance listed them. Empty when the payload
  *   carried no `redirect_uris` key, `null`, or an empty array — the three are indistinguishable on the wire and mean
  *   the same thing here
  * @param isConfidentialClient
  *   whether the application can keep a secret, which decides whether Forgejo requires one at the token endpoint.
  *   `false` when the instance did not say, which is the safer reading: a public client is treated as unable to hold a
  *   credential
  * @param createdAt
  *   when the application was registered, absent when the instance sent no timestamp or the zero-time sentinel
  */
final case class OAuth2Application private[codeberg4s] (
    id: OAuth2ApplicationId,
    name: Option[String],
    clientId: Option[String],
    clientSecret: Option[ClientSecret],
    redirectUris: Vector[String],
    isConfidentialClient: Boolean,
    createdAt: Option[Instant],
):

  /** Whether this value carries the one and only copy of the client secret.
    *
    * `true` for the result of a creation, `false` for every read. A caller that persists credentials can branch on this
    * rather than on an `Option` match, which makes "we only get one chance at this" readable at the call site.
    */
  def carriesSecret: Boolean =
    clientSecret.isDefined
