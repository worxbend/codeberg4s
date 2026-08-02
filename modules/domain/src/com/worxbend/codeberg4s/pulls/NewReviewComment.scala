package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError

/** One inline remark to write, as `CreatePullReviewComment` expects it.
  *
  * Used twice: on its own as the body of `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`, and as an
  * element of [[CreateReview.comments]] when a whole review is posted in one call. The pinned spec expresses that reuse
  * literally — `CreatePullReviewCommentOptions` is a `$ref` to `CreatePullReviewComment` and nothing else — so this
  * library models it once.
  *
  * ==Which line, and which side of the diff==
  *
  * Forgejo carries two line numbers and uses `0` for "not this side": `new_position` addresses the line as it appears
  * '''after''' the change, `old_position` addresses it as it appeared '''before'''. Getting them the wrong way round
  * anchors the remark to an unrelated line rather than failing, which is why there is no constructor taking both. Pick
  * the one that says what is meant:
  *
  * {{{
  * for remark <- NewReviewComment.onNewLine("modules/git/hook.go", 42L, "this quoting is still wrong")
  * yield remark.spanning(3L)
  * }}}
  *
  * [[NewReviewComment.onFile]] is the third possibility — a remark about the file as a whole, with neither position set
  * — which Forgejo accepts and renders at the top of the file's diff.
  *
  * @param body
  *   the remark, as Markdown source; trimmed and required by every constructor
  * @param path
  *   the file the remark is about, relative to the repository root, exactly as
  *   [[com.worxbend.codeberg4s.pulls.ChangedFile.filename]] reports it
  * @param newPosition
  *   the line on the new side of the diff, absent for a remark on the old side or on the file as a whole
  * @param oldPosition
  *   the line on the old side of the diff, absent for a remark on the new side or on the file as a whole
  * @param extraLinesCount
  *   how many further lines the remark covers, absent for a single-line remark. Forgejo reads an absent value and a `0`
  *   identically, so this library sends neither unless [[spanning]] was called
  */
final case class NewReviewComment(
    body: String,
    path: String,
    newPosition: Option[Long],
    oldPosition: Option[Long],
    extraLinesCount: Option[Long],
):

  /** Extends the remark over `lines` further lines after the one it is anchored to.
    *
    * A non-positive `lines` leaves the remark single-line rather than failing: `0` is what Forgejo already means by an
    * absent `extra_lines_count`, and a negative span has no reading at all.
    */
  def spanning(lines: Long): NewReviewComment =
    if lines <= 0L then copy(extraLinesCount = None) else copy(extraLinesCount = Some(lines))

object NewReviewComment:

  /** A remark on a line of the '''new''' side of the diff — the line as it reads after the change.
    *
    * @param path
    *   the file, relative to the repository root
    * @param line
    *   the line number, which must be at least `1`
    * @param body
    *   the remark; trimmed, and rejected when blank
    * @return
    *   the comment, or a [[ValidationError]] on `"path"`, `"line"` or `"body"` — whichever failed first
    */
  def onNewLine(path: String, line: Long, body: String): Either[ValidationError, NewReviewComment] =
    positioned(path, body, line, atNewLine = true)

  /** A remark on a line of the '''old''' side of the diff — the line as it read before the change.
    *
    * Validated exactly as [[onNewLine]], and reported on the same three fields.
    */
  def onOldLine(path: String, line: Long, body: String): Either[ValidationError, NewReviewComment] =
    positioned(path, body, line, atNewLine = false)

  /** A remark about the file as a whole, anchored to neither side of the diff.
    *
    * @return
    *   the comment, or a [[ValidationError]] on `"path"` or `"body"`
    */
  def onFile(path: String, body: String): Either[ValidationError, NewReviewComment] =
    for
      file   <- validPath(path)
      remark <- validBody(body)
    yield NewReviewComment(
      body            = remark,
      path            = file,
      newPosition     = None,
      oldPosition     = None,
      extraLinesCount = None,
    )

  private def positioned(
      path: String,
      body: String,
      line: Long,
      atNewLine: Boolean,
  ): Either[ValidationError, NewReviewComment] =
    for
      file   <- validPath(path)
      number <- validLine(line)
      remark <- validBody(body)
    yield NewReviewComment(
      body            = remark,
      path            = file,
      newPosition     = Option.when(atNewLine)(number),
      oldPosition     = Option.when(!atNewLine)(number),
      extraLinesCount = None,
    )

  private def validPath(path: String): Either[ValidationError, String] =
    val trimmed = path.trim
    if trimmed.isEmpty then Left(ValidationError("path", "must not be blank"))
    else Right(trimmed)

  private def validBody(body: String): Either[ValidationError, String] =
    val trimmed = body.trim
    if trimmed.isEmpty then Left(ValidationError("body", "must not be blank"))
    else Right(trimmed)

  private def validLine(line: Long): Either[ValidationError, Long] =
    if line < 1L then Left(ValidationError("line", "must be at least 1")) else Right(line)
