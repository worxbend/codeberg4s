package com.worxbend.codeberg4s.repositories.actions

import java.time.Instant

/** An Actions secret, as much of one as any client is ever allowed to see.
  *
  * ==There is no value here, and that is the whole design==
  *
  * Forgejo's `Secret` model has exactly two properties, `name` and `created_at`. The material is write-only: it goes in
  * through `PUT /repos/{owner}/{repo}/actions/secrets/{secretname}` as a [[SecretValue]] and never comes back out, from
  * this endpoint or any other. Modelling that faithfully means this type has no value field at all — not an
  * `Option[SecretValue]` that is always `None`, which would invite a caller to believe some other call might fill it
  * in, and not a `String` that would be empty for reasons the caller has to look up.
  *
  * A caller who needs to know what a secret contains has to hold that knowledge outside the forge. That is not a
  * limitation of this library; it is the point of a secret store.
  *
  * '''Derived from `spec/swagger.v1.json`'s `Secret` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * @param name
  *   what addresses the secret. Forgejo upper-cases names, so this is usually not the spelling the caller wrote — see
  *   [[SecretName]]
  * @param createdAt
  *   when the secret was first set. Forgejo reports no modification time, so a secret that has been rewritten looks
  *   exactly like one that has not
  */
final case class ActionSecret private[codeberg4s] (
    name: SecretName,
    createdAt: Option[Instant],
)
