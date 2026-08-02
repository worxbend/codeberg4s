package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.ContentEntry
import com.worxbend.codeberg4s.repositories.FileContent

import munit.FunSuite

/** The two entry kinds the golden captures never contain, and the encodings the captures never use.
  *
  * `docs/HAZARDS.md` §3 records that a `symlink` and a `submodule` share the element schema of a `file` and a `dir` —
  * only `type` says which, and `type` decides which of the payload's other fields mean anything. Codeberg's
  * `Codeberg/Community` repository has neither kind, so no capture can prove the dispatch. What this suite does instead
  * is take a captured entry verbatim and change nothing but the fields that decide the kind, so every other key stays
  * exactly as the live instance sent it: an entry that stops decoding for an unrelated reason still fails here.
  *
  * The same trick covers the encoding arm of `content`. Forgejo has only ever sent `base64`, so the branch that keeps
  * an unknown encoding verbatim cannot be reached from a capture either, and it is the branch where guessing would hand
  * a caller silent rubbish.
  */
final class ContentEntryKindsSuite extends FunSuite with GoldenFixtures:

  /** The directory capture, whose first entry is a `dir` with `content`, `encoding` and `target` all JSON `null`. */
  private val DirectoryCapture: String = "repository/contents-dir-small.json"

  /** The single-file capture, the one entry in the golden set that carries a base64 payload. */
  private val FileCapture: String = "repository/contents-file.json"

  test("a symlink entry becomes Symlink and carries what the link points at"):
    entry(DirectoryCapture, "type" -> ujson.Str("symlink"), "target" -> ujson.Str("../shared/README.md")) match
      case ContentEntry.Symlink(meta, target) =>
        assertEquals(target, Some("../shared/README.md"))
        assertEquals(meta.name, ".forgejo")
      case other                              => fail(s"expected a symlink, got $other")

  test("a symlink whose target the instance did not send is still a symlink, with no target"):
    entry(DirectoryCapture, "type" -> ujson.Str("symlink"), "target" -> ujson.Null) match
      case ContentEntry.Symlink(_, target) => assertEquals(target, None)
      case other                           => fail(s"expected a symlink, got $other")

  test("a submodule entry becomes Submodule and carries the clone url .gitmodules records"):
    val gitmodules = "https://codeberg.org/forgejo/forgejo.git"

    entry(DirectoryCapture, "type" -> ujson.Str("submodule"), "submodule_git_url" -> ujson.Str(gitmodules)) match
      case ContentEntry.Submodule(meta, gitUrl) =>
        assertEquals(gitUrl, Some(gitmodules))
        assertEquals(meta.name, ".forgejo")
      case other                                => fail(s"expected a submodule, got $other")

  test("a submodule the repository records no clone url for is still a submodule"):
    entry(DirectoryCapture, "type" -> ujson.Str("submodule"), "submodule_git_url" -> ujson.Null) match
      case ContentEntry.Submodule(_, gitUrl) => assertEquals(gitUrl, None)
      case other                             => fail(s"expected a submodule, got $other")

  test("the type alone decides the case, so a payload's file bytes do not follow it onto another kind"):
    entry(FileCapture, "type" -> ujson.Str("symlink"), "target" -> ujson.Str("docs/README.md")) match
      case ContentEntry.Symlink(meta, target) =>
        assertEquals(meta.name, "README.md")
        assertEquals(meta.size, 2790L)
        assertEquals(target, Some("docs/README.md"))
      case other                              => fail(s"expected a symlink, got $other")

  test("an encoding this library does not implement keeps the payload verbatim and refuses to guess at bytes"):
    fileContent(entry(FileCapture, "encoding" -> ujson.Str("hex"))) match
      case Some(payload @ FileContent.Opaque(encoding, raw)) =>
        assertEquals(encoding, Some("hex"))
        assertEquals(raw, capturedPayload)
        assertEquals(payload.text, None)
      case other                                             => fail(s"expected an opaque payload, got $other")

  test("content sent without any encoding is kept verbatim rather than assumed to be base64"):
    fileContent(entry(FileCapture, "encoding" -> ujson.Null)) match
      case Some(payload @ FileContent.Opaque(encoding, raw)) =>
        assertEquals(encoding, None)
        assertEquals(raw, capturedPayload)
        assertEquals(payload.text, None)
      case other                                             => fail(s"expected an opaque payload, got $other")

  test("the encoding name is matched ignoring case and surrounding space, since only its spelling varies"):
    fileContent(entry(FileCapture, "encoding" -> ujson.Str("  BASE64 "))) match
      case Some(payload @ FileContent.Base64(_)) =>
        assert(payload.text.exists(_.contains("Welcome to Forgejo")), "the payload did not decode as base64")
      case other                                 => fail(s"expected a base64 payload, got $other")

  test("an encoding without content is nothing at all, because neither field means anything alone"):
    fileContent(entry(FileCapture, "content" -> ujson.Null)) match
      case None  => ()
      case other => fail(s"expected no payload, got $other")

  /** The first contents object of a capture, as a plain map, with `overrides` applied on top. */
  private def derived(fixture: String, overrides: Seq[(String, ujson.Value)]): Map[String, ujson.Value] =
    val parsed = ujson.read(golden(fixture))

    parsed.arrOpt.flatMap(_.headOption).getOrElse(parsed).objOpt match
      case Some(fields) => fields.toMap ++ overrides
      case None         => fail(s"$fixture does not hold a contents object")

  /** Decodes a derived entry the same way the endpoint does, failing the test if it does not convert. */
  private def entry(fixture: String, overrides: (String, ujson.Value)*): ContentEntry =
    val body = ujson.write(ujson.Obj.from(derived(fixture, overrides)))

    Json.decode[ContentEntryDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(problem) => fail(s"did not convert: ${problem.path.render} ${problem.message}")

  private def fileContent(value: ContentEntry): Option[FileContent] =
    value match
      case ContentEntry.File(_, payload, _) => payload
      case other                            => fail(s"expected a file entry, got $other")

  /** The base64 payload exactly as the capture holds it, so the verbatim assertions above compare against the wire. */
  private def capturedPayload: String =
    derived(FileCapture, Seq.empty).get("content").flatMap(_.strOpt) match
      case Some(value) => value
      case None        => fail("the file capture stopped carrying a content payload")
