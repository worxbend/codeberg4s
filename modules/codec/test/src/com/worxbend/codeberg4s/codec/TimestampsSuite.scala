package com.worxbend.codeberg4s.codec

import munit.FunSuite

import scala.util.Try

import java.time.Instant
import java.time.OffsetDateTime

final class TimestampsSuite extends FunSuite:

  test("an RFC-3339 timestamp with an offset parses to the instant it names"):
    assertEquals(Timestamps.parse("2022-11-26T18:56:24+01:00"), Some(Instant.parse("2022-11-26T17:56:24Z")))

  test("a Z-suffixed timestamp parses"):
    assertEquals(Timestamps.parse("2026-08-01T22:12:04Z"), Some(Instant.parse("2026-08-01T22:12:04Z")))

  test("a negative offset is subtracted, even when that moves the date"):
    assertEquals(Timestamps.parse("2026-08-01T22:12:04-05:00"), Some(Instant.parse("2026-08-02T03:12:04Z")))

  test("fractional seconds survive, and the offset still applies"):
    assertEquals(Timestamps.parse("2022-11-26T18:56:24.123+01:00"), Some(Instant.parse("2022-11-26T17:56:24.123Z")))
    assertEquals(Timestamps.parse("2026-08-01T22:12:04.1Z"), Some(Instant.parse("2026-08-01T22:12:04.100Z")))
    assertEquals(
      Timestamps.parse("2026-08-01T22:12:04.123456789Z"),
      Some(Instant.parse("2026-08-01T22:12:04.123456789Z")),
    )

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

  test("a timestamp with no offset at all is absence"):
    assertEquals(Timestamps.parse("2026-08-01T22:12:04"), None)
    assertEquals(Timestamps.parse("2026-08-01T22:12:04.123"), None)

  test("anything trailing the offset makes the whole value absence"):
    assertEquals(Timestamps.parse("2026-08-01T22:12:04Zx"), None)
    assertEquals(Timestamps.parse("2026-08-01T22:12:04+02:00x"), None)
    assertEquals(Timestamps.parse("2026-08-01T22:12:04Z 2026-08-01T22:12:04Z"), None)

  test("a value that is not a real point on the calendar is absence"):
    assertEquals(Timestamps.parse("2024-02-29T00:00:00Z"), Some(Instant.parse("2024-02-29T00:00:00Z")))
    assertEquals(Timestamps.parse("2023-02-29T00:00:00Z"), None)
    assertEquals(Timestamps.parse("2026-04-31T00:00:00Z"), None)
    assertEquals(Timestamps.parse("2026-13-01T00:00:00Z"), None)
    assertEquals(Timestamps.parse("2026-00-01T00:00:00Z"), None)
    assertEquals(Timestamps.parse("2026-08-00T00:00:00Z"), None)
    assertEquals(Timestamps.parse("2026-08-01T24:00:00Z"), None)
    assertEquals(Timestamps.parse("2026-08-01T22:60:04Z"), None)
    assertEquals(Timestamps.parse("2026-08-01T22:12:60Z"), None)
    assertEquals(Timestamps.parse("2026-08-01T22:12:04+24:00"), None)

  test("single-digit calendar fields are not padded for us"):
    assertEquals(Timestamps.parse("2026-8-01T22:12:04Z"), None)
    assertEquals(Timestamps.parse("2026-08-1T22:12:04Z"), None)

  test("surrounding whitespace is tolerated"):
    assertEquals(Timestamps.parse("  2022-11-26T18:56:24+01:00  "), Some(Instant.parse("2022-11-26T17:56:24Z")))

  /** RFC-3339 spellings Forgejo does not send, but which the library accepts today.
    *
    * They are pinned so that a faster parser tuned to Forgejo's exact layout cannot silently narrow what the library
    * understands: whatever shape a caller was already feeding it has to keep working.
    */
  test("RFC-3339 spellings outside Forgejo's own layout still parse"):
    assertEquals(Timestamps.parse("2026-08-01t22:12:04z"), Some(Instant.parse("2026-08-01T22:12:04Z")))
    assertEquals(Timestamps.parse("2022-11-26T18:56+01:00"), Some(Instant.parse("2022-11-26T17:56:00Z")))
    assertEquals(Timestamps.parse("2026-08-01T22:12:04+01:00:30"), Some(Instant.parse("2026-08-01T21:11:34Z")))
    assertEquals(Timestamps.parse("2026-08-01T22:12:04-00:00"), Some(Instant.parse("2026-08-01T22:12:04Z")))
    assertEquals(
      Timestamps.parse("+12026-08-01T22:12:04Z"),
      Some(OffsetDateTime.parse("+12026-08-01T22:12:04Z").toInstant),
    )

  test("parseOptional threads absence through"):
    assertEquals(Timestamps.parseOptional(None), None)
    assertEquals(Timestamps.parseOptional(Some("0001-01-01T00:00:00Z")), None)
    assertEquals(Timestamps.parseOptional(Some("2026-08-01T22:12:04Z")), Some(Instant.parse("2026-08-01T22:12:04Z")))

  /** What [[Timestamps.parse]] has to mean, spelled out with the JDK doing the work.
    *
    * The sweep below compares every generated value against this, so an implementation that hand-rolls the arithmetic —
    * days since the epoch, leap years, offset subtraction — is checked against `java.time` rather than against a
    * handful of examples someone remembered to write down.
    */
  private def viaJdk(value: String): Option[Instant] =
    Try(OffsetDateTime.parse(value.trim).toInstant).toOption
      .filter(_.isAfter(Instant.EPOCH))

  test("the parser agrees with the JDK across a sweep of dates, times and offsets"):
    val dates =
      for
        year  <- Seq(1969, 1970, 1971, 1999, 2000, 2001, 2023, 2024, 2026, 2100, 2400)
        month <- 1 to 12
        day   <- Seq(1, 15, 28, 29, 30, 31)
      yield f"$year%04d-$month%02d-$day%02d"

    val times   = Seq("00:00:00", "12:34:56", "23:59:59", "22:12:04.123", "06:07:08.000000001")
    val offsets = Seq("Z", "+00:00", "-00:00", "+01:00", "-05:00", "+05:30", "-09:30", "+14:00", "-12:00", "+18:00")

    for
      date   <- dates
      time   <- times
      offset <- offsets
    do
      val value = s"${date}T$time$offset"
      assertEquals(Timestamps.parse(value), viaJdk(value), value)
