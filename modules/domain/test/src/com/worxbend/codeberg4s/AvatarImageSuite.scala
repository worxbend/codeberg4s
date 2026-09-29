package com.worxbend.codeberg4s

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64

/** [[AvatarImage]]: the one avatar type, shared by the user, organisation and repository avatar endpoints.
  *
  * The assertions are the contract each constructor's Scaladoc states — bytes always encode, text is validated as
  * standard base64 and only trimmed, never re-encoded.
  */
final class AvatarImageSuite extends FunSuite:

  private val Png: Array[Byte] = "PNGDATA".getBytes(StandardCharsets.UTF_8)

  private val Encoded: String = Base64.getEncoder.encodeToString(Png)

  test("bytes become standard base64, which is what the endpoints decode"):
    assertEquals(AvatarImage.ofBytes(Png).base64, Encoded)

  test("encoding bytes cannot fail, not even for an empty image"):
    assertEquals(AvatarImage.ofBytes(Array.emptyByteArray).base64, "")

  test("an already-encoded value round-trips through the decoder"):
    assertEquals(AvatarImage.ofBase64(Encoded).toOption.map(_.base64), Some(Encoded))

  test("an already-encoded value is trimmed, because a blob copied from a file carries a newline"):
    assertEquals(AvatarImage.ofBase64(s"  $Encoded\n").toOption.map(_.base64), Some(Encoded))

  test("an already-encoded value is taken verbatim and never re-encoded"):
    assertEquals(AvatarImage.ofBase64("aGk=").toOption.map(_.base64), Some("aGk="))

  test("an already-encoded value rejects a blank string, on the avatarImage field"):
    assertEquals(AvatarImage.ofBase64("   ").swap.toOption.map(_.field), Some("avatarImage"))

  test("an already-encoded value rejects text that is not base64, so the mistake is not discovered from a 4xx"):
    assertEquals(AvatarImage.ofBase64("not base64 at all").swap.toOption.map(_.field), Some("avatarImage"))

  test("an already-encoded value rejects MIME-style line wrapping, which the instance would refuse"):
    val wrapped = s"${"QUFB" * 19}\n${"QUFB" * 19}"

    assert(AvatarImage.ofBase64(wrapped).isLeft, "a wrapped blob must be refused")
