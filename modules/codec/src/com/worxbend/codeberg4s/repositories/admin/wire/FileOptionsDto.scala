package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.issues.wire.WireInstant
import com.worxbend.codeberg4s.repositories.admin.ChangeFiles
import com.worxbend.codeberg4s.repositories.admin.CommitDates
import com.worxbend.codeberg4s.repositories.admin.CommitIdentity
import com.worxbend.codeberg4s.repositories.admin.CommitOptions
import com.worxbend.codeberg4s.repositories.admin.CreateFile
import com.worxbend.codeberg4s.repositories.admin.DeleteFile
import com.worxbend.codeberg4s.repositories.admin.FileOperation
import com.worxbend.codeberg4s.repositories.admin.UpdateFile

/** Forgejo's four contents-write request models — `CreateFileOptions`, `UpdateFileOptions`, `DeleteFileOptions` and
  * `ChangeFilesOptions`.
  *
  * One object for four models because they share eight properties out of ten, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once. The shared part is
  * [[commitFields]], which renders [[com.worxbend.codeberg4s.repositories.admin.CommitOptions]]; each `render*` adds
  * only what its own model declares.
  *
  * ==Content is Base64 and the type says so==
  *
  * `content` is described by the spec as "content must be base64 encoded" on both the create and the update.
  * [[com.worxbend.codeberg4s.repositories.admin.FileBytes]] is what guarantees that here: the renderer emits
  * `FileBytes.base64` verbatim and has no way to be handed raw text by mistake.
  *
  * ==The sha guard is never omitted==
  *
  * `sha` is required by the spec on the update and the delete, and by
  * [[com.worxbend.codeberg4s.repositories.admin.UpdateFile]] and
  * [[com.worxbend.codeberg4s.repositories.admin.DeleteFile]] here, so it is always emitted. It is what makes a
  * concurrent edit a `409` instead of a silent overwrite; see those two types for the argument.
  */
private[codeberg4s] object FileOptionsDto:

  /** The wire key the Base64 file content travels under, on the create, the update and each batch operation. */
  val ContentKey: String = "content"

  /** The wire key the expected blob id travels under. */
  val ShaKey: String = "sha"

  /** The wire key the batch's operation list travels under. */
  val FilesKey: String = "files"

  /** Renders `command` as the JSON body to `POST /repos/{owner}/{repo}/contents/{filepath}`. */
  def renderCreate(command: CreateFile): String =
    ujson.write(ujson.Obj.from((ContentKey -> ujson.Str(command.content.base64)) +: commitFields(command.commit)))

  /** Renders `command` as the JSON body to `PUT /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * `from_path` is emitted only when the command moves the file; an empty one would ask Forgejo to move the file from
    * a path called nothing.
    */
  def renderUpdate(command: UpdateFile): String =
    val head = List(
      Some(ContentKey -> ujson.Str(command.content.base64)),
      Some(ShaKey     -> ujson.Str(command.expectedSha.value)),
      command.fromPath.map(source => "from_path" -> ujson.Str(source.value)),
    ).flatten

    ujson.write(ujson.Obj.from(head ++ commitFields(command.commit)))

  /** Renders `command` as the JSON body to `DELETE /repos/{owner}/{repo}/contents/{filepath}`. */
  def renderDelete(command: DeleteFile): String =
    ujson.write(ujson.Obj.from((ShaKey -> ujson.Str(command.expectedSha.value)) +: commitFields(command.commit)))

  /** Renders `command` as the JSON body to `POST /repos/{owner}/{repo}/contents`.
    *
    * The operations keep the order the caller put them in, because that is Forgejo's application order and a batch that
    * moves a file before editing it depends on it.
    */
  def renderChange(command: ChangeFiles): String =
    val files = ujson.Arr.from(command.operations.map(operation))

    ujson.write(ujson.Obj.from((FilesKey -> files) +: commitFields(command.commit)))

  /** The eight properties every contents write shares, rendered once. */
  private def commitFields(options: CommitOptions): List[(String, ujson.Value)] =
    List(
      Some("signoff"                    -> ujson.Bool(options.signoff)),
      Some("force_overwrite_new_branch" -> ujson.Bool(options.forceOverwriteNewBranch)),
      options.branch.map(branch         => "branch" -> ujson.Str(branch.value)),
      options.newBranch.map(branch      => "new_branch" -> ujson.Str(branch.value)),
      options.message.map(text          => "message" -> ujson.Str(text)),
      options.author.map(who            => "author" -> identityOf(who)),
      options.committer.map(who         => "committer" -> identityOf(who)),
      dates(options.dates).map(rendered => "dates" -> rendered),
    ).flatten

  private def identityOf(who: CommitIdentity): ujson.Value =
    ujson.Obj.from(
      List(
        who.name.map(value  => "name" -> ujson.Str(value)),
        who.email.map(value => "email" -> ujson.Str(value)),
      ).flatten
    )

  /** The `dates` object, or nothing at all when neither date was set.
    *
    * Emitting `{}` would be a request to date the commit with an empty object, which Forgejo reads as the Go zero time.
    * Omitting the key entirely is what leaves it to date the commit itself.
    */
  private def dates(when: CommitDates): Option[ujson.Value] =
    val fields = List(
      when.author.map(moment    => "author" -> ujson.Str(WireInstant.render(moment))),
      when.committer.map(moment => "committer" -> ujson.Str(WireInstant.render(moment))),
    ).flatten

    Option.when(fields.nonEmpty)(ujson.Obj.from(fields))

  private def operation(change: FileOperation): ujson.Value =
    ujson.Obj.from(
      List("operation" -> ujson.Str(change.wireValue), "path" -> ujson.Str(change.path.value)) ++
        operationFields(change)
    )

  private def operationFields(change: FileOperation): List[(String, ujson.Value)] =
    change match
      case FileOperation.Create(_, content)                => List(ContentKey -> ujson.Str(content.base64))
      case FileOperation.Update(_, content, sha, fromPath) =>
        List(
          Some(ContentKey -> ujson.Str(content.base64)),
          Some(ShaKey     -> ujson.Str(sha.value)),
          fromPath.map(source => "from_path" -> ujson.Str(source.value)),
        ).flatten
      case FileOperation.Delete(_, sha)                    => List(ShaKey -> ujson.Str(sha.value))
