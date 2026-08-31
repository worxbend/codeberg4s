package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.{CommitSha, ContentEntry, ContentKind, ContentMeta, ContentPath, FileContent}

/** Forgejo's `ContentsResponse` — one entry of a repository's contents.
  *
  * This is the element type of '''both''' arms of the union described in
  * [[com.worxbend.codeberg4s.repositories.RepositoryContent]]: the object arm is one of these, and the array arm is a
  * vector of them. `docs/HAZARDS.md` §3 confirmed the element schema is identical either way, which is what makes one
  * DTO enough.
  *
  * Every key on `golden/repository/contents-file.json` and on the 68 entries of `golden/repository/contents-dir.json`
  * is represented, except `_links` — its three members duplicate `url`, `git_url` and `html_url` exactly, on all 69
  * captured entries. [[com.worxbend.codeberg4s.codec.JsonFields]] ignores keys the DTO does not name, so a payload
  * carrying it still decodes.
  *
  * The directory capture is what proves the optionality here is not defensive programming: its entries send `content`,
  * `encoding`, `download_url`, `target` and `submodule_git_url` all as JSON `null`, including for entries whose `type`
  * is `file`.
  *
  * @param name
  *   the `name` key
  * @param path
  *   the `path` key
  * @param sha
  *   the `sha` key: the blob or tree id
  * @param lastCommitSha
  *   the `last_commit_sha` key
  * @param lastCommitWhen
  *   the `last_commit_when` key as a raw string
  * @param contentType
  *   the `type` key, renamed because `type` is a Scala keyword. One of `file`, `dir`, `symlink`, `submodule`
  * @param size
  *   the `size` key, in bytes; `0` for a directory
  * @param encoding
  *   the `encoding` key, populated only alongside `content`
  * @param content
  *   the `content` key: the file's bytes, encoded
  * @param target
  *   the `target` key, populated only for a symlink
  * @param submoduleGitUrl
  *   the `submodule_git_url` key, populated only for a submodule
  * @param url
  *   the `url` key
  * @param htmlUrl
  *   the `html_url` key
  * @param gitUrl
  *   the `git_url` key
  * @param downloadUrl
  *   the `download_url` key, populated only for a file with content to download
  */
final case class ContentEntryDto(
    name: Option[String],
    path: Option[String],
    sha: Option[String],
    lastCommitSha: Option[String],
    lastCommitWhen: Option[String],
    contentType: Option[String],
    size: Option[Long],
    encoding: Option[String],
    content: Option[String],
    target: Option[String],
    submoduleGitUrl: Option[String],
    url: Option[String],
    htmlUrl: Option[String],
    gitUrl: Option[String],
    downloadUrl: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Four things are required. `name`, `path` and `sha` identify the entry, and `path` and `sha` additionally go
    * through their smart constructors so neither can forge a request path later. `type` is required and must be one
    * this library knows, because it decides which case of [[com.worxbend.codeberg4s.repositories.ContentEntry]] is
    * built and therefore which of the payload's fields mean anything — a fifth kind arriving from a future Forgejo is a
    * decoding failure rather than a silently mis-shaped entry.
    *
    * `last_commit_sha` is treated leniently in the other direction: it is descriptive, so a value that is not a valid
    * object id becomes absence rather than failing the whole entry.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ContentEntry] =
    for
      entryName <- Wire.required(at, "name", name)
      entryPath <- Wire.validated(at, "path", path)(ContentPath.from)
      objectId  <- Wire.validated(at, "sha", sha)(CommitSha.from)
      kind      <- kindAt(at)
    yield
      val meta = ContentMeta(
        name           = entryName,
        path           = entryPath,
        sha            = objectId,
        size           = size.getOrElse(0L),
        lastCommitSha  = lastCommitSha.flatMap(value => CommitSha.from(value).toOption),
        lastCommitWhen = Timestamps.parseOptional(lastCommitWhen),
        url            = url,
        htmlUrl        = htmlUrl,
        gitUrl         = gitUrl,
      )

      kind match
        case ContentKind.File      => ContentEntry.File(meta, fileContent, downloadUrl)
        case ContentKind.Directory => ContentEntry.Directory(meta)
        case ContentKind.Symlink   => ContentEntry.Symlink(meta, target)
        case ContentKind.Submodule => ContentEntry.Submodule(meta, submoduleGitUrl)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ContentEntry] =
    toDomainAt(JsonPath.Root)

  private def kindAt(at: JsonPath): Either[DecodeFailure, ContentKind] =
    Wire
      .required(at, "type", contentType)
      .flatMap: declared =>
        ContentKind
          .parse(declared)
          .toRight(DecodeFailure(at.field("type"), s"unknown content type '$declared'"))

  /** Pairs `content` with `encoding`, since neither means anything alone.
    *
    * An unrecognised encoding keeps the payload verbatim rather than dropping it — see
    * [[com.worxbend.codeberg4s.repositories.FileContent.Opaque]].
    */
  private def fileContent: Option[FileContent] =
    content.map: payload =>
      encoding.map(_.trim.toLowerCase) match
        case Some("base64") => FileContent.Base64(payload)
        case _              => FileContent.Opaque(encoding, payload)

object ContentEntryDto:

  /** Reads a `ContentsResponse` object. */
  given JsonDecoder[ContentEntryDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the union DTO that reads both arms. */
  def fromFields(fields: JsonFields): ContentEntryDto =
    ContentEntryDto(
      name            = fields.text("name"),
      path            = fields.text("path"),
      sha             = fields.text("sha"),
      lastCommitSha   = fields.text("last_commit_sha"),
      lastCommitWhen  = fields.text("last_commit_when"),
      contentType     = fields.text("type"),
      size            = fields.number("size"),
      encoding        = fields.text("encoding"),
      content         = fields.rawText("content"),
      target          = fields.text("target"),
      submoduleGitUrl = fields.text("submodule_git_url"),
      url             = fields.text("url"),
      htmlUrl         = fields.text("html_url"),
      gitUrl          = fields.text("git_url"),
      downloadUrl     = fields.text("download_url"),
    )
