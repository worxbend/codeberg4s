package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.hooks.{WikiCommit, WikiPage, WikiPageMeta}
import com.worxbend.codeberg4s.repositories.wire.GitIdentityDto
import com.worxbend.codeberg4s.repositories.{CommitSha, FileContent}

/** Forgejo's `WikiCommit` model — one revision of a wiki page.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  *
  * ==The committer key is misspelled on the wire==
  *
  * The definition spells it `commiter`, with one `t`. That is not a transcription error in this file: it is what the
  * spec declares and what Forgejo sends, and it cannot be corrected upstream without breaking every existing client.
  * Rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once, so the
  * misspelling appears once, here, and the domain spells it
  * [[com.worxbend.codeberg4s.repositories.hooks.WikiCommit.committer]].
  */
final case class WikiCommitDto(
    sha: Option[String],
    author: Option[GitIdentityDto],
    committer: Option[GitIdentityDto],
    message: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `sha` is required and goes through [[com.worxbend.codeberg4s.repositories.CommitSha.from]]: a revision that cannot
    * be named is one no caller can fetch, compare or report. A `sha` that is not an object id is refused at the same
    * place, which is what stops a value that could forge a path from reaching the domain.
    *
    * The failure path names the '''wire''' key, so a bad committer is reported at `commiter` — the spelling a reader
    * will find in the payload — and not at the domain's spelling.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, WikiCommit] =
    Wire
      .validated(at, "sha", sha)(CommitSha.from)
      .map: identifier =>
        WikiCommit(
          sha       = identifier,
          author    = author.map(_.toDomain),
          committer = committer.map(_.toDomain),
          message   = message,
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, WikiCommit] =
    toDomainAt(JsonPath.Root)

object WikiCommitDto:

  /** The wire spelling of the committer key, misspelled by the API; see the class note. */
  val CommitterKey: String = "commiter"

  /** Reads a `WikiCommit` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[WikiCommitDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the DTOs that embed this one. */
  def fromFields(fields: JsonFields): WikiCommitDto =
    WikiCommitDto(
      sha       = fields.text("sha"),
      author    = fields.nested("author").map(GitIdentityDto.fromFields),
      committer = fields.nested(CommitterKey).map(GitIdentityDto.fromFields),
      message   = fields.rawText("message"),
    )

  /** Converts a decoded array of revisions, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[WikiCommitDto]): Either[DecodeFailure, Vector[WikiCommit]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))

/** Forgejo's `WikiPage` model — one wiki page with its content.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  *
  * `content_base64` is the only property the spec annotates as base64, which is why it alone becomes a
  * [[com.worxbend.codeberg4s.repositories.FileContent]] while `sidebar` and `footer` are carried through verbatim — see
  * [[com.worxbend.codeberg4s.repositories.hooks.WikiPage]] for that argument in full.
  */
final case class WikiPageDto(
    title: Option[String],
    contentBase64: Option[String],
    sidebar: Option[String],
    footer: Option[String],
    htmlUrl: Option[String],
    subUrl: Option[String],
    lastCommit: Option[WikiCommitDto],
    commitCount: Option[Long],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `title` is required: it is what addresses the page on every other wiki route, so a page without one cannot be read
    * again, edited or deleted. Everything else is absence-tolerant.
    *
    * A `last_commit` that is present but does not convert fails the whole page at `at.field("last_commit")`, rather
    * than being silently dropped — an unreadable revision is evidence that the payload is not what this model
    * describes.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, WikiPage] =
    for
      pageTitle <- Wire.required(at, "title", title)
      revision  <- WikiPageDto.revision(at, lastCommit)
    yield WikiPage(
      title       = pageTitle,
      content     = contentBase64.map(FileContent.Base64.apply),
      sidebar     = sidebar,
      footer      = footer,
      htmlUrl     = htmlUrl,
      subUrl      = subUrl,
      lastCommit  = revision,
      commitCount = commitCount,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, WikiPage] =
    toDomainAt(JsonPath.Root)

object WikiPageDto:

  /** The wire key a page's base64 content arrives under. */
  val ContentKey: String = "content_base64"

  /** The wire key a page's most recent revision arrives under. */
  val LastCommitKey: String = "last_commit"

  /** Reads a `WikiPage` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[WikiPageDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): WikiPageDto =
    WikiPageDto(
      title         = fields.text("title"),
      contentBase64 = fields.rawText(ContentKey),
      sidebar       = fields.text("sidebar"),
      footer        = fields.text("footer"),
      htmlUrl       = fields.text("html_url"),
      subUrl        = fields.text("sub_url"),
      lastCommit    = fields.nested(LastCommitKey).map(WikiCommitDto.fromFields),
      commitCount   = fields.number("commit_count"),
    )

  /** Converts an embedded revision, reporting its failure at the enclosing model's `last_commit`. */
  private[wire] def revision(
      at: JsonPath,
      commit: Option[WikiCommitDto],
  ): Either[DecodeFailure, Option[WikiCommit]] =
    Wire.nested(at, LastCommitKey, commit)(_.toDomainAt(_))

/** Forgejo's `WikiPageMetaData` model — one entry of the wiki page listing.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]]. It is [[WikiPageDto]] without the
  * body, the sidebar, the footer and the commit count, which is how listing a wiki avoids transferring every page's
  * text.
  */
final case class WikiPageMetaDto(
    title: Option[String],
    htmlUrl: Option[String],
    subUrl: Option[String],
    lastCommit: Option[WikiCommitDto],
):

  /** Converts to the domain, reporting failure paths relative to `at`. `title` is required, for the reason
    * [[WikiPageDto.toDomainAt]] gives.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, WikiPageMeta] =
    for
      pageTitle <- Wire.required(at, "title", title)
      revision  <- WikiPageDto.revision(at, lastCommit)
    yield WikiPageMeta(title = pageTitle, htmlUrl = htmlUrl, subUrl = subUrl, lastCommit = revision)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, WikiPageMeta] =
    toDomainAt(JsonPath.Root)

object WikiPageMetaDto:

  /** Reads a `WikiPageMetaData` object. Absent and `null` are the same thing for every field; see [[JsonFields]]. */
  given JsonDecoder[WikiPageMetaDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): WikiPageMetaDto =
    WikiPageMetaDto(
      title      = fields.text("title"),
      htmlUrl    = fields.text("html_url"),
      subUrl     = fields.text("sub_url"),
      lastCommit = fields.nested(WikiPageDto.LastCommitKey).map(WikiCommitDto.fromFields),
    )

  /** Converts a decoded array of listing entries, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[WikiPageMetaDto]): Either[DecodeFailure, Vector[WikiPageMeta]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))

/** Forgejo's `WikiCommitList` model — the envelope the revision listing returns.
  *
  * '''This listing is not a bare array''', unlike the wiki page listing beside it: it answers
  * `{"commits": [...], "count": n}`. The elements are therefore reported at `$.commits[n]` and not at `$[n]`, which is
  * what [[WikiCommitListDto.EntriesKey]] exists to keep true in one place.
  *
  * The body's own `count` is '''not''' what [[com.worxbend.codeberg4s.paging.Page.totalCount]] reports — that comes
  * from the `X-Total-Count` header, as it does for every other paged call in this library — so it is read here and
  * offered as [[WikiCommitListDto.count]] for a caller who wants to compare the two.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  */
final case class WikiCommitListDto(entries: Vector[WikiCommitDto], count: Option[Long])

object WikiCommitListDto:

  /** The wire key the revisions sit under. */
  val EntriesKey: String = "commits"

  /** Reads a `WikiCommitList` envelope. A missing or `null` `commits` key is an empty listing, not a failure. */
  given JsonDecoder[WikiCommitListDto] =
    JsonFields.reader: fields =>
      WikiCommitListDto(
        entries = fields.nestedAll(EntriesKey).map(WikiCommitDto.fromFields),
        count   = fields.number("count"),
      )
