package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{GoldenFixtures, Json, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.ChangedFile
import com.worxbend.codeberg4s.repositories.CommitFileStatus

import munit.FunSuite

/** [[ChangedFileDto]] against `golden/pull/files-list.json`. */
final class ChangedFileDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden changed-file listing converts field for field"):
    files.headOption match
      case Some(file) =>
        assertEquals(file.filename, "modules/git/hook_generate.go")
        assertEquals(file.status, Some(CommitFileStatus.Changed))
        assertEquals(file.additions, 17L)
        assertEquals(file.deletions, 17L)
        assertEquals(file.changes, 34L)
      case None       => fail("the fixture was expected to hold one changed file")

  test("Forgejo's own total is read rather than recomputed from the two counts"):
    assertEquals(files.map(_.changes), Vector(34L))

  test("a file that was not renamed carries no previous name"):
    assertEquals(files.headOption.flatMap(_.previousFilename), None)

  test("the three URLs are kept apart, because they answer three different questions"):
    files.headOption match
      case Some(file) =>
        assert(file.htmlUrl.exists(_.contains("/src/commit/")), s"unexpected html url: ${file.htmlUrl}")
        assert(file.contentsUrl.exists(_.contains("/api/v1/")), s"unexpected contents url: ${file.contentsUrl}")
        assert(file.rawUrl.exists(_.contains("/raw/commit/")), s"unexpected raw url: ${file.rawUrl}")
      case None       => fail("the fixture was expected to hold one changed file")

  test("a status this library does not recognise costs the status, not the file"):
    assertEquals(convert("""{"filename":"a.go","status":"teleported"}""").map(_.status), Right(None))

  test("absent counts become zero rather than failing the file"):
    assertEquals(convert("""{"filename":"a.go"}""").map(_.additions), Right(0L))

  test("a changed file with no filename is a decoding failure at $.filename"):
    assertEquals(failingPath("""{"status":"added"}"""), "$.filename")

  test("a failing element of a list reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[ChangedFileDto]]("""[{"filename":"a.go"},{"status":"added"}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].filename")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a garbage body is a DecodeFailure, not an escaping codec exception"):
    assert(Json.decode[ChangedFileDto]("not json at all").isLeft)

  private def files: Vector[ChangedFile] =
    Json
      .decode[Vector[ChangedFileDto]](golden("pull/files-list.json"))
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode the changed-file listing: $failure")

  private def convert(body: String): Either[DecodeFailure, ChangedFile] =
    Json.decode[ChangedFileDto](body).flatMap(_.toDomain)

  private def failingPath(body: String): String =
    convert(body) match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")
