package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.repositories.Repository

import java.time.Instant

/** An SSH key that grants a machine — not a person — access to one repository.
  *
  * '''No golden fixture backs this model.''' `golden/MANIFEST.md` records that every capture was taken anonymously and
  * `GET /repos/{owner}/{repo}/keys` requires a token, so the nine fields below are exactly the properties of
  * `definitions.DeployKey` in `spec/swagger.v1.json`. Per `docs/HAZARDS.md` §1 the spec asserts nothing about
  * optionality, so every field is treated as absent-able and the conversion supplies the reading below.
  *
  * ==[[key]] is not a secret, and is deliberately not redacted==
  *
  * Elsewhere in this library a credential-shaped value hides itself:
  * `com.worxbend.codeberg4s.repositories.actions.SecretValue` and
  * `com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken` both mask themselves in every rendering path,
  * and an Actions secret has no read model at all. None of that applies here, and the difference is worth stating so
  * the next reader does not wonder whether it was an oversight.
  *
  * [[key]] is '''public-key''' material — the `ssh-ed25519 AAAA… comment` half that is meant to be published. It is
  * what `git` is handed to authorise a push, it is what the instance stores and returns in full on every read, and
  * anyone who can read a repository's deploy keys can already read this. The private half never enters this API. So the
  * value is logged, printed and compared like any other string, exactly as
  * `com.worxbend.codeberg4s.users.PublicKey.key` is. What '''is''' sensitive about a deploy key is not the material but
  * the grant: see [[isReadOnly]].
  *
  * @param id
  *   the identifier `DELETE /repos/{owner}/{repo}/keys/{id}` takes. Required, and '''not''' [[keyId]]
  * @param key
  *   the key material as `git` reads it. Required — a deploy key entry without it authorises nothing and identifies
  *   nothing
  * @param keyId
  *   the identifier of the underlying SSH key row, which is what the listing's `key_id` filter matches. A different
  *   number from [[id]] for the same key; see [[DeployKeyId]]
  * @param title
  *   the label whoever added the key gave it, absent when they gave none
  * @param fingerprint
  *   the instance's own fingerprint of [[key]], for matching against `ssh-keygen -lf` without re-hashing
  * @param repository
  *   the repository the key grants access to, as Forgejo embeds it. Absent on a payload that omitted it
  * @param isReadOnly
  *   whether the key may only fetch. '''An absent flag becomes `false`''' — that is, read-write — which is the
  *   permissive reading and therefore the conservative one for anyone auditing what a repository's keys can do: this
  *   model may over-state a key's power, never under-state it. Forgejo sends the field on every real payload, so the
  *   case is theoretical
  */
final case class DeployKey(
    id: DeployKeyId,
    key: String,
    keyId: Option[Long],
    title: Option[String],
    fingerprint: Option[String],
    url: Option[String],
    repository: Option[Repository],
    isReadOnly: Boolean,
    createdAt: Option[Instant],
)
