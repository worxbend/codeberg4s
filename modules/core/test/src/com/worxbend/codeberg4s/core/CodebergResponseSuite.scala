package com.worxbend.codeberg4s.core

import scala.concurrent.duration.DurationInt

import munit.FunSuite

final class CodebergResponseSuite extends FunSuite:

  test("a header is found case-insensitively, since the transport lowercases the keys"):
    assertEquals(headers("x-request-id" -> "abc123").header("X-Request-Id"), Some("abc123"))

  test("a header the instance did not send is absent"):
    assertEquals(headers().header("x-request-id"), None)

  test("a blank header value counts as absent"):
    assertEquals(headers("x-request-id" -> "   ").requestId, None)

  test("a header value is trimmed"):
    assertEquals(headers("x-request-id" -> " abc123 ").requestId, Some("abc123"))

  test("only the first value of a repeated header is used"):
    val response = CodebergResponse(200, Map("x-total-count" -> List("42", "7")), "")

    assertEquals(response.totalCount, Some(42))

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
    CodebergResponse(200, entries.map((name, value) => (name, List(value))).toMap, "")
