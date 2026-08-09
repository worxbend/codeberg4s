package com.worxbend.codeberg4s.users

import java.time.Instant

/** An SSH public key an account has registered with the instance.
  *
  * A public key is public data: it is what Forgejo hands to `git` to authorise a push, and both `GET /user/keys` and
  * `GET /users/{username}/keys` return it in full. Nothing here is a secret, and nothing here needs redacting — the
  * private half never leaves the account holder's machine and never appears in this API.
  *
  * '''No golden fixture backs this model.''' `docs/HAZARDS.md` and the fixture manifest record that every capture was
  * anonymous, and `/user/keys` needs a token while `/users/{u}/keys` was not among the 61 captured paths. The fields
  * below are therefore taken from `definitions.PublicKey` in the pinned spec, and — per the rule that the spec asserts
  * nothing about optionality — everything except [[id]] and [[key]] is optional. When a real capture lands, this model
  * is the first thing to check against it.
  *
  * @param id
  *   the instance-local numeric identifier, which is what `DELETE /user/keys/{id}` takes
  * @param key
  *   the key material as `git` would read it, for example `ssh-ed25519 AAAA… comment`. Required, because a key entry
  *   without it identifies nothing
  * @param title
  *   the label the account holder gave the key, absent when they gave none
  * @param fingerprint
  *   the instance's own fingerprint of [[key]], useful for matching against `ssh-keygen -lf` without re-hashing
  * @param keyType
  *   Forgejo's classification of the key, observed as `"user"` or `"deploy"` on other deployments; carried as text
  *   because the spec enumerates no values and inventing an enum from an unmeasured field would be a guess
  * @param owner
  *   the account the key belongs to; Forgejo populates it on `/users/{u}/keys` and may omit it elsewhere
  * @param isVerified
  *   whether the account holder proved possession of the private half, which Forgejo tracks separately from
  *   registration
  */
final case class PublicKey private[codeberg4s] (
    id: Long,
    key: String,
    title: Option[String],
    fingerprint: Option[String],
    keyType: Option[String],
    url: Option[String],
    owner: Option[User],
    isReadOnly: Boolean,
    isVerified: Boolean,
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
