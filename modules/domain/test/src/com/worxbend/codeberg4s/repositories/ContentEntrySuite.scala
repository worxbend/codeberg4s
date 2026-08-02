package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[ContentEntry]] exists so that a directory cannot be asked for its symlink target. What has to hold is that
  * [[ContentEntry.kind]] agrees with the case a caller matched on: a caller who branches on `kind` and a caller who
  * pattern-matches must not reach different conclusions about the same entry.
  */
final class ContentEntrySuite extends FunSuite:

  private val Meta: ContentMeta = meta("README.md")

  private val Blob: ContentEntry =
    ContentEntry.File(Meta, Some(FileContent.Base64("aGk=")), Some("https://codeberg.org/raw/README.md"))

  private val Tree: ContentEntry = ContentEntry.Directory(meta("docs"))

  private val Link: ContentEntry = ContentEntry.Symlink(meta("latest"), Some("../v1/README.md"))

  private val Nested: ContentEntry = ContentEntry.Submodule(meta("vendor"), Some("https://codeberg.org/forgejo/act"))

  test("a file reports the file kind"):
    assertEquals(Blob.kind, ContentKind.File)

  test("a directory reports the directory kind, whose wire spelling is the abbreviated dir"):
    assertEquals(Tree.kind, ContentKind.Directory)
    assertEquals(Tree.kind.wireName, "dir")

  test("a symlink reports the symlink kind"):
    assertEquals(Link.kind, ContentKind.Symlink)

  test("a submodule reports the submodule kind"):
    assertEquals(Nested.kind, ContentKind.Submodule)

  test("kind never disagrees with the case a caller would match on"):
    List(Blob, Tree, Link, Nested).foreach: entry =>
      val matched = entry match
        case ContentEntry.File(_, _, _)   => ContentKind.File
        case ContentEntry.Directory(_)    => ContentKind.Directory
        case ContentEntry.Symlink(_, _)   => ContentKind.Symlink
        case ContentEntry.Submodule(_, _) => ContentKind.Submodule

      assertEquals(entry.kind, matched)

  test("every kind is reachable from some entry, so no case reports another's kind"):
    assertEquals(
      List(Blob, Tree, Link, Nested).map(_.kind.wireName).distinct.sorted,
      ContentKind.values.toList.map(_.wireName).sorted,
    )

  test("meta is available on every kind without matching, which is why it is not a case field"):
    assertEquals(List(Blob, Tree, Link, Nested).map(_.meta.name), List("README.md", "docs", "latest", "vendor"))

  test("a listed file carries no content, which means not sent and never an empty file"):
    val listed = ContentEntry.File(Meta, None, None)

    assertEquals(listed.kind, ContentKind.File)
    listed match
      case ContentEntry.File(_, content, downloadUrl) =>
        assertEquals(content, None)
        assertEquals(downloadUrl, None)
      case other                                      => fail(s"expected a file, got ${other.kind.wireName}")

  test("a file fetched by its own path carries its base64 bytes, which decode to the text"):
    Blob match
      case ContentEntry.File(_, Some(content), _) => assertEquals(content.text, Some("hi"))
      case other                                  => fail(s"expected a file with content, got ${other.kind.wireName}")

  test("a symlink target is kept verbatim, including one that escapes the repository"):
    Link match
      case ContentEntry.Symlink(_, target) => assertEquals(target, Some("../v1/README.md"))
      case other                           => fail(s"expected a symlink, got ${other.kind.wireName}")

  test("a submodule with no recorded clone URL is representable, and is still a submodule"):
    val unrecorded = ContentEntry.Submodule(meta("vendor"), None)

    assertEquals(unrecorded.kind, ContentKind.Submodule)
    unrecorded match
      case ContentEntry.Submodule(_, gitUrl) => assertEquals(gitUrl, None)
      case other                             => fail(s"expected a submodule, got ${other.kind.wireName}")

  private def meta(name: String): ContentMeta =
    ContentMeta(
      name           = name,
      path           = orFail(ContentPath.from(name)),
      sha            = orFail(CommitSha.from("1bdb1938c1a2b1e9f0d3")),
      size           = 3L,
      lastCommitSha  = None,
      lastCommitWhen = None,
      url            = None,
      htmlUrl        = None,
      gitUrl         = None,
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
