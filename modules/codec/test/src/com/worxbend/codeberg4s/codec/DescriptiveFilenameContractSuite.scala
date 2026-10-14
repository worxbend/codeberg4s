package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.wire.{ChangedFileDto, ReviewCommentDto}
import com.worxbend.codeberg4s.repositories.gitdata.wire.{CompareDto, FileResponseDto}
import com.worxbend.codeberg4s.repositories.wire.CommitAffectedFileDto
import com.worxbend.codeberg4s.repositories.wire.{CommitDto, ContentEntryDto, RepositoryContentDto}
import com.worxbend.codeberg4s.repositories.{ContentEntry, RepositoryContent}

import munit.FunSuite

/** Synthetic descriptive paths. These remain strings, not validated request ContentPaths. */
final class DescriptiveFilenameContractSuite extends FunSuite:

  private val paths: Vector[String] = Vector(" ", "   ", " padded / file ", "../outside")

  paths.foreach: path =>
    val raw  = Json.render(JsonValue.Str(path))
    val file = s"""{"filename":$raw}"""
    val link = symlink(s""","target":$raw""")

    test(s"standalone commit file preserves raw filename $raw"):
      assertEquals(Json.decode[CommitAffectedFileDto](file).map(_.filename), Right(Some(path)))
      assertEquals(Json.decode[CommitAffectedFileDto](file).flatMap(_.toDomain).map(_.filename), Right(path))

    test(s"commit embedded file preserves raw filename $raw"):
      val body = s"""{"sha":"cafebabe","files":[$file]}"""
      assertEquals(Json.decode[CommitDto](body).flatMap(_.toDomain).map(_.files.map(_.filename)), Right(Vector(path)))

    test(s"compare file preserves raw filename $raw"):
      val body = s"""{"files":[$file]}"""
      assertEquals(Json.decode[CompareDto](body).flatMap(_.toDomain).map(_.files.map(_.filename)), Right(Vector(path)))

    test(s"compare nested commit file preserves raw filename $raw"):
      val body   = s"""{"commits":[{"sha":"cafebabe","files":[$file]}]}"""
      val result = Json.decode[CompareDto](body).flatMap(_.toDomain)
      assertEquals(result.map(_.commits.flatMap(_.files.map(_.filename))), Right(Vector(path)))

    test(s"standalone PR file preserves raw filename $raw"):
      assertEquals(Json.decode[ChangedFileDto](file).map(_.filename), Right(Some(path)))
      assertEquals(Json.decode[ChangedFileDto](file).flatMap(_.toDomain).map(_.filename), Right(path))

    test(s"PR file listing preserves raw filename $raw"):
      val body   = s"""[{"filename":"ordinary"},$file]"""
      val result = Json.decode[Vector[ChangedFileDto]](body).flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
      assertEquals(result.map(_.map(_.filename)), Right(Vector("ordinary", path)))

    test(s"standalone renamed PR file preserves raw previous filename $raw"):
      val body = s"""{"filename":"new","previous_filename":$raw}"""
      assertEquals(Json.decode[ChangedFileDto](body).map(_.previousFilename), Right(Some(path)))
      assertEquals(Json.decode[ChangedFileDto](body).flatMap(_.toDomain).map(_.previousFilename), Right(Some(path)))

    test(s"PR listing preserves raw optional rename path $raw"):
      val body   = s"""[{"filename":"ordinary"},{"filename":"new","previous_filename":$raw}]"""
      val result = Json.decode[Vector[ChangedFileDto]](body).flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
      assertEquals(result.map(_.map(_.previousFilename)), Right(Vector(None, Some(path))))

    test(s"standalone review comment preserves raw optional path $raw"):
      val body = s"""{"id":1,"path":$raw}"""
      assertEquals(Json.decode[ReviewCommentDto](body).map(_.path), Right(Some(path)))
      assertEquals(Json.decode[ReviewCommentDto](body).flatMap(_.toDomain).map(_.path), Right(Some(path)))

    test(s"review comment listing preserves raw optional path $raw"):
      val body   = s"""[{"id":1},{"id":2,"path":$raw}]"""
      val result = Json.decode[Vector[ReviewCommentDto]](body).flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
      assertEquals(result.map(_.map(_.path)), Right(Vector(None, Some(path))))

    test(s"standalone symlink preserves raw optional target $raw"):
      assertEquals(Json.decode[ContentEntryDto](link).map(_.target), Right(Some(path)))
      assertEquals(target(domain(Json.decode[ContentEntryDto](link).flatMap(_.toDomain))), Some(path))

    test(s"contents union preserves raw symlink target $raw"):
      val result = domain(Json.decode[RepositoryContentDto](s"[$link]").flatMap(_.toDomain))
      result match
        case RepositoryContent.Directory(entries) => assertEquals(entries.map(target), Vector(Some(path)))
        case other                                => fail(s"expected directory, got $other")

    test(s"file response preserves embedded raw symlink target $raw"):
      val body   = s"""{"content":$link}"""
      val result = domain(Json.decode[FileResponseDto](body).flatMap(_.toDomain))
      assertEquals(result.content.map(target), Some(Some(path)))

  test("commit listing retains distinct whitespace-only and padded descriptive filenames"):
    val commits = paths.map: path =>
      val raw = Json.render(JsonValue.Str(path))
      s"""{"sha":"cafebabe","files":[{"filename":$raw}]}"""
    val body    = commits.mkString("[", ",", "]")
    val result  = Json.decode[Vector[CommitDto]](body).flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
    assertEquals(result.map(_.flatMap(_.files.map(_.filename))), Right(paths))

  Vector("", ",\"filename\":null", ",\"filename\":\"\"", ",\"filename\":7").foreach: field =>
    test(s"commit filename still requires a nonempty string for $field"):
      val body = s"{$field}".replace("{,", "{")
      assertPath(Json.decode[CommitAffectedFileDto](body).flatMap(_.toDomain), "$.filename")

    test(s"embedded commit filename keeps its missing-field path for $field"):
      val body = s"""{"sha":"cafebabe","files":[{"filename":"ok"},{${field.stripPrefix(",")}}]}"""
      assertPath(Json.decode[CommitDto](body).flatMap(_.toDomain), "$.files[1].filename")

    test(s"PR filename still requires a nonempty string for $field"):
      val body = s"{$field}".replace("{,", "{")
      assertPath(Json.decode[ChangedFileDto](body).flatMap(_.toDomain), "$.filename")

    test(s"PR listing keeps its missing filename index for $field"):
      val body   = s"""[{"filename":"ok"},{${field.stripPrefix(",")}}]"""
      val result = Json.decode[Vector[ChangedFileDto]](body).flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
      assertPath(result, "$[1].filename")

  Vector("", "null", "\"\"", "7").foreach: value =>
    val rename = optionalField("previous_filename", value)
    val path   = optionalField("path", value)
    val link   = symlink(optionalField("target", value))

    test(s"empty absent or wrong-kind previous filename remains absent for $value"):
      val body = s"""{"filename":"new"$rename}"""
      assertEquals(Json.decode[ChangedFileDto](body).flatMap(_.toDomain).map(_.previousFilename), Right(None))

    test(s"empty absent or wrong-kind review comment path remains absent for $value"):
      val body = s"""{"id":1$path}"""
      assertEquals(Json.decode[ReviewCommentDto](body).flatMap(_.toDomain).map(_.path), Right(None))

    test(s"empty absent or wrong-kind symlink target remains absent for $value"):
      assertEquals(target(domain(Json.decode[ContentEntryDto](link).flatMap(_.toDomain))), None)

  private def optionalField(key: String, value: String): String =
    if value.isEmpty then "" else s""","$key":$value"""

  private def symlink(extra: String): String =
    s"""{"name":"link","path":"link","sha":"cafebabe","type":"symlink"$extra}"""

  private def target(entry: ContentEntry): Option[String] =
    entry match
      case ContentEntry.Symlink(_, value) => value
      case other                          => fail(s"expected symlink, got $other")

  private def domain[A](result: Either[DecodeFailure, A]): A =
    result.fold(problem => fail(s"${problem.path.render}: ${problem.message}"), identity)

  private def assertPath[A](result: Either[DecodeFailure, A], expected: String): Unit =
    assertEquals(result.swap.toOption.map(_.path.render), Some(expected))
