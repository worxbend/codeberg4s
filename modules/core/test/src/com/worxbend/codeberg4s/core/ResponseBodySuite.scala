package com.worxbend.codeberg4s.core

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** [[ResponseBody]] is where the library stopped turning every response into text.
  *
  * Two things here are worth more than the rest. The first is that the charset is '''read''' rather than assumed: sttp
  * used to pick it off `Content-Type` inside `asStringAlways`, and moving the byte-to-text step out of the transport
  * would have silently thrown that away. The second is [[ResponseBody.excerpt]], which bounds a number of characters
  * over an array of bytes — the operation with the trap in it, since slicing bytes at a fixed length cuts a multi-byte
  * character in half.
  */
final class ResponseBodySuite extends FunSuite:

  /** Three bytes in UTF-8, one character. */
  private val euro: String = "€"

  /** Four bytes in UTF-8, two characters — a surrogate pair. */
  private val grin: String = "😀"

  /** What a decoder writes where it could not read a character, and therefore the marker of a byte slice that cut one
    * in half.
    */
  private val replacement: Char = '�'

  private def utf8(text: String): ResponseBody = ResponseBody.utf8(text)

  // --- bytes and text -------------------------------------------------------

  test("the bytes come back exactly as they went in"):
    val raw = Array[Byte](-1, -2, 0, 65)

    assertEquals(ResponseBody.of(raw, StandardCharsets.UTF_8).bytes.toList, raw.toList)

  test("bytes are not copied, because copying is the cost this type exists to avoid"):
    val raw = Array[Byte](1, 2, 3)

    assert(ResponseBody.of(raw, StandardCharsets.UTF_8).bytes.eq(raw))

  test("text decodes with the charset the response declared"):
    val latin1 = ResponseBody.of("café".getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1)

    assertEquals(latin1.text, "café")

  test("bytes that are invalid in the declared charset become replacement characters, not a failure"):
    val broken = ResponseBody.of(Array[Byte](-1, -2), StandardCharsets.UTF_8)

    assert(broken.text.forall(_.equals(replacement)), s"expected only replacement characters, got ${broken.text}")

  test("size and isEmpty report the bytes, not the decoded text"):
    assertEquals(utf8(euro).size, 3)
    assert(!utf8(euro).isEmpty)
    assert(ResponseBody.Empty.isEmpty)
    assertEquals(ResponseBody.Empty.size, 0)

  // --- blankness ------------------------------------------------------------

  test("a body of ASCII whitespace is blank, and so is an empty one"):
    assert(ResponseBody.Empty.isBlank)
    assert(utf8("").isBlank)
    assert(utf8(" \t\r\n\f").isBlank)

  test("a body with anything else in it is not blank"):
    assert(!utf8("{}").isBlank)
    assert(!utf8("  x  ").isBlank)
    assert(!utf8(euro).isBlank)

  // --- the UTF-8 requirement ------------------------------------------------

  test("utf8Bytes hands back the same array when the response declared UTF-8, so the JSON path copies nothing"):
    val body = utf8("""{"a":1}""")

    assert(body.utf8Bytes.eq(body.bytes))

  test("utf8Bytes transcodes when the response declared something else"):
    val latin1 = ResponseBody.of("café".getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1)

    assertEquals(String(latin1.utf8Bytes, StandardCharsets.UTF_8), "café")
    assert(!latin1.utf8Bytes.eq(latin1.bytes))

  // --- charset negotiation --------------------------------------------------

  test("a response with no content type is read as UTF-8"):
    assertEquals(ResponseBody.charsetOf(None), StandardCharsets.UTF_8)

  test("a content type with no charset parameter is read as UTF-8"):
    assertEquals(ResponseBody.charsetOf(Some("application/json")), StandardCharsets.UTF_8)

  test("the declared charset is honoured, whatever case it was written in"):
    assertEquals(ResponseBody.charsetOf(Some("text/plain; CharSet=ISO-8859-1")), StandardCharsets.ISO_8859_1)

  test("a quoted charset value is unquoted, as RFC 9110 allows"):
    assertEquals(ResponseBody.charsetOf(Some("""text/plain; charset="utf-8"""")), StandardCharsets.UTF_8)

  test("a charset parameter after another parameter is still found"):
    assertEquals(ResponseBody.charsetOf(Some("text/plain; boundary=x; charset=US-ASCII")), StandardCharsets.US_ASCII)

  test("an unknown or illegal charset name falls back to UTF-8 rather than failing the request"):
    assertEquals(ResponseBody.charsetOf(Some("text/plain; charset=klingon-1")), StandardCharsets.UTF_8)
    assertEquals(ResponseBody.charsetOf(Some("text/plain; charset=not a name")), StandardCharsets.UTF_8)
    assertEquals(ResponseBody.charsetOf(Some("text/plain; charset=")), StandardCharsets.UTF_8)

  test("what Forgejo actually sends is read as UTF-8"):
    assertEquals(ResponseBody.charsetOf(Some("application/json;charset=utf-8")), StandardCharsets.UTF_8)

  // --- excerpt --------------------------------------------------------------

  test("a body shorter than the bound comes back whole"):
    assertEquals(utf8("""{"a":1}""").excerpt(512), """{"a":1}""")

  test("a body longer than the bound is cut at that many characters"):
    assertEquals(utf8("x".repeat(2000)).excerpt(512), "x".repeat(512))

  test("the bound counts characters, so a three-byte character is one of them"):
    // The whole point. 512 euro signs are 1,536 bytes; a bound applied to bytes
    // would have answered 512 bytes, which is 170 characters and a broken one.
    assertEquals(utf8(euro.repeat(2000)).excerpt(512), euro.repeat(512))

  test("cutting never splits a multi-byte character"):
    val excerpt = utf8(euro.repeat(2000)).excerpt(512)

    assert(!excerpt.contains(replacement), "a replacement character means the byte slice cut a character in half")

  test("a four-byte character, which is two characters, is also counted correctly"):
    val excerpt = utf8(grin.repeat(2000)).excerpt(512)

    assertEquals(excerpt, grin.repeat(256))
    assert(!excerpt.contains(replacement))

  test("a bound of zero or less yields nothing"):
    assertEquals(utf8("payload").excerpt(0), "")
    assertEquals(utf8("payload").excerpt(-1), "")

  test("an empty body excerpts to nothing"):
    assertEquals(ResponseBody.Empty.excerpt(512), "")

  test("a charset that is not ASCII-compatible still excerpts the right number of characters"):
    val utf16 = ResponseBody.of("x".repeat(2000).getBytes(StandardCharsets.UTF_16), StandardCharsets.UTF_16)

    assertEquals(utf16.excerpt(512), "x".repeat(512))

  // --- value semantics ------------------------------------------------------

  test("two bodies with the same bytes and charset are equal, and hash alike"):
    val one = utf8("payload")
    val two = utf8("payload")

    assertEquals(one, two)
    assertEquals(one.hashCode, two.hashCode)

  test("bodies differing in bytes or in charset are not equal"):
    val ascii = ResponseBody.of("payload".getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII)

    assertNotEquals(utf8("payload"), utf8("other"))
    assertNotEquals[Any, Any](utf8("payload"), ascii)

  test("a body is not equal to something that is not a body"):
    assertNotEquals[Any, Any](utf8("payload"), "payload")

  test("toString reports the size and the charset and never the payload"):
    val rendered = utf8("s3cret-token").toString

    assert(!rendered.contains("s3cret"), s"a body must not render its payload, got $rendered")
    assert(rendered.contains("12 B"), rendered)
    assert(rendered.contains("UTF-8"), rendered)
