package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.issues.CreateIssue

/** Forgejo's `CreateIssueOption` request model — the body of `POST /repos/{owner}/{repo}/issues`.
  *
  * An object rather than a case class with a `Writer`, unlike every response DTO in this module, because a request body
  * is only ever '''written'''. A case class in between would have to be built and then rendered, and the interesting
  * decision — which keys appear at all — would still live in the rendering. Putting it in one place keeps it visible.
  *
  * '''Only what the caller set is emitted.''' `CreateIssueOption` declares one required property, `title`; the rest are
  * optional, and an absent key means "let the instance decide". Sending `"body": ""` or `"milestone": 0` instead would
  * assert something the caller never said.
  *
  * `assignees` and `labels` are emitted only when non-empty, for the same reason: an explicit `[]` is a statement, and
  * a caller who never called [[com.worxbend.codeberg4s.issues.CreateIssue.labelled]] made no statement.
  */
private[codeberg4s] object CreateIssueOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateIssue): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: CreateIssue): List[(String, ujson.Value)] =
    List(
      Some("title" -> ujson.Str(command.title)),
      command.body.map(text => "body" -> ujson.Str(text)),
      Option.when(command.assignees.nonEmpty)("assignees" -> WireNumbers.strings(command.assignees)),
      Option.when(command.labels.nonEmpty)("labels"       -> WireNumbers.identifiers(command.labels.map(_.value))),
      command.milestone.map(id   => "milestone" -> WireNumbers.identifier(id.value)),
      command.dueDate.map(moment => "due_date" -> ujson.Str(WireInstant.render(moment))),
      command.ref.map(reference  => "ref" -> ujson.Str(reference)),
      Option.when(command.closed)("closed" -> ujson.Bool(true)),
    ).flatten
