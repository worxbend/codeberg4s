package com.worxbend.codeberg4s.repositories.actions

/** What `POST /repos/{owner}/{repo}/actions/runners` hands back: a new runner, and the credential it needs.
  *
  * '''This value carries a credential.''' [[token]] is what the runner binary authenticates with, so this type is worth
  * treating like a password even though it is a plain case class: its `toString` is safe only because
  * [[RunnerRegistrationToken]] masks itself, which is exactly why that type is a final class and not an opaque alias.
  *
  * A separate type from [[ActionRunner]] rather than a widening of it, because the payloads have almost nothing in
  * common: the registration response is `{id, uuid, token}` and reports neither the runner's labels nor its status,
  * which have not been established yet — the machine has not connected.
  *
  * '''Derived from `spec/swagger.v1.json`'s `RegisterRunnerResponse` definition, not from a captured response'''; see
  * [[ActionArtifact]] for why.
  *
  * @param id
  *   the identifier the runner endpoints address the new runner by, absent when the instance did not report one
  * @param uuid
  *   the runner's own identifier, absent when the instance did not report one
  * @param token
  *   the one-shot registration credential. Required: a registration response without it registers nothing, so decoding
  *   fails rather than handing back a runner nobody can start
  */
final case class RegisteredRunner(
    id: Option[RunnerId],
    uuid: Option[String],
    token: RunnerRegistrationToken,
)
