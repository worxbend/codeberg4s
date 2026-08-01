package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.issues.CreateComment

/** Forgejo's `CreateIssueCommentOption` request model — the body of
  * `POST /repos/{owner}/{repo}/issues/{index}/comments`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives.
  *
  * The model declares two properties, `body` and `updated_at`, of which only `body` is required.
  * [[com.worxbend.codeberg4s.issues.CreateComment]] does not offer `updated_at` and so nothing here emits it: it exists
  * for backdating an imported comment, which needs a privileged token and belongs to an import tool rather than to this
  * API surface.
  */
private[codeberg4s] object CreateIssueCommentOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateComment): String =
    ujson.write(ujson.Obj("body" -> ujson.Str(command.body)))
