package com.worxbend.codeberg4s.codec

import munit.FunSuite

import java.time.Instant

final class TimestampsSuite extends FunSuite:

  test("an RFC-3339 timestamp with an offset parses to the instant it names"):
    assertEquals(Timestamps.parse("2022-11-26T18:56:24+01:00"), Some(Instant.parse("2022-11-26T17:56:24Z")))

  test("a Z-suffixed timestamp parses"):
    assertEquals(Timestamps.parse("2026-08-01T22:12:04Z"), Some(Instant.parse("2026-08-01T22:12:04Z")))

  test("the Go zero-time sentinel is absence, not a year-1 timestamp"):
    assertEquals(Timestamps.parse("0001-01-01T00:00:00Z"), None)

  test("the Unix-epoch sentinel Forgejo sends for archived_at is absence"):
    assertEquals(Timestamps.parse("1970-01-01T01:00:00+01:00"), None)
    assertEquals(Timestamps.parse("1970-01-01T00:00:00Z"), None)

  test("a blank or malformed value is absence, never an exception"):
    assertEquals(Timestamps.parse(""), None)
    assertEquals(Timestamps.parse("   "), None)
    assertEquals(Timestamps.parse("notadate"), None)
    assertEquals(Timestamps.parse("2022-11-26"), None)

  test("surrounding whitespace is tolerated"):
    assertEquals(Timestamps.parse("  2022-11-26T18:56:24+01:00  "), Some(Instant.parse("2022-11-26T17:56:24Z")))

  test("parseOptional threads absence through"):
    assertEquals(Timestamps.parseOptional(None), None)
    assertEquals(Timestamps.parseOptional(Some("0001-01-01T00:00:00Z")), None)
    assertEquals(Timestamps.parseOptional(Some("2026-08-01T22:12:04Z")), Some(Instant.parse("2026-08-01T22:12:04Z")))
