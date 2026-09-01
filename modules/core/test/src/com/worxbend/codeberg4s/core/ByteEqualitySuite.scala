package com.worxbend.codeberg4s.core

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** The core values that carry an `Array[Byte]`, and the one rule they all have to obey: two of them holding the same
  * bytes are equal.
  *
  * They are tested together because the defect is one defect. An array's `equals` in Scala is '''identity''', so the
  * equality a case class generates for a byte field compares two archives by reference — `download(a) == download(a)`
  * answers `false`, a `Set` keeps both copies, and nothing warns. Every assertion below would have passed by accident
  * if the field were a `String`, which is exactly why the rule is worth pinning down for the byte case.
  *
  * `hashCode` is asserted alongside every equality, because equal values that hash differently break a `Set` and a
  * `Map` just as thoroughly as unequal ones.
  */
final class ByteEqualitySuite extends FunSuite:

  private def bytes(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  private val Headers: Map[String, List[String]] = Map("content-type" -> List("application/zip"))

  // --- ResponseBody, and the response that carries it -----------------------

  private def zip(text: String): CodebergResponse =
    CodebergResponse(200, Headers, ResponseBody.of(bytes(text), StandardCharsets.UTF_8))

  test("two bodies carrying equal-but-distinct arrays are equal and hash alike"):
    val one = ResponseBody.of(bytes("PK-archive"), StandardCharsets.UTF_8)
    val two = ResponseBody.of(bytes("PK-archive"), StandardCharsets.UTF_8)

    assert(!one.bytes.eq(two.bytes), "the two arrays must be distinct objects, or the test proves nothing")
    assertEquals(one, two)
    assertEquals(one.hashCode, two.hashCode)

  test("a set of responses keeps one copy of a repeated archive"):
    assertEquals(Set(zip("PK-archive"), zip("PK-archive")).size, 1)

  test("responses whose bytes differ are not equal"):
    assertNotEquals(zip("PK-archive"), zip("PK-archiv3"))

  test("a shorter body is not equal to a longer one that starts the same way"):
    assertNotEquals(zip("PK"), zip("PK-archive"))

  test("the status and the headers still count, so equal bytes alone are not enough"):
    val archive = zip("PK-archive")
    val body    = ResponseBody.of(bytes("PK-archive"), StandardCharsets.UTF_8)

    assertNotEquals(archive, CodebergResponse(404, Headers, body))
    assertNotEquals(archive, CodebergResponse(200, Map.empty[String, List[String]], body))

  test("the charset counts too: the same bytes read as different text are different bodies"):
    // Two bodies that would render differently must not compare equal, or a
    // cache keyed on the body would serve one for the other.
    assertNotEquals(
      ResponseBody.of(bytes("PK-archive"), StandardCharsets.UTF_8),
      ResponseBody.of(bytes("PK-archive"), StandardCharsets.ISO_8859_1),
    )

  test("canEqual agrees with equals: a response is comparable to another and to nothing else"):
    val archive = zip("PK-archive")

    assert(archive.canEqual(CodebergResponse(500, Map.empty, ResponseBody.Empty)))
    assert(!archive.canEqual("PK-archive"))
    assertNotEquals[Any, Any](archive, "PK-archive")

  // --- RequestBody.Binary ---------------------------------------------------

  test("two binary request bodies carrying equal-but-distinct arrays are equal and hash alike"):
    val one = RequestBody.Binary(bytes("avatar"), "image/png")
    val two = RequestBody.Binary(bytes("avatar"), "image/png")

    assert(!one.bytes.eq(two.bytes), "the two arrays must be distinct objects, or the test proves nothing")
    assertEquals(one, two)
    assertEquals(one.hashCode, two.hashCode)
    assertEquals(Set[RequestBody](one, two).size, 1)

  test("binary request bodies differing in their bytes or in their media type are not equal"):
    val body = RequestBody.Binary(bytes("avatar"), "image/png")

    assertNotEquals(body, RequestBody.Binary(bytes("avatar!"), "image/png"))
    assertNotEquals(body, RequestBody.Binary(bytes("avatar"), "image/jpeg"))

  // --- RequestBody.Multipart ------------------------------------------------

  test("two multipart bodies carrying equal-but-distinct arrays are equal and hash alike"):
    val one = RequestBody.Multipart("attachment", "notes.txt", bytes("hi"), "text/plain")
    val two = RequestBody.Multipart("attachment", "notes.txt", bytes("hi"), "text/plain")

    assert(!one.bytes.eq(two.bytes), "the two arrays must be distinct objects, or the test proves nothing")
    assertEquals(one, two)
    assertEquals(one.hashCode, two.hashCode)
    assertEquals(Set[RequestBody](one, two).size, 1)

  test("every field of a multipart body counts, not only the bytes"):
    val part = RequestBody.Multipart("attachment", "notes.txt", bytes("hi"), "text/plain")

    assertNotEquals(part, RequestBody.Multipart("attachment", "notes.txt", bytes("ho"), "text/plain"))
    assertNotEquals(part, RequestBody.Multipart("file", "notes.txt", bytes("hi"), "text/plain"))
    assertNotEquals(part, RequestBody.Multipart("attachment", "other.txt", bytes("hi"), "text/plain"))
    assertNotEquals(part, RequestBody.Multipart("attachment", "notes.txt", bytes("hi"), "text/markdown"))

  test("a binary body is not equal to a multipart body that happens to carry the same bytes"):
    val binary    = RequestBody.Binary(bytes("hi"), "text/plain")
    val multipart = RequestBody.Multipart("attachment", "notes.txt", bytes("hi"), "text/plain")

    assertNotEquals[RequestBody, RequestBody](binary, multipart)
    assertNotEquals[RequestBody, RequestBody](multipart, binary)

  // --- the cases that carry no bytes ---------------------------------------

  test("the byte-free cases still compare structurally, and the empty body is still a value"):
    // RequestBody stopped being an enum so that two of its cases could write
    // their own equals; the other three must be unaffected by that.
    assertEquals(RequestBody.Json("""{"a":1}"""), RequestBody.Json("""{"a":1}"""))
    assertNotEquals(RequestBody.Json("""{"a":1}"""), RequestBody.Json("""{"a":2}"""))
    assertEquals(
      RequestBody.Text("# heading", RequestBody.TextMediaType),
      RequestBody.Text("# heading", "text/plain; charset=utf-8"),
    )
    assertEquals[RequestBody, RequestBody](RequestBody.Empty, RequestBody.Empty)
