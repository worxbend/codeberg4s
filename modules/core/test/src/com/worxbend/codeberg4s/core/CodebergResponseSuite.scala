package com.worxbend.codeberg4s.core

import munit.FunSuite

import scala.concurrent.duration.DurationInt

final class CodebergResponseSuite extends FunSuite:

  test("a header is found case-insensitively, since the constructor lowercases the keys"):
    assertEquals(headers("x-request-id" -> "abc123").header("X-Request-Id"), Some("abc123"))

  test("a header stored under a mixed-case key is found whatever case the lookup uses"):
    // An HttpPort that hands names over as they arrived on HTTP/1.1 must get
    // the same answers as one that lowercased them; the constructor, not the
    // adapter, owns that.
    val response = headers("X-Request-Id" -> "abc123")

    assertEquals(response.header("x-request-id"), Some("abc123"))
    assertEquals(response.header("X-REQUEST-ID"), Some("abc123"))
    assertEquals(response.requestId, Some("abc123"))

  test("the stored keys themselves are lowercased, so a direct map lookup agrees with header"):
    assertEquals(headers("X-Total-Count" -> "42").headers, Map("x-total-count" -> List("42")))

  test("x-total-count and the Link header are read through mixed-case keys too"):
    val response = CodebergResponse(
      200,
      Map("X-Total-Count" -> List("1590"), "Link" -> List("<a?page=2>; rel=\"next\"")),
      ResponseBody.Empty,
    )

    assertEquals(response.totalCount, Some(1590))
    assertEquals(response.nextPage.map(_.value), Some(2))

  test("two keys differing only in case are one header, with every value kept"):
    val response = CodebergResponse(200, Map("Link" -> List("<a>"), "link" -> List("<b>")), ResponseBody.Empty)

    assertEquals(response.headers.get("link").map(_.sorted), Some(List("<a>", "<b>")))

  test("a header the instance did not send is absent"):
    assertEquals(headers().header("x-request-id"), None)

  test("a blank header value counts as absent"):
    assertEquals(headers("x-request-id" -> "   ").requestId, None)

  test("a header value is trimmed"):
    assertEquals(headers("x-request-id" -> " abc123 ").requestId, Some("abc123"))

  test("request-id access normalizes OWS while stored headers retain raw credential-edge input"):
    val raw      = " \t diagnostic-only-header-password \t "
    val response = headers("X-Request-Id" -> raw)
    assertEquals(response.headers.get("x-request-id"), Some(List(raw)))
    assertEquals(response.rawRequestId, Some(raw))
    assertEquals(response.requestId, Some("diagnostic-only-header-password"))
    assertEquals(response.header("X-Request-Id"), response.requestId)

  test("request-id access accepts values whose native adapter already removed OWS"):
    val response = headers("X-Request-Id" -> "diagnostic-only-header-password")
    assertEquals(response.requestId, Some("diagnostic-only-header-password"))

  test("only the first value of a repeated header is used"):
    val response = CodebergResponse(200, Map("x-total-count" -> List("42", "7")), ResponseBody.Empty)

    assertEquals(response.totalCount, Some(42))

  test("diagnostic request-id access retains the first raw value even when normalization would discard it"):
    val response = CodebergResponse(200, Map("X-Request-Id" -> List(" \t ", "second-id")), ResponseBody.Empty)
    assertEquals(response.rawRequestId, Some(" \t "))
    assertEquals(response.requestId, None)
    assertEquals(headers().rawRequestId, None)

  test("x-total-count is parsed when the instance sends it"):
    assertEquals(headers("x-total-count" -> "42").totalCount, Some(42))

  test("a missing x-total-count is unknown, not zero and not an error"):
    assertEquals(headers().totalCount, None)

  test("a malformed x-total-count is absent, not an error"):
    assertEquals(headers("x-total-count" -> "many").totalCount, None)
    assertEquals(headers("x-total-count" -> "4 2").totalCount, None)
    assertEquals(headers("x-total-count" -> "9999999999999").totalCount, None)

  test("a negative x-total-count is rejected"):
    assertEquals(headers("x-total-count" -> "-1").totalCount, None)

  test("Retry-After is read as delta-seconds"):
    assertEquals(headers("retry-after" -> "5").retryAfter, Some(5.seconds))

  test("a Retry-After of zero is honoured as no wait at all"):
    assertEquals(headers("retry-after" -> "0").retryAfter, Some(0.seconds))

  test("the HTTP-date form of Retry-After is treated as absent"):
    assertEquals(headers("retry-after" -> "Wed, 21 Oct 2015 07:28:00 GMT").retryAfter, None)

  test("a missing Retry-After is absent"):
    assertEquals(headers().retryAfter, None)

  test("x-request-id is exposed as the correlation id"):
    assertEquals(headers("x-request-id" -> "abc123").requestId, Some("abc123"))

  private def headers(entries: (String, String)*): CodebergResponse =
    CodebergResponse(200, entries.map((name, value) => (name, List(value))).toMap, ResponseBody.Empty)
