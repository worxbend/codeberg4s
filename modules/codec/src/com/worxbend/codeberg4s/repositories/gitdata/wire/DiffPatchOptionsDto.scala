package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.issues.wire.WireInstant
import com.worxbend.codeberg4s.repositories.gitdata.ApplyDiffPatch
import com.worxbend.codeberg4s.repositories.gitdata.GitAuthor

import java.time.Instant

/** Forgejo's `UpdateFileOptions` request model, as `POST /repos/{owner}/{repo}/diffpatch` consumes it.
  *
  * An object rather than a case class with a `Writer`, for the reason
  * [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]] gives.
  *
  * '''Only what the caller set is emitted.''' `branch`, `message`, `author` and the rest all mean "let the instance
  * decide" when absent, and sending `"message": ""` instead would suppress the generated commit message rather than
  * accept it. The two booleans are emitted only when `true`, since `false` is what the instance already assumes.
  *
  * `content` is emitted verbatim, without encoding — see
  * [[com.worxbend.codeberg4s.repositories.gitdata.ApplyDiffPatch]] for why this library refuses to guess whether the
  * diffpatch route wants base64. `from_path` is not emitted at all: it names the single file a rename moved, which a
  * patch spanning several files cannot answer, and the spec's own description ties it to the file-editing routes.
  *
  * `dates` is nested, matching Forgejo's `CommitDateOptions`, and appears only when the caller supplied both halves.
  */
private[codeberg4s] object DiffPatchOptionsDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: ApplyDiffPatch): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: ApplyDiffPatch): List[(String, JsonValue)] =
    List(
      Some("content" -> JsonValue.Str(command.content)),
      command.sha.map(value      => "sha" -> JsonValue.Str(value)),
      command.branch.map(target  => "branch" -> JsonValue.Str(target.value)),
      command.newBranch.map(name => "new_branch" -> JsonValue.Str(name.value)),
      command.message.map(text   => "message" -> JsonValue.Str(text)),
      command.author.map(who     => "author" -> person(who)),
      command.committer.map(who  => "committer" -> person(who)),
      dates(command.authorDate, command.committerDate),
      Option.when(command.signoff)("signoff"                                    -> JsonValue.Bool(true)),
      Option.when(command.forceOverwriteNewBranch)("force_overwrite_new_branch" -> JsonValue.Bool(true)),
    ).flatten

  private def person(who: GitAuthor): JsonValue =
    JsonValue.Obj("name" -> JsonValue.Str(who.name), "email" -> JsonValue.Str(who.email))

  /** The `dates` object, present only when both halves are, because
    * [[com.worxbend.codeberg4s.repositories.gitdata.ApplyDiffPatch.dated]] is the only way to set either.
    */
  private def dates(authored: Option[Instant], committed: Option[Instant]): Option[(String, JsonValue)] =
    authored.zip(committed).map: (author, committer) =>
      "dates" -> JsonValue.Obj(
        "author"    -> JsonValue.Str(WireInstant.render(author)),
        "committer" -> JsonValue.Str(WireInstant.render(committer)),
      )
