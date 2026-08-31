package com.worxbend.codeberg4s.repositories.access.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.access.CollaboratorPermission

/** Forgejo's `AddCollaboratorOption` request model — the body of
  * `PUT /repos/{owner}/{repo}/collaborators/{collaborator}`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds.
  *
  * ==The one property is always emitted==
  *
  * `permission` is the model's only property and the spec marks it optional, but this renderer takes a
  * [[com.worxbend.codeberg4s.repositories.access.CollaboratorPermission]] and always writes it. Omitting it would leave
  * Forgejo to pick a level, and "whatever the instance defaults to" is not something this library will send on a
  * caller's behalf on the endpoint that decides who may push to a repository. The type makes the wrong level
  * unspellable and this renderer makes the absent level unspellable; between them, what reaches the wire is exactly one
  * of the three values the spec's own `enum` declares.
  */
private[codeberg4s] object AddCollaboratorOptionDto:

  /** Renders `permission` as the JSON body to `PUT`. */
  def render(permission: CollaboratorPermission): String =
    Json.render(JsonValue.Obj(CollaboratorWire.Permission -> JsonValue.Str(permission.wireName)))
