package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.ArchiveDownloadCount
import com.worxbend.codeberg4s.repositories.ContentKind
import com.worxbend.codeberg4s.repositories.FileContent
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusState
import com.worxbend.codeberg4s.repositories.gitdata.GitObjectKind

import munit.FunSuite

import java.time.Instant

/** The response DTOs of the raw-git group, decoded from hand-written bodies.
  *
  * '''There is no golden fixture behind any of these payloads.''' The harvest that produced
  * `modules/codec/test/resources/golden` was anonymous and covers none of these endpoints, so every body below is
  * derived from the definitions in `spec/swagger.v1.json` — `GitBlob`, `GitTreeResponse`, `Reference`, `AnnotatedTag`,
  * `Note`, `CommitStatus`, `CombinedStatus`, `Compare` and `FileResponse` — and each DTO's own Scaladoc says the same.
  * What these tests establish is that the DTOs read the spec's shape, that a JSON `null` and an absent key decode
  * identically, and that every required field reports its own path when it is missing. They do not establish that a
  * live instance sends exactly this.
  */
final class GitDataDtoSuite extends FunSuite:

  // --- blobs ----------------------------------------------------------------

  test("a blob decodes its five keys"):
    val dto = decode[GitBlobDto](GitDataDtoSuite.BlobBody)

    assertEquals(dto.sha, Some("bd9f0e2b1a4e5f6c7d8e9f0a1b2c3d4e5f6a7b8c"))
    assertEquals(dto.encoding, Some("base64"))
    assertEquals(dto.content, Some("aGVsbG8="))
    assertEquals(dto.size, Some(6L))

  test("a base64 blob converts to the FileContent the contents endpoint already models"):
    val blob = domain(decode[GitBlobDto](GitDataDtoSuite.BlobBody).toDomain)

    assertEquals(blob.size, 6L)
    assertEquals(blob.content, Some(FileContent.Base64("aGVsbG8=")))
    assertEquals(blob.content.flatMap(_.text), Some("hello"))

  test("a blob whose encoding is unknown keeps its payload verbatim rather than guessing"):
    val blob = domain(decode[GitBlobDto]("""{"sha":"cafebabe","content":"..","encoding":"quoted-printable"}""").toDomain)

    assertEquals(blob.content, Some(FileContent.Opaque(Some("quoted-printable"), "..")))

  test("a blob too large to inline has a size and no content, which is a success"):
    val blob = domain(decode[GitBlobDto]("""{"sha":"cafebabe","size":40000000,"content":null}""").toDomain)

    assertEquals(blob.size, 40000000L)
    assertEquals(blob.content, None)

  test("an absent key and a JSON null decode identically on a blob"):
    assertEquals(decode[GitBlobDto]("""{"sha":"cafebabe","url":null}"""), decode[GitBlobDto]("""{"sha":"cafebabe"}"""))

  test("a blob without an object id cannot be converted"):
    assertEquals(pathOf(decode[GitBlobDto]("""{"size":1}""").toDomain), Some("$.sha"))

  test("a blob whose id is not hexadecimal is rejected before it can forge a path"):
    assertEquals(pathOf(decode[GitBlobDto]("""{"sha":"../../etc"}""").toDomain), Some("$.sha"))

  test("an array of blobs reports a bad element at its own index"):
    val dtos    = decode[Vector[GitBlobDto]]("""[{"sha":"cafebabe"},{"size":2}]""")
    val failure = dtos(1).toDomainAt(JsonPath.Root.index(1))

    assertEquals(pathOf(failure), Some("$[1].sha"))

  // --- trees ----------------------------------------------------------------

  test("a tree envelope decodes its entries and its own paging fields"):
    val dto = decode[GitTreeDto](GitDataDtoSuite.TreeBody)

    assertEquals(dto.sha, Some("aaaabbbbccccddddeeeeffff00001111"))
    assertEquals(dto.entries.size, 2)
    assertEquals(dto.totalCount, Some(2L))
    assertEquals(dto.truncated, Some(false))
    assertEquals(dto.page, Some(1L))

  test("tree entries convert with their kind, mode and size"):
    val entries = domain(decode[GitTreeDto](GitDataDtoSuite.TreeBody).toDomain)

    assertEquals(entries.map(_.path.value), Vector("README.md", "modules/core"))
    assertEquals(entries.map(_.kind), Vector(Some(GitObjectKind.Blob), Some(GitObjectKind.Tree)))
    assertEquals(entries.head.mode, Some("100644"))
    assertEquals(entries.head.size, 42L)
    assertEquals(entries(1).size, 0L)

  test("an entry naming an object kind this library does not know keeps the rest of the page"):
    val entries = domain(decode[GitTreeDto]("""{"tree":[{"path":"x","sha":"cafebabe","type":"gitlink"}]}""").toDomain)

    assertEquals(entries.map(_.kind), Vector(None))

  test("a tree with no entries is an empty directory and not a failure"):
    assertEquals(domain(decode[GitTreeDto]("""{"sha":"cafebabe","tree":null}""").toDomain), Vector.empty)

  test("an entry without a path reports its index under the tree key"):
    assertEquals(pathOf(decode[GitTreeDto]("""{"tree":[{"sha":"cafebabe"}]}""").toDomain), Some("$.tree[0].path"))

  test("an entry without an object id reports its index too"):
    assertEquals(pathOf(decode[GitTreeDto]("""{"tree":[{"path":"x"}]}""").toDomain), Some("$.tree[0].sha"))

  // --- refs -----------------------------------------------------------------

  test("a ref listing decodes and converts, target and all"):
    val refs = decode[Vector[ReferenceDto]](GitDataDtoSuite.RefsBody).map(dto => domain(dto.toDomain))

    assertEquals(refs.map(_.name.value), Vector("refs/heads/main", "refs/tags/v1.0"))
    assertEquals(refs.head.target.map(_.sha.value), Some("1111111111111111111111111111111111111111"))
    assertEquals(refs.head.target.flatMap(_.kind), Some(GitObjectKind.Commit))

  test("an annotated tag's ref points at a tag object and not at a commit"):
    val refs = decode[Vector[ReferenceDto]](GitDataDtoSuite.RefsBody).map(dto => domain(dto.toDomain))

    assertEquals(refs(1).target.flatMap(_.kind), Some(GitObjectKind.Tag))

  test("a ref with no name cannot be converted"):
    assertEquals(pathOf(decode[ReferenceDto]("""{"url":"https://example.org"}""").toDomain), Some("$.ref"))

  test("a ref whose name would climb out of the route is rejected"):
    assertEquals(pathOf(decode[ReferenceDto]("""{"ref":"refs/../admin"}""").toDomain), Some("$.ref"))

  test("a failure inside a ref's object is reported at the object's own path"):
    assertEquals(pathOf(decode[ReferenceDto]("""{"ref":"refs/heads/x","object":{}}""").toDomain), Some("$.object.sha"))

  test("a ref with no object is still a ref"):
    assertEquals(domain(decode[ReferenceDto]("""{"ref":"refs/heads/x"}""").toDomain).target, None)

  // --- annotated tags -------------------------------------------------------

  test("an annotated tag decodes its name, its own id and the object it points at"):
    val tag = domain(decode[AnnotatedTagDto](GitDataDtoSuite.AnnotatedTagBody).toDomain)

    assertEquals(tag.name.value, "v1.0")
    assertEquals(tag.sha.value, "2222222222222222222222222222222222222222")
    assertEquals(tag.target.map(_.sha.value), Some("3333333333333333333333333333333333333333"))
    assertEquals(tag.message, Some("release 1.0"))

  test("the tag object's id and the commit's id are different values, which is the trap this model exists for"):
    val tag = domain(decode[AnnotatedTagDto](GitDataDtoSuite.AnnotatedTagBody).toDomain)

    assertNotEquals(Some(tag.sha.value), tag.target.map(_.sha.value))

  test("a tagger, a verification verdict and the archive counters all convert"):
    val tag = domain(decode[AnnotatedTagDto](GitDataDtoSuite.AnnotatedTagBody).toDomain)

    assertEquals(tag.tagger.flatMap(_.name), Some("Ada"))
    assertEquals(tag.tagger.flatMap(_.date), Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(tag.verification.map(_.isVerified), Some(true))
    assertEquals(tag.archiveDownloads, Some(ArchiveDownloadCount(3L, 4L)))

  test("a tag with no name cannot be converted"):
    assertEquals(pathOf(decode[AnnotatedTagDto]("""{"sha":"cafebabe"}""").toDomain), Some("$.tag"))

  test("a tag with no id of its own cannot be converted"):
    assertEquals(pathOf(decode[AnnotatedTagDto]("""{"tag":"v1"}""").toDomain), Some("$.sha"))

  // --- notes ----------------------------------------------------------------

  test("a note decodes its message and the commit it hangs off"):
    val note = domain(decode[NoteDto](GitDataDtoSuite.NoteBody).toDomain)

    assertEquals(note.message, Some("reviewed by security"))
    assertEquals(note.commit.map(_.sha.value), Some("4444444444444444444444444444444444444444"))

  test("a note with no commit is still a note"):
    assertEquals(domain(decode[NoteDto]("""{"message":"x"}""").toDomain).commit, None)

  test("an unusable commit inside a note is reported at the commit's own path"):
    assertEquals(pathOf(decode[NoteDto]("""{"message":"x","commit":{}}""").toDomain), Some("$.commit.sha"))

  // --- statuses -------------------------------------------------------------

  test("a commit status decodes its verdict, its context and its timestamps"):
    val status = domain(decode[CommitStatusDto](GitDataDtoSuite.StatusBody).toDomain)

    assertEquals(status.id, 7L)
    assertEquals(status.state, Some(CommitStatusState.Success))
    assertEquals(status.context, Some("ci/woodpecker/push"))
    assertEquals(status.created, Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(status.creator.map(_.login.value), Some("ada"))

  test("a status whose verdict this library does not recognise keeps every other field"):
    val status = domain(decode[CommitStatusDto]("""{"id":1,"status":"cancelled","context":"x"}""").toDomain)

    assertEquals(status.state, None)
    assertEquals(status.context, Some("x"))

  test("a status without an id cannot be converted"):
    assertEquals(pathOf(decode[CommitStatusDto]("""{"status":"success"}""").toDomain), Some("$.id"))

  test("a combined status keeps the reduced verdict the envelope exists for"):
    val combined = domain(decode[CombinedStatusDto](GitDataDtoSuite.CombinedStatusBody).toDomain)

    assertEquals(combined.sha.value, "5555555555555555555555555555555555555555")
    assertEquals(combined.state, Some(CommitStatusState.Pending))
    assertEquals(combined.totalCount, 2L)
    assertEquals(combined.statuses.map(_.id), Vector(7L))
    assertEquals(combined.repository.map(_.slug.value), Some("worxbend/codeberg4s"))

  test("a combined status without a resolved sha cannot be converted"):
    assertEquals(pathOf(decode[CombinedStatusDto]("""{"state":"success"}""").toDomain), Some("$.sha"))

  test("a bad nested status is reported at its own index"):
    val body = """{"sha":"cafebabe","statuses":[{"id":1},{"context":"x"}]}"""

    assertEquals(pathOf(decode[CombinedStatusDto](body).toDomain), Some("$.statuses[1].id"))

  // --- compare --------------------------------------------------------------

  test("a comparison decodes its commits, its files and the count that reveals truncation"):
    val comparison = domain(decode[CompareDto](GitDataDtoSuite.CompareBody).toDomain)

    assertEquals(comparison.totalCommits, 3L)
    assertEquals(comparison.commits.map(_.sha.value), Vector("6666666666666666666666666666666666666666"))
    assertEquals(comparison.files.map(_.filename), Vector("build.mill"))
    assertEquals(comparison.isTruncated, true)

  test("a comparison of a ref with itself decodes to nothing at all"):
    assertEquals(
      domain(decode[CompareDto]("""{"total_commits":0,"commits":null,"files":null}""").toDomain).commits,
      Vector.empty,
    )

  test("a bad commit in a comparison is reported at its own index"):
    assertEquals(pathOf(decode[CompareDto]("""{"commits":[{"url":"x"}]}""").toDomain), Some("$.commits[0].sha"))

  // --- diffpatch response ---------------------------------------------------

  test("a file response decodes the commit it wrote and the file it left behind"):
    val change = domain(decode[FileResponseDto](GitDataDtoSuite.FileResponseBody).toDomain)

    assertEquals(change.commit.map(_.sha.value), Some("7777777777777777777777777777777777777777"))
    assertEquals(change.commit.flatMap(_.message), Some("apply patch"))
    assertEquals(change.commit.map(_.parents.size), Some(1))
    assertEquals(change.commit.flatMap(_.tree).map(_.sha.value), Some("8888888888888888888888888888888888888888"))
    assertEquals(change.verification.map(_.isVerified), Some(false))

  test("the file the patch left behind is the contents endpoint's own model"):
    val change = domain(decode[FileResponseDto](GitDataDtoSuite.FileResponseBody).toDomain)

    assertEquals(change.content.map(_.meta.path.value), Some("README.md"))
    assertEquals(change.content.map(_.kind), Some(ContentKind.File))

  test("a patch that touched several files reports a commit and no single content"):
    val change = domain(decode[FileResponseDto]("""{"commit":{"sha":"cafebabe"},"content":null}""").toDomain)

    assertEquals(change.content, None)
    assertEquals(change.commit.map(_.sha.value), Some("cafebabe"))

  test("a written commit with no id cannot be converted"):
    assertEquals(pathOf(decode[FileResponseDto]("""{"commit":{"message":"x"}}""").toDomain), Some("$.commit.sha"))

  // --- editorconfig ---------------------------------------------------------

  test("editorconfig properties decode as the text an EditorConfig consumer would have read"):
    val definitions = decode[EditorConfigDto](GitDataDtoSuite.EditorConfigBody).toDomain

    assertEquals(definitions.valueOf("indent_style"), Some("space"))
    assertEquals(definitions.valueOf("indent_size"), Some("4"))
    assertEquals(definitions.valueOf("insert_final_newline"), Some("true"))

  test("a whole number renders without the fractional part the document model parses it into"):
    assertEquals(
      decode[EditorConfigDto]("""{"max_line_length":120}""").toDomain.valueOf("max_line_length"),
      Some("120"),
    )

  test("a fractional number keeps its point, since nothing here decides what a property means"):
    assertEquals(decode[EditorConfigDto]("""{"tab_ratio":1.5}""").toDomain.valueOf("tab_ratio"), Some("1.5"))

  test("a value with no text form is dropped rather than invented"):
    val definitions = decode[EditorConfigDto]("""{"a":"x","b":null,"c":[1],"d":{"e":1}}""").toDomain

    assertEquals(definitions.values.keySet, Set("a"))

  test("a path with no properties decodes to an empty set of definitions"):
    assertEquals(decode[EditorConfigDto]("{}").toDomain.isEmpty, true)

  // --- helpers --------------------------------------------------------------

  private def decode[A: JsonDecoder](body: String): A =
    Json.decode[A](body) match
      case Right(value)  => value
      case Left(failure) => fail(s"expected a decodable body, got ${failure.path.render}: ${failure.message}")

  private def domain[A](result: Either[DecodeFailure, A]): A =
    result match
      case Right(value)  => value
      case Left(failure) => fail(s"expected a convertible payload, got ${failure.path.render}: ${failure.message}")

  private def pathOf[A](result: Either[DecodeFailure, A]): Option[String] =
    result.swap.toOption.map(_.path.render)

/** The bodies this suite decodes, derived from `spec/swagger.v1.json` and kept out of the tests so each test reads as
  * one behaviour.
  */
object GitDataDtoSuite:

  val BlobBody: String =
    """{
      |  "sha": "bd9f0e2b1a4e5f6c7d8e9f0a1b2c3d4e5f6a7b8c",
      |  "size": 6,
      |  "encoding": "base64",
      |  "content": "aGVsbG8=",
      |  "url": "https://forge.example/api/v1/repos/o/r/git/blobs/bd9f0e2b"
      |}""".stripMargin

  val TreeBody: String =
    """{
      |  "sha": "aaaabbbbccccddddeeeeffff00001111",
      |  "url": "https://forge.example/api/v1/repos/o/r/git/trees/aaaabbbb",
      |  "tree": [
      |    {"path": "README.md", "mode": "100644", "type": "blob", "size": 42, "sha": "1a2b3c4d"},
      |    {"path": "modules/core", "mode": "040000", "type": "tree", "size": null, "sha": "5e6f7a8b"}
      |  ],
      |  "truncated": false,
      |  "page": 1,
      |  "total_count": 2
      |}""".stripMargin

  val RefsBody: String =
    """[
      |  {
      |    "ref": "refs/heads/main",
      |    "url": "https://forge.example/api/v1/repos/o/r/git/refs/heads/main",
      |    "object": {"type": "commit", "sha": "1111111111111111111111111111111111111111", "url": "https://x"}
      |  },
      |  {
      |    "ref": "refs/tags/v1.0",
      |    "url": null,
      |    "object": {"type": "tag", "sha": "2222222222222222222222222222222222222222"}
      |  }
      |]""".stripMargin

  val AnnotatedTagBody: String =
    """{
      |  "tag": "v1.0",
      |  "sha": "2222222222222222222222222222222222222222",
      |  "url": "https://forge.example/api/v1/repos/o/r/git/tags/2222222222",
      |  "message": "release 1.0",
      |  "tagger": {"name": "Ada", "email": "ada@example.org", "date": "2026-07-30T21:14:15+02:00"},
      |  "object": {"type": "commit", "sha": "3333333333333333333333333333333333333333"},
      |  "verification": {"verified": true, "reason": "gpg.error.no_gpg_keys_found", "signature": null},
      |  "archive_download_count": {"zip": 3, "tar_gz": 4}
      |}""".stripMargin

  val NoteBody: String =
    """{
      |  "message": "reviewed by security",
      |  "commit": {
      |    "sha": "4444444444444444444444444444444444444444",
      |    "commit": {"message": "fix the thing", "tree": {"sha": "9999999999999999999999999999999999999999"}},
      |    "author": null,
      |    "files": null
      |  }
      |}""".stripMargin

  val StatusBody: String =
    """{
      |  "id": 7,
      |  "status": "success",
      |  "context": "ci/woodpecker/push",
      |  "description": "Pipeline was successful",
      |  "target_url": "https://ci.example/pipeline/7",
      |  "creator": {"id": 1, "login": "ada"},
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "updated_at": "2026-07-30T21:14:15+02:00",
      |  "url": "https://forge.example/api/v1/repos/o/r/statuses/7"
      |}""".stripMargin

  val CombinedStatusBody: String =
    """{
      |  "sha": "5555555555555555555555555555555555555555",
      |  "state": "pending",
      |  "total_count": 2,
      |  "commit_url": "https://forge.example/api/v1/repos/o/r/git/commits/5555555555",
      |  "url": "https://forge.example/api/v1/repos/o/r/commits/5555555555/status",
      |  "statuses": [{"id": 7, "status": "pending", "context": "ci/woodpecker/push"}],
      |  "repository": {"id": 12, "name": "codeberg4s", "owner": {"id": 1, "login": "worxbend"}}
      |}""".stripMargin

  val CompareBody: String =
    """{
      |  "total_commits": 3,
      |  "commits": [{"sha": "6666666666666666666666666666666666666666", "commit": {"message": "one"}}],
      |  "files": [{"filename": "build.mill", "status": "modified"}]
      |}""".stripMargin

  val FileResponseBody: String =
    """{
      |  "commit": {
      |    "sha": "7777777777777777777777777777777777777777",
      |    "message": "apply patch",
      |    "created": "2026-07-30T21:14:15+02:00",
      |    "author": {"name": "Ada", "email": "ada@example.org"},
      |    "committer": {"name": "Ada", "email": "ada@example.org"},
      |    "tree": {"sha": "8888888888888888888888888888888888888888"},
      |    "parents": [{"sha": "9999999999999999999999999999999999999999"}]
      |  },
      |  "content": {"name": "README.md", "path": "README.md", "sha": "1a2b3c4d", "type": "file", "size": 12},
      |  "verification": {"verified": false, "reason": "gpg.error.not_signed_commit"}
      |}""".stripMargin

  val EditorConfigBody: String =
    """{
      |  "charset": "utf-8",
      |  "indent_style": "space",
      |  "indent_size": 4,
      |  "insert_final_newline": true
      |}""".stripMargin
