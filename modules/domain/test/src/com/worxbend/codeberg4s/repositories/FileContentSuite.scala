package com.worxbend.codeberg4s.repositories

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

final class FileContentSuite extends FunSuite:

  private val Text: String = "# Welcome to Forgejo\n"

  private val Bytes: Array[Byte] = Text.getBytes(StandardCharsets.UTF_8)

  private val Encoded: String = JavaBase64.getEncoder.encodeToString(Bytes)

  test("base64 content decodes to the bytes the file holds"):
    assertEquals(FileContent.Base64(Encoded).decoded.map(_.length), Some(Bytes.length))

  test("base64 content reads back as UTF-8 text"):
    assertEquals(FileContent.Base64(Encoded).text, Some(Text))

  test("the encoded payload is kept verbatim"):
    assertEquals(FileContent.Base64(Encoded).raw, Encoded)

  test("a payload that cannot be base64 answers None instead of throwing"):
    assertEquals(FileContent.Base64("A").decoded, None)

  test("an encoding this library does not implement answers None rather than guessing"):
    assertEquals(FileContent.Opaque(Some("uuencode"), Encoded).decoded, None)

  test("an unimplemented encoding still keeps the payload"):
    assertEquals(FileContent.Opaque(Some("uuencode"), Encoded).raw, Encoded)

  test("base64 with embedded line breaks decodes, since MIME base64 permits them"):
    assertEquals(FileContent.Base64(Encoded.grouped(4).mkString("\n")).text, Some(Text))
