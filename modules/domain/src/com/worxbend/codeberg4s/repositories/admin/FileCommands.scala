package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.CommitVerification
import com.worxbend.codeberg4s.repositories.ContentEntry
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.gitdata.FileCommit

import java.time.Instant

/** Who a commit written by a contents endpoint is attributed to — Forgejo's `Identity`.
  *
  * Two free-text fields, exactly as Git records them. This is deliberately '''not'''
  * [[com.worxbend.codeberg4s.users.User]] and not [[com.worxbend.codeberg4s.repositories.GitIdentity]]: the wire model
  * has only `name` and `email`, and a type with fields the endpoint cannot accept would invite a caller to set them.
  *
  * The spec's own note on every contents request: "`author` and `committer` are optional (if only one is given, it will
  * be used for the other, otherwise the authenticated user will be used)". Setting neither is the normal case.
  *
  * @param name
  *   the display name to record
  * @param email
  *   the address to record. Forgejo may reject one that belongs to another account
  */
final case class CommitIdentity(name: Option[String], email: Option[String])

/** The author and committer dates of a commit written by a contents endpoint — Forgejo's `CommitDateOptions`.
  *
  * Setting these is how a caller reproduces history rather than recording "now". Absent leaves Forgejo to use the time
  * it processed the request, which is what almost every caller wants.
  *
  * @param author
  *   `GIT_AUTHOR_DATE`
  * @param committer
  *   `GIT_COMMITTER_DATE`
  */
final case class CommitDates(author: Option[Instant], committer: Option[Instant])

object CommitDates:

  /** Let Forgejo date the commit. */
  val Unset: CommitDates = CommitDates(None, None)

/** The settings every contents write shares — the eight properties `CreateFileOptions`, `UpdateFileOptions`,
  * `DeleteFileOptions` and `ChangeFilesOptions` all declare.
  *
  * Split into its own type rather than repeated on four commands, so the wire spellings and the semantics live in one
  * place. Rule 4 of `com.worxbend.codeberg4s.codec.WireConventions` is the same argument on the codec side.
  *
  * ==Branching==
  *
  * [[branch]] says where to read the current state from; [[newBranch]] says to create a branch there first and commit
  * onto that instead. Together they are how a caller opens a change for review without touching the default branch.
  * [[forceOverwriteNewBranch]] is the escape hatch for the case where [[newBranch]] already exists — it force-pushes,
  * and it is off by default because a silent force-push is not something a library should do on its own initiative.
  *
  * @param branch
  *   the branch to base the change on. Absent means the repository's default branch
  * @param newBranch
  *   a branch to create from [[branch]] and commit onto. Absent commits onto [[branch]] itself
  * @param message
  *   the commit message. Absent lets Forgejo compose its own, which names the operation and the path
  * @param author
  *   who the commit is attributed to — see [[CommitIdentity]]
  * @param committer
  *   who applied it — see [[CommitIdentity]]
  * @param dates
  *   the commit's author and committer dates — see [[CommitDates]]
  * @param signoff
  *   add a `Signed-off-by` trailer for the committer
  * @param forceOverwriteNewBranch
  *   force-push when [[newBranch]] already exists. See the note above
  */
final case class CommitOptions(
    branch: Option[BranchName],
    newBranch: Option[BranchName],
    message: Option[String],
    author: Option[CommitIdentity],
    committer: Option[CommitIdentity],
    dates: CommitDates,
    signoff: Boolean,
    forceOverwriteNewBranch: Boolean,
):

  /** Bases the change on `base` rather than the repository's default branch. */
  def on(base: BranchName): CommitOptions = copy(branch = Some(base))

  /** Creates `target` from the base branch and commits onto it. */
  def onNewBranch(target: BranchName): CommitOptions = copy(newBranch = Some(target))

  /** Uses `text` as the commit message. */
  def describedAs(text: String): CommitOptions = copy(message = Some(text))

  /** Attributes the commit to `identity`. */
  def authoredBy(identity: CommitIdentity): CommitOptions = copy(author = Some(identity))

  /** Records `identity` as the committer. */
  def committedBy(identity: CommitIdentity): CommitOptions = copy(committer = Some(identity))

  /** Dates the commit explicitly rather than letting Forgejo use the time of the request. */
  def dated(when: CommitDates): CommitOptions = copy(dates = when)

  /** Adds a `Signed-off-by` trailer. */
  def signedOff: CommitOptions = copy(signoff = true)

  /** Force-pushes when the new branch already exists. See the type note before choosing this. */
  def overwritingNewBranch: CommitOptions = copy(forceOverwriteNewBranch = true)

object CommitOptions:

  /** Commit onto the repository's default branch, with Forgejo's own message and the token's account as author. */
  val Default: CommitOptions =
    CommitOptions(
      branch                  = None,
      newBranch               = None,
      message                 = None,
      author                  = None,
      committer               = None,
      dates                   = CommitDates.Unset,
      signoff                 = false,
      forceOverwriteNewBranch = false,
    )

/** Everything `POST /repos/{owner}/{repo}/contents/{filepath}` may be told, as one value.
  *
  * Derived from `CreateFileOptions` in `spec/swagger.v1.json`, which declares `content` required. The path is the URL's
  * business, not this command's. No golden capture of this request exists.
  *
  * '''Creating a file that already exists is a `422`''', not an overwrite. [[UpdateFile]] is the call that replaces
  * one, and it is the call that carries the sha guard.
  *
  * @param content
  *   the file's bytes — see [[FileBytes]] for why this is not a `String`
  * @param commit
  *   the branch, message, authorship and dates of the commit this writes
  */
final case class CreateFile(content: FileBytes, commit: CommitOptions):

  /** Replaces the commit settings. */
  def committing(options: CommitOptions): CreateFile = copy(commit = options)

object CreateFile:

  /** Creates a file with the given bytes, committing onto the default branch with Forgejo's own message. */
  def of(content: FileBytes): CreateFile =
    CreateFile(content = content, commit = CommitOptions.Default)

/** Everything `PUT /repos/{owner}/{repo}/contents/{filepath}` may be told, as one value.
  *
  * Derived from `UpdateFileOptions` in `spec/swagger.v1.json`, which declares `sha` '''and''' `content` required. No
  * golden capture of this request exists.
  *
  * ==The sha is the whole safety story==
  *
  * [[expectedSha]] is the blob id the caller believes the file currently has, and Forgejo refuses the write —
  * `409 Conflict` — when the file has moved on. It is not optional, on the wire or here, and modelling it as optional
  * would be the single most dangerous convenience this group could offer: without it, a caller who read a file, thought
  * about it, and wrote it back would silently discard whatever landed in between.
  *
  * It is also what makes the retry decision defensible. `RepositoryAdminApi.updateFile` is
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]: after a lost success the file's sha has already changed, so
  * a repeat cannot overwrite anything — it fails with a `409`. That is the right failure, but it is still a failure a
  * caller would be told about for a call that in fact succeeded, which is exactly the case the retry rules say not to
  * repeat.
  *
  * ==Moving a file==
  *
  * [[fromPath]] renames: the URL names where the file will be, this names where it is now, and Forgejo records one
  * commit that moves and edits it. The sha guard applies to the '''source'''.
  *
  * @param content
  *   the file's new bytes — see [[FileBytes]]
  * @param expectedSha
  *   the blob id the file is expected to have. See the note above
  * @param fromPath
  *   the file's current path, when this call also moves it
  * @param commit
  *   the branch, message, authorship and dates of the commit this writes
  */
final case class UpdateFile(
    content: FileBytes,
    expectedSha: CommitSha,
    fromPath: Option[ContentPath],
    commit: CommitOptions,
):

  /** Moves the file from `source` to the path named in the URL, as part of the same commit. */
  def movedFrom(source: ContentPath): UpdateFile = copy(fromPath = Some(source))

  /** Replaces the commit settings. */
  def committing(options: CommitOptions): UpdateFile = copy(commit = options)

object UpdateFile:

  /** Replaces the file whose current blob id is `expectedSha` with `content`.
    *
    * Both arguments are already-validated types, so this cannot fail. The blob id comes from
    * [[com.worxbend.codeberg4s.repositories.ContentMeta.sha]] on the entry that was read.
    */
  def of(content: FileBytes, expectedSha: CommitSha): UpdateFile =
    UpdateFile(content = content, expectedSha = expectedSha, fromPath = None, commit = CommitOptions.Default)

/** Everything `DELETE /repos/{owner}/{repo}/contents/{filepath}` may be told, as one value.
  *
  * Derived from `DeleteFileOptions` in `spec/swagger.v1.json`, which declares `sha` required. No golden capture of this
  * request exists.
  *
  * '''The sha guard is the same as [[UpdateFile]]'s''' and is required for the same reason: without it, deleting a file
  * a caller read a minute ago would also delete an edit they never saw. Forgejo answers `400` when the id does not
  * match what is there.
  *
  * @param expectedSha
  *   the blob id the file is expected to have
  * @param commit
  *   the branch, message, authorship and dates of the commit this writes
  */
final case class DeleteFile(expectedSha: CommitSha, commit: CommitOptions):

  /** Replaces the commit settings. */
  def committing(options: CommitOptions): DeleteFile = copy(commit = options)

object DeleteFile:

  /** Deletes the file whose current blob id is `expectedSha`. Cannot fail; the argument is already validated. */
  def of(expectedSha: CommitSha): DeleteFile =
    DeleteFile(expectedSha = expectedSha, commit = CommitOptions.Default)

/** One file change inside a batch — Forgejo's `ChangeFileOperation`.
  *
  * An enum rather than one case class with three `Option`s, for the reason
  * [[com.worxbend.codeberg4s.repositories.ContentEntry]] gives on the read side: a delete has no content, a create has
  * no sha, and a single flat shape would let a caller build both of those mistakes and only find out from a `422`. The
  * `operation` key Forgejo reads is derived from the case, so it cannot disagree with the fields that are present.
  */
enum FileOperation:

  /** Adds a file that does not exist yet.
    *
    * @param path
    *   where it will live
    * @param content
    *   its bytes
    */
  case Create(path: ContentPath, content: FileBytes)

  /** Replaces a file that does exist.
    *
    * @param path
    *   where it will live afterwards
    * @param content
    *   its new bytes
    * @param expectedSha
    *   the blob id it is expected to have now — the same guard [[UpdateFile]] carries, and required by the spec for
    *   this operation
    * @param fromPath
    *   where it lives now, when this operation also moves it
    */
  case Update(path: ContentPath, content: FileBytes, expectedSha: CommitSha, fromPath: Option[ContentPath])

  /** Removes a file.
    *
    * @param path
    *   the file to remove
    * @param expectedSha
    *   the blob id it is expected to have, required by the spec for this operation
    */
  case Delete(path: ContentPath, expectedSha: CommitSha)

  /** The path this operation writes to, without matching on the case. */
  def path: ContentPath

  /** The spelling Forgejo expects in the `operation` key. */
  def wireValue: String =
    this match
      case Create(_, _)       => "create"
      case Update(_, _, _, _) => "update"
      case Delete(_, _)       => "delete"

/** Everything `POST /repos/{owner}/{repo}/contents` may be told, as one value.
  *
  * Derived from `ChangeFilesOptions` in `spec/swagger.v1.json`, which declares `files` required. No golden capture of
  * this request exists.
  *
  * ==One commit, many files==
  *
  * This is the only way to write several files atomically. Four separate calls to [[CreateFile]] produce four commits
  * and four chances to leave the repository half-changed; one call here produces one commit that either lands whole or
  * does not land at all. Prefer it whenever a change spans more than one path.
  *
  * The order of [[operations]] is preserved and is Forgejo's application order, which matters when one operation moves
  * a file another one then edits.
  *
  * @param operations
  *   what to do, in order. An empty batch is rejected by Forgejo, so [[ChangeFiles.of]] demands the first one
  * @param commit
  *   the branch, message, authorship and dates of the single commit this writes
  */
final case class ChangeFiles(operations: Vector[FileOperation], commit: CommitOptions):

  /** Appends another operation to the batch. */
  def and(operation: FileOperation): ChangeFiles = copy(operations = operations.appended(operation))

  /** Replaces the commit settings. */
  def committing(options: CommitOptions): ChangeFiles = copy(commit = options)

object ChangeFiles:

  /** Starts a batch with the operation Forgejo will not do without.
    *
    * Taking the first operation as an argument is what makes an empty batch unrepresentable, rather than a `422` a
    * caller discovers at runtime.
    */
  def of(first: FileOperation): ChangeFiles =
    ChangeFiles(operations = Vector(first), commit = CommitOptions.Default)

/** What a batch write answers with — Forgejo's `FilesResponse`.
  *
  * The multi-file counterpart of [[com.worxbend.codeberg4s.repositories.gitdata.FileChange]], which the single-file
  * writes answer with. Both name one commit; this one lists every entry that commit touched instead of naming a single
  * one.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.'''
  *
  * @param commit
  *   the commit that was written, absent when the instance reported none
  * @param files
  *   the entries as they now stand, in the order Forgejo returned them. A delete contributes no entry, so this is not
  *   always as long as the batch that produced it
  * @param verification
  *   what Forgejo made of the commit's signature, when it signed one
  */
final case class FileChangeSet(
    commit: Option[FileCommit],
    files: Vector[ContentEntry],
    verification: Option[CommitVerification],
)
