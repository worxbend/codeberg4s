package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.ContentEntry
import com.worxbend.codeberg4s.repositories.ContentKind
import com.worxbend.codeberg4s.repositories.RepositoryContent

import munit.FunSuite

import java.time.Instant

/** The union endpoint, both arms, against both golden captures.
  *
  * `docs/HAZARDS.md` §3: `GET /repos/{owner}/{repo}/contents/{filepath}` answers a JSON object for a file and a JSON
  * array for a directory, while the pinned spec declares only the object. These tests are the reason this library gets
  * the endpoint right where a generated client does not, so they assert on both shapes and on what happens to a third.
  */
final class RepositoryContentDtoSuite extends FunSuite with GoldenFixtures:

  test("the file capture decodes into the object arm"):
    decoded("repository/contents-file.json") match
      case RepositoryContent.File(_)            => ()
      case RepositoryContent.Directory(entries) => fail(s"a file decoded as a directory of ${entries.size}")

  test("the file capture's metadata converts"):
    val entry = fileEntry("repository/contents-file.json")

    assertEquals(entry.meta.name, "README.md")
    assertEquals(entry.meta.path.value, "README.md")
    assertEquals(entry.meta.sha.value, "e6f1b6c3d1bf880d606379609238c57fe60016db")
    assertEquals(entry.meta.size, 2790L)
    assertEquals(entry.meta.lastCommitSha.map(_.value), Some("676fb7e0a74e801cc5e5eedd6af37c16f56aa63f"))
    assertEquals(entry.meta.lastCommitWhen, Some(Instant.parse("2025-09-04T01:53:40+02:00")))

  test("the file capture's base64 payload decodes to the README's text"):
    val content = fileEntry("repository/contents-file.json") match
      case ContentEntry.File(_, payload, _) => payload.flatMap(_.text)
      case other                            => fail(s"expected a file entry, got $other")

    assert(content.exists(_.contains("Welcome to Forgejo")))

  test("the directory capture decodes into the array arm, all 68 entries"):
    decoded("repository/contents-dir.json") match
      case RepositoryContent.Directory(entries) => assertEquals(entries.size, 68)
      case RepositoryContent.File(entry)        => fail(s"a directory decoded as the single entry ${entry.meta.name}")

  test("a directory mixes entry kinds, and each lands on its own case"):
    val entries = directoryEntries("repository/contents-dir-small.json")

    assertEquals(entries.map(_.kind), Vector(ContentKind.Directory, ContentKind.File))

  test("a file listed inside a directory has no content, which is absence and not an empty file"):
    val listed = directoryEntries("repository/contents-dir-small.json").last

    listed match
      case ContentEntry.File(meta, payload, downloadUrl) =>
        assertEquals(meta.name, "README.md")
        assertEquals(payload, None)
        assert(downloadUrl.isDefined, "a listed file still reports where its bytes can be fetched")
      case other                                         => fail(s"expected a file entry, got $other")

  test("a body that is a JSON string is a decoding failure, not an exception"):
    assertEquals(failure(""""just a string""""), Some("$"))

  test("a body that is the JSON literal null is a decoding failure"):
    assertEquals(failure("null"), Some("$"))

  test("an array holding something that is not an entry is a decoding failure"):
    assertEquals(failure("[1, 2, 3]"), Some("$"))

  test("an entry whose type this library does not know is a decoding failure at that field"):
    val body = """{"name": "x", "path": "x", "sha": "abcdef12", "type": "wormhole"}"""

    assertEquals(failure(body), Some("$.type"))

  test("an entry without a sha is a decoding failure at that field"):
    val body = """{"name": "x", "path": "x", "type": "file"}"""

    assertEquals(failure(body), Some("$.sha"))

  test("a directory entry that cannot be converted is reported at its index"):
    val body = """[{"name": "x", "path": "x", "sha": "abcdef12", "type": "dir"}, {"name": "y"}]"""

    assertEquals(failure(body), Some("$[1].path"))

  test("a path that would climb out of the repository is rejected at conversion"):
    val body = """{"name": "passwd", "path": "../../etc/passwd", "sha": "abcdef12", "type": "file"}"""

    assertEquals(failure(body), Some("$.path"))

  private def decoded(fixture: String): RepositoryContent =
    convert(golden(fixture)) match
      case Right(content) => content
      case Left(problem)  => fail(s"$fixture did not decode: ${problem.path.render} ${problem.message}")

  private def fileEntry(fixture: String): ContentEntry =
    decoded(fixture) match
      case RepositoryContent.File(entry) => entry
      case other                         => fail(s"expected the object arm, got $other")

  private def directoryEntries(fixture: String): Vector[ContentEntry] =
    decoded(fixture) match
      case RepositoryContent.Directory(entries) => entries
      case other                                => fail(s"expected the array arm, got $other")

  private def failure(body: String): Option[String] =
    convert(body).swap.toOption.map(_.path.render)

  private def convert(body: String): Either[DecodeFailure, RepositoryContent] =
    Json.decode[RepositoryContentDto](body).flatMap(_.toDomain)
