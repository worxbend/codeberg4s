package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** [[UploadAsset]]'s constructor is a header-injection boundary, so most of this suite is about what it refuses. */
final class UploadAssetSuite extends FunSuite:

  private val Bytes: Array[Byte] = "checksums".getBytes(StandardCharsets.UTF_8)

  private val Bell: String = 7.toChar.toString

  test("an ordinary file name is accepted and the media type defaults to octet-stream"):
    val upload = accepted("forgejo-16.0.2-linux-amd64")

    assertEquals(upload.fileName, "forgejo-16.0.2-linux-amd64")
    assertEquals(upload.mediaType, UploadAsset.DefaultMediaType)
    assertEquals(upload.name, None)
    assertEquals(upload.size, Bytes.length)

  test("the default media type is the one core sends binary bodies under"):
    // Asserted rather than imported: the domain module depends on nothing, so
    // the two constants agree by test and not by a compile-time reference.
    assertEquals(UploadAsset.DefaultMediaType, "application/octet-stream")

  test("surrounding whitespace in the file name is trimmed"):
    assertEquals(accepted("  notes.txt  ").fileName, "notes.txt")

  test("a blank file name is rejected"):
    assertEquals((rejected("  ").field, rejected("  ").message), ("fileName", "must not be blank"))

  test("a quotation mark is rejected, because it would close the Content-Disposition parameter"):
    assertEquals(rejected("evil\".txt").message, "must not contain a quotation mark or a line break")

  test("a carriage return or a newline is rejected, because either would inject a header"):
    assertEquals(rejected("a\r\nX-Injected: 1").message, "must not contain a quotation mark or a line break")

  test("another control character is rejected too"):
    assertEquals(rejected("a\tb").message, "must not contain a control character")

  test("a slash is allowed — a multipart file name is a quoted parameter, not a path segment"):
    assertEquals(accepted("linux/amd64.tar.gz").fileName, "linux/amd64.tar.gz")

  test("the stored name is separate from the file name, so a local path can be uploaded under a release name"):
    val upload = accepted("out.tar.gz").named("forgejo-16.0.2-linux-amd64")

    assertEquals(upload.fileName, "out.tar.gz")
    assertEquals(upload.name, Some("forgejo-16.0.2-linux-amd64"))

  test("the media type can be stated"):
    assertEquals(accepted("notes.txt").as("text/plain").map(_.mediaType), Right("text/plain"))

  test("surrounding whitespace in the media type is trimmed"):
    assertEquals(accepted("notes.txt").as("  text/plain  ").map(_.mediaType), Right("text/plain"))

  test("a blank media type is rejected, and the rejection names the mediaType field"):
    assertEquals((refusedMedia("   ").field, refusedMedia("   ").message), ("mediaType", "must not be blank"))

  test("a media type carrying a line break is rejected, because it would inject a header into the part"):
    val injected = refusedMedia("text/plain\r\nX-Injected: 1")

    assertEquals((injected.field, injected.message), ("mediaType", "must not contain a control character"))

  test("any other control character in the media type is rejected too"):
    // Mid-string on purpose: `trim` removes every character below a space,
    // so a control character at either end never reaches the check.
    assertEquals(refusedMedia(s"text/${Bell}plain").message, "must not contain a control character")

  test("the bytes are held, not copied — the caller keeps ownership of the array"):
    val content = "one".getBytes(StandardCharsets.UTF_8)
    val upload  = UploadAsset.of("one.txt", content) match
      case Right(value) => value
      case Left(error)  => fail(s"expected acceptance, got ${error.message}")

    assert(upload.content eq content, "the constructor copied the array, which the Scaladoc promises it does not")

  private def accepted(fileName: String): UploadAsset =
    UploadAsset.of(fileName, Bytes) match
      case Right(upload) => upload
      case Left(error)   => fail(s"expected $fileName to be accepted, got ${error.message}")

  private def rejected(fileName: String): ValidationError =
    UploadAsset.of(fileName, Bytes) match
      case Left(error)   => error
      case Right(upload) => fail(s"expected $fileName to be rejected, got ${upload.fileName}")

  private def refusedMedia(media: String): ValidationError =
    accepted("notes.txt").as(media) match
      case Left(error)   => error
      case Right(upload) => fail(s"expected the media type to be rejected, got ${upload.mediaType}")
