package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.RepositoryContent
import com.worxbend.codeberg4s.repositories.admin.wire.FilesResponseDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.{FileResponseDto, GitEntryDto, GitTreeDto}
import com.worxbend.codeberg4s.repositories.wire.{ContentEntryDto, RepositoryContentDto}

import munit.FunSuite

/** Synthetic wire identities, not live captures. Runtime wire paths must not use the legacy trimming constructor. */
final class WireFilenameIdentitySuite extends FunSuite:

  private val paths: Vector[String] = Vector(
    " README.md",
    "README.md ",
    "  README.md  ",
    " ",
    "   ",
    "dir / file ",
    "  /   ",
    " %?#é.txt ",
  )

  paths.foreach: path =>
    val name = path.split("/", -1).last
    val body = contentBody(path, name)

    test(s"contents preserve the raw name and path ${Json.render(JsonValue.Str(path))}"):
      val entry = domain(Json.decode[ContentEntryDto](body).flatMap(_.toDomain))
      assertEquals(entry.meta.name, name)
      assertEquals(entry.meta.path.value, path)
      assertEquals(entry.meta.path.name, name)
      assertEquals(entry.meta.path.segments, path.split("/", -1).toList)

    test(s"git entries preserve the path ${Json.render(JsonValue.Str(path))}"):
      val entry = domain(Json.decode[GitEntryDto](body).flatMap(_.toDomain))
      assertEquals(entry.path.value, path)
      assertEquals(entry.path.segments, path.split("/", -1).toList)

  test("contents union keeps padded and unpadded sibling identities distinct"):
    val paths = Vector("README.md", " README.md", "README.md ", "   ")
    val body  = paths.map(path => contentBody(path, path)).mkString("[", ",", "]")
    domain(Json.decode[RepositoryContentDto](body).flatMap(_.toDomain)) match
      case RepositoryContent.Directory(entries) => assertEquals(entries.map(_.meta.path.value), paths)
      case other                                => fail(s"expected a directory, got $other")

  test("tree envelope keeps padded and unpadded sibling identities distinct"):
    val paths   = Vector("README.md", " README.md", "README.md ", "   ")
    val entries = paths.map(path => contentBody(path, path)).mkString("[", ",", "]")
    val tree    = domain(Json.decode[GitTreeDto](s"""{"tree":$entries}""").flatMap(_.toDomain))
    assertEquals(tree.map(_.path.value), paths)

  test("a file response retains an embedded whitespace-only content name"):
    val body   = s"""{"content":${contentBody("   ", "   ")}}"""
    val change = domain(Json.decode[FileResponseDto](body).flatMap(_.toDomain))
    assertEquals(change.content.map(_.meta.name), Some("   "))
    assertEquals(change.content.map(_.meta.path.value), Some("   "))

  test("a multi-file response retains an embedded padded content path"):
    val body    = s"""{"files":[${contentBody(" dir / file ", " file ")}]}"""
    val changes = domain(Json.decode[FilesResponseDto](body).flatMap(_.toDomain))
    assertEquals(changes.files.map(_.meta.path.value), Vector(" dir / file "))
    assertEquals(changes.files.map(_.meta.name), Vector(" file "))

  Vector("", "/absolute", "a//b", ".", "..", "a/../b", "a/./b", "a\tb", "a\nb").foreach: path =>
    test(s"wire contents still refuse unsafe path ${Json.render(JsonValue.Str(path))}"):
      val result = Json.decode[ContentEntryDto](contentBody(path, "entry")).flatMap(_.toDomain)
      assertEquals(result.swap.toOption.map(_.path.render), Some("$.path"))

    test(s"wire git entries still refuse unsafe path ${Json.render(JsonValue.Str(path))}"):
      val result = Json.decode[GitEntryDto](contentBody(path, "entry")).flatMap(_.toDomain)
      assertEquals(result.swap.toOption.map(_.path.render), Some("$.path"))

  private def contentBody(path: String, name: String): String =
    Json.render(JsonValue.Obj(Vector(
      "name" -> JsonValue.Str(name),
      "path" -> JsonValue.Str(path),
      "sha"  -> JsonValue.Str("cafebabe"),
      "type" -> JsonValue.Str("file"),
    )))

  private def domain[A](result: Either[DecodeFailure, A]): A =
    result.fold(problem => fail(s"${problem.path.render}: ${problem.message}"), identity)
