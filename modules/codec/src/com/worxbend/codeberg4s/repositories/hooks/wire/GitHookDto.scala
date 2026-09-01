package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.hooks.{GitHook, GitHookName}

/** Forgejo's `GitHook` model — one script the instance runs when a push arrives.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  *
  * The definition declares exactly three properties, and `content` is a plain string: a Git hook's script is '''not'''
  * base64, unlike a wiki page's content. Nothing here is a credential, so unlike [[WebhookDto]] there is no field this
  * DTO refuses to read — although the script itself is arbitrary text an administrator wrote, and a caller that logs it
  * is logging whatever that administrator put in it.
  */
final case class GitHookDto(
    name: Option[String],
    isActive: Option[Boolean],
    content: Option[String],
) extends WireModel[GitHook]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `name` is required and goes through [[com.worxbend.codeberg4s.repositories.hooks.GitHookName.from]], because it is
    * the only thing that addresses the hook: an unnamed Git hook cannot be read back, edited or deleted. A name that
    * could forge a path is refused at the same place and reported the same way.
    *
    * `content` is read with [[com.worxbend.codeberg4s.codec.JsonFields.rawText]] rather than `text`, so a hook whose
    * script really is the empty string stays distinguishable from one that has no script at all — the `""`-for-absent
    * convention that holds for Forgejo's descriptive fields does not hold for a file's contents.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GitHook] =
    Wire
      .validated(at, "name", name)(GitHookName.from)
      .map(hookName => GitHook(name = hookName, isActive = isActive, content = content))

object GitHookDto:

  /** Reads a `GitHook` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[GitHookDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): GitHookDto =
    GitHookDto(
      name     = fields.text("name"),
      isActive = fields.boolean("is_active"),
      content  = fields.rawText("content"),
    )
