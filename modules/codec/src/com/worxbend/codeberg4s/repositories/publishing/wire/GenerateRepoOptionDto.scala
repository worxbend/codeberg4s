package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.repositories.publishing.GenerateRepository

/** Forgejo's `GenerateRepoOption` request model — the body of `POST /repos/{template_owner}/{template_repo}/generate`.
  *
  * An object rather than a case class, for the reason [[CreateReleaseOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * '''Only what the caller set is emitted.''' `owner` and `name` are the two properties the spec marks required and are
  * always present. The eight booleans are emitted only when `true`: Forgejo assumes `false` for all of them, so a
  * command that copies nothing renders as just the two required keys, which is exactly what it means.
  */
private[codeberg4s] object GenerateRepoOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: GenerateRepository): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: GenerateRepository): List[(String, ujson.Value)] =
    List(
      Some("owner" -> ujson.Str(command.owner.value)),
      Some("name"  -> ujson.Str(command.name.value)),
      command.description.map(text     => "description" -> ujson.Str(text)),
      command.defaultBranch.map(branch => "default_branch" -> ujson.Str(branch.value)),
      Option.when(command.isPrivate)("private"                          -> ujson.Bool(true)),
      Option.when(command.includesAvatar)("avatar"                      -> ujson.Bool(true)),
      Option.when(command.includesGitContent)("git_content"             -> ujson.Bool(true)),
      Option.when(command.includesGitHooks)("git_hooks"                 -> ujson.Bool(true)),
      Option.when(command.includesLabels)("labels"                      -> ujson.Bool(true)),
      Option.when(command.includesProtectedBranches)("protected_branch" -> ujson.Bool(true)),
      Option.when(command.includesTopics)("topics"                      -> ujson.Bool(true)),
      Option.when(command.includesWebhooks)("webhooks"                  -> ujson.Bool(true)),
    ).flatten
