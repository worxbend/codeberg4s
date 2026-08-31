package com.worxbend.codeberg4s.codec

import scala.annotation.tailrec
import scala.util.Try

import java.time.Instant
import java.time.OffsetDateTime
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Turns Forgejo's timestamp strings into instants, sentinels included.
  *
  * Forgejo renders times as RFC-3339 with an explicit offset — `"2022-11-26T18:56:24+01:00"` on
  * `golden/user/user-single.json`, `"2026-08-01T22:12:04+02:00"` on `golden/repository/repo-single.json`.
  *
  * It also has a second spelling for "never", which the golden-fixture manifest counts: `User.last_login` (104
  * occurrences), `Repository.mirror_updated` (41) and `CommitMeta.created` (4) come back as the Go zero time
  * `"0001-01-01T00:00:00Z"` rather than `null`, and `Repository.archived_at` comes back as the Unix epoch
  * (`"1970-01-01T01:00:00+01:00"`) on repositories that were never archived. Both are absence wearing a costume, and
  * both are folded into `None` here so no caller has to know the trick.
  *
  * The reverse direction lives here too: [[render]] writes the one spelling Forgejo's parser accepts.
  */
object Timestamps:

  /** Parses a Forgejo timestamp.
    *
    * '''Never throws.''' Answers `None` for a blank string, for anything that is not RFC-3339 with an offset, and for
    * any instant at or before the Unix epoch — see the sentinels above. The epoch boundary means this cannot represent
    * a genuine pre-1970 timestamp; Forgejo has no field that could carry one, since every timestamp it emits describes
    * an event on a Git forge.
    *
    * Two parsers sit behind this. [[fixedLayout]] reads the one shape Forgejo actually sends, character by character,
    * with no formatter and no intermediate date objects. Anything it does not recognise — a lowercase `t`, an offset
    * carrying seconds, a year outside four digits, or a value that is simply not a timestamp — falls through to
    * `java.time.OffsetDateTime.parse`, so the set of strings this accepts is exactly what it has always been, and only
    * the speed of the common case changes.
    */
  def parse(value: String): Option[Instant] =
    val trimmed = value.trim
    fixedLayout(trimmed)
      .orElse(Try(OffsetDateTime.parse(trimmed).toInstant).toOption)
      .filter(_.isAfter(Instant.EPOCH))

  /** [[parse]] lifted over an optional wire value, for the common `dto.createdAt.flatMap(...)` shape. */
  def parseOptional(value: Option[String]): Option[Instant] =
    value.flatMap(parse)

  /** `value` as RFC-3339 with a `Z` offset and second precision, for example `"2026-08-01T18:14:16Z"`.
    *
    * The counterpart of [[parse]]. Reading is lenient and writing cannot be: Forgejo parses timestamps with Go's
    * `time.RFC3339` layout, `2006-01-02T15:04:05Z07:00`, and a value it cannot parse comes back as a `422` whose
    * message is the raw Go parse error — `docs/HAZARDS.md` §4 captures exactly that response for `?since=notadate`.
    *
    * Seconds are the finest unit emitted. `Instant.toString` would append fractional seconds when it has them, which Go
    * does accept, but truncating keeps the rendering stable regardless of where the caller's instant came from, and
    * keeps a `since` cursor byte-identical between runs.
    */
  def render(value: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS))

  /** The shortest string the fixed layout can be: `yyyy-MM-ddTHH:mm:ssZ`. */
  private val MinimumLength: Int = 20

  private val SecondsPerDay: Long = 86400L

  /** `java.time.ZoneOffset` refuses anything beyond ±18:00, so the fixed path refuses it too rather than inventing an
    * instant the JDK would have rejected.
    */
  private val MaxOffsetSeconds: Int = 18 * 3600

  /** Not a possible offset, so it can stand for "these characters are not an offset" without an `Option` wrapper on a
    * path that runs once per timestamp field of every decoded object.
    */
  private val OffsetMismatch: Int = Int.MinValue

  /** Reads `yyyy-MM-ddTHH:mm:ss`, optional `.fraction`, then `Z` or `±HH:mm`, and answers the instant it names.
    *
    * Answers `None` whenever the string departs from that layout in any way, including when it departs by being an
    * impossible date such as `2023-02-29`. `None` here means "not handled", not "invalid": the caller retries with the
    * JDK parser, which is the authority on what is valid. That keeps this function free to bail out early on anything
    * awkward instead of having to reproduce every corner of RFC-3339.
    *
    * The whole parse is integer arithmetic over `charAt`. It allocates the resulting `Instant` and nothing else — no
    * formatter lookup, no `LocalDate`, no `OffsetDateTime`, no exception for the failure case.
    */
  private def fixedLayout(value: String): Option[Instant] =
    if value.length < MinimumLength || !hasFixedSeparators(value) then None
    else
      val year   = fourDigits(value, 0)
      val month  = twoDigits(value, 5)
      val day    = twoDigits(value, 8)
      val hour   = twoDigits(value, 11)
      val minute = twoDigits(value, 14)
      val second = twoDigits(value, 17)
      if year < 0 || !isRealDate(year, month, day) || !isRealTime(hour, minute, second) then None
      else
        val fractionStart  = MinimumLength
        val hasFraction    = value.startsWith(".", fractionStart - 1)
        val fractionEnd    = if hasFraction then digitsEnd(value, fractionStart) else fractionStart - 1
        val fractionDigits = fractionEnd - fractionStart
        // A dot with no digits after it, or more than nanosecond precision: rare enough to hand to the JDK.
        if hasFraction && (fractionDigits < 1 || fractionDigits > 9) then None
        else
          offsetSeconds(value, fractionEnd) match
            case OffsetMismatch => None
            case offset         =>
              val nanos       =
                if hasFraction then scaleToNanos(digitsValue(value, fractionStart, fractionEnd, 0), fractionDigits)
                else 0
              val secondOfDay = hour * 3600 + minute * 60 + second
              val epochSecond = epochDay(year, month, day) * SecondsPerDay + secondOfDay - offset
              Some(Instant.ofEpochSecond(epochSecond, nanos.toLong))

  /** `String.startsWith(prefix, offset)` rather than a comparison against a `Char`, because `.scalafix.conf` bans
    * universal equality — the same reason `LinkHeader` reaches for `equalsIgnoreCase`.
    */
  private def hasFixedSeparators(value: String): Boolean =
    value.startsWith("-", 4) && value.startsWith("-", 7) && value.startsWith("T", 10) &&
    value.startsWith(":", 13) && value.startsWith(":", 16)

  /** Reads `Z` or `±HH:mm` at `index`, and only if it runs to the end of the string — which is what rejects trailing
    * garbage. Answers seconds east of UTC, or [[OffsetMismatch]].
    */
  private def offsetSeconds(value: String, index: Int): Int = (value.length - index) match
    case 1 if value.startsWith("Z", index)     => 0
    case 6 if value.startsWith(":", index + 3) => signedOffsetSeconds(value, index)
    case _                                     => OffsetMismatch

  /** The `±HH:mm` at `index`, which the caller has already checked is six characters long with a colon in the middle. */
  private def signedOffsetSeconds(value: String, index: Int): Int =
    val hours   = twoDigits(value, index + 1)
    val minutes = twoDigits(value, index + 4)
    val total   = hours * 3600 + minutes * 60
    if hours < 0 || minutes < 0 || minutes > 59 || total > MaxOffsetSeconds then OffsetMismatch
    else
      value.charAt(index) match
        case '+' => total
        case '-' => -total
        case _   => OffsetMismatch

  private def isRealDate(year: Int, month: Int, day: Int): Boolean =
    month >= 1 && month <= 12 && day >= 1 && day <= lengthOfMonth(year, month)

  private def isRealTime(hour: Int, minute: Int, second: Int): Boolean =
    hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && second >= 0 && second <= 59

  /** `java.time.Year.isLeap` is a static arithmetic method — no object is created — so the leap-year rule stays the
    * JDK's rather than a second copy of it here.
    */
  private def lengthOfMonth(year: Int, month: Int): Int =
    month match
      case 2 => if Year.isLeap(year.toLong) then 29 else 28
      case 4 | 6 | 9 | 11 => 30
      case _ => 31

  /** Days from 1970-01-01 to the given proleptic-Gregorian date, by Howard Hinnant's `days_from_civil`.
    *
    * The trick is to start the year in March, which pushes the leap day to the end of the year and makes the
    * day-of-year a closed-form expression, then to count whole 400-year eras, each of which has exactly 146,097 days.
    * The date must already be known to be real; nothing here range-checks it.
    */
  private def epochDay(year: Int, month: Int, day: Int): Long =
    val shiftedYear  = if month <= 2 then year - 1 else year
    val era          = (if shiftedYear >= 0 then shiftedYear else shiftedYear - 399) / 400
    val yearOfEra    = shiftedYear - era * 400 // 0 to 399
    val marchMonth   = (month + 9) % 12 // March is 0, February is 11
    val dayOfYear    = (153 * marchMonth + 2) / 5 + day - 1 // 0 to 365
    val dayOfEra     = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val daysToMarch1 = 719468 // 1970-01-01 measured from 0000-03-01
    era * 146097L + dayOfEra - daysToMarch1

  /** Left-aligns a fraction of `digits` places into nanoseconds: `.123` is 123,000,000ns, `.1` is 100,000,000ns. */
  @tailrec
  private def scaleToNanos(fraction: Int, digits: Int): Int =
    if digits >= 9 then fraction else scaleToNanos(fraction * 10, digits + 1)

  @tailrec
  private def digitsEnd(value: String, index: Int): Int =
    if index < value.length && isDigit(value.charAt(index)) then digitsEnd(value, index + 1) else index

  @tailrec
  private def digitsValue(value: String, index: Int, end: Int, accumulated: Int): Int =
    if index >= end then accumulated
    else digitsValue(value, index + 1, end, accumulated * 10 + (value.charAt(index) - '0'))

  private def isDigit(character: Char): Boolean =
    character >= '0' && character <= '9'

  /** The digit at `index`, or `-1` if that character is not a digit. */
  private def digit(value: String, index: Int): Int =
    val character = value.charAt(index)
    if isDigit(character) then character - '0' else -1

  /** The two-digit number at `index`, or a negative value if either character is not a digit. */
  private def twoDigits(value: String, index: Int): Int =
    val tens  = digit(value, index)
    val units = digit(value, index + 1)
    if tens < 0 || units < 0 then -1 else tens * 10 + units

  /** The four-digit number at `index`, or a negative value if any character is not a digit. */
  private def fourDigits(value: String, index: Int): Int =
    val hundreds = twoDigits(value, index)
    val units    = twoDigits(value, index + 2)
    if hundreds < 0 || units < 0 then -1 else hundreds * 100 + units
