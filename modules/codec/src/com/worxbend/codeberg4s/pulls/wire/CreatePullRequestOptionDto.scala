package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.issues.wire.WireInstant
import com.worxbend.codeberg4s.issues.wire.WireNumbers
import com.worxbend.codeberg4s.pulls.CreatePullRequest

/** Forgejo's `CreatePullRequestOption` request model — the body of `POST /repos/{owner}/{repo}/pulls`.
  *
  * An object rather than a case class with a `Writer`, unlike every response DTO in this module, because a request body
  * is only ever '''written'''. A case class in between would have to be built and then rendered, and the interesting
  * decision — which keys appear at all — would still live in the rendering.
  *
  * '''Only what the caller set is emitted.''' `CreatePullRequestOption` declares no required property at all, but a
  * pull request without `title`, `head` and `base` is not one Forgejo will open, so those three are always present —
  * [[com.worxbend.codeberg4s.pulls.CreatePullRequest.of]] has already refused to build a command without them. The rest
  * are optional, and an absent key means "let the instance decide"; sending `"body": ""` or `"milestone": 0` instead
  * would assert something the caller never said.
  *
  * `assignees` and `labels` are emitted only when non-empty, for the same reason: an explicit `[]` is a statement, and
  * a caller who never called [[com.worxbend.codeberg4s.pulls.CreatePullRequest.labelled]] made no statement.
  *
  * [[com.worxbend.codeberg4s.issues.wire.WireInstant]] and [[com.worxbend.codeberg4s.issues.wire.WireNumbers]] are the
  * issue wave's, reused rather than copied per `docs/LEDGER.md`; both are `private[codeberg4s]` and both are listed
  * there as candidates for promotion into `codec`.
  */
private[codeberg4s] object CreatePullRequestOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreatePullRequest): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: CreatePullRequest): List[(String, ujson.Value)] =
    List(
      Some("title" -> ujson.Str(command.title)),
      Some("head"  -> ujson.Str(command.head.value)),
      Some("base"  -> ujson.Str(command.base.value)),
      command.body.map(text => "body" -> ujson.Str(text)),
      Option.when(command.assignees.nonEmpty)("assignees" -> WireNumbers.strings(command.assignees)),
      Option.when(command.labels.nonEmpty)("labels"       -> WireNumbers.identifiers(command.labels.map(_.value))),
      command.milestone.map(id   => "milestone" -> WireNumbers.identifier(id.value)),
      command.dueDate.map(moment => "due_date" -> ujson.Str(WireInstant.render(moment))),
    ).flatten
