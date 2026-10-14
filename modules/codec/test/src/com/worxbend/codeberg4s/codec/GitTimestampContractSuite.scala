package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.repositories.gitdata.wire.{AnnotatedTagDto, FileCommitDto, FileResponseDto}
import com.worxbend.codeberg4s.repositories.wire.{
  CommitDto,
  CommitMetaDto,
  ContentEntryDto,
  GitIdentityDto,
  PayloadCommitDto,
  RepositoryDto
}
import com.worxbend.codeberg4s.users.wire.UserDto

import munit.FunSuite

import java.time.{Instant, OffsetDateTime}

/** Git dates are authored metadata; the service's historical sentinel policy must remain separately scoped. */
final class GitTimestampContractSuite extends FunSuite:

  private val actualDates: Vector[String] = Vector(
    "1970-01-01T00:00:00Z",
    "1970-01-01T01:00:00+01:00",
    "1969-12-31T23:59:59Z",
    "1969-12-31T23:59:59.123456789Z",
    "1960-02-29T00:00:00-05:00",
    "0001-01-01T00:00:00.000000001Z",
    "1969-12-31t23:59:59z",
  )

  actualDates.foreach: date =>
    val expected = Some(OffsetDateTime.parse(date).toInstant)
    val quoted   = Json.render(JsonValue.Str(date))

    test(s"Git identity preserves authored date $date"):
      assertEquals(Json.decode[GitIdentityDto](s"""{"date":$quoted}""").map(_.toDomain.date), Right(expected))

    test(s"commit created preserves authored date $date"):
      val body = s"""{"sha":"cafebabe","created":$quoted}"""
      assertEquals(Json.decode[CommitDto](body).flatMap(_.toDomain).map(_.created), Right(expected))

    test(s"commit metadata preserves real date $date"):
      val body = s"""{"sha":"cafebabe","created":$quoted}"""
      assertEquals(Json.decode[CommitMetaDto](body).flatMap(_.toDomain).map(_.created), Right(expected))

    test(s"branch payload timestamp preserves real date $date"):
      val body = s"""{"id":"cafebabe","timestamp":$quoted}"""
      assertEquals(Json.decode[PayloadCommitDto](body).flatMap(_.toDomain).map(_.timestamp), Right(expected))

    test(s"file commit created preserves real date $date"):
      val body = s"""{"sha":"cafebabe","created":$quoted}"""
      assertEquals(Json.decode[FileCommitDto](body).flatMap(_.toDomain).map(_.created), Right(expected))

    test(s"contents last commit time preserves real date $date"):
      val body = s"""{"name":"x","path":"x","sha":"cafebabe","type":"file","last_commit_when":$quoted}"""
      assertEquals(Json.decode[ContentEntryDto](body).flatMap(_.toDomain).map(_.meta.lastCommitWhen), Right(expected))

  test("a commit distinguishes Git author epoch from service account last-login epoch"):
    val epoch  = "1970-01-01T01:00:00+01:00"
    val body   =
      s"""{"sha":"cafebabe","commit":{"author":{"date":"$epoch"}},"author":{"id":1,"login":"ada","last_login":"$epoch"}}"""
    val result = Json.decode[CommitDto](body).flatMap(_.toDomain)
    assertEquals(result.map(_.details.flatMap(_.author).flatMap(_.date)), Right(Some(Instant.EPOCH)))
    assertEquals(result.map(_.author.flatMap(_.lastLoginAt)), Right(None))

  test("a file response preserves its embedded author and committer's historical dates"):
    val body   =
      """{"commit":{"sha":"cafebabe","author":{"date":"1970-01-01T00:00:00Z"},"committer":{"date":"1969-12-31T23:59:59Z"}}}"""
    val result = Json.decode[FileResponseDto](body).flatMap(_.toDomain)
    assertEquals(result.map(_.commit.flatMap(_.author).flatMap(_.date)), Right(Some(Instant.EPOCH)))
    assertEquals(
      result.map(_.commit.flatMap(_.committer).flatMap(_.date)),
      Right(Some(Instant.parse("1969-12-31T23:59:59Z")))
    )

  test("an annotated tag preserves a historical tagger date"):
    val body   = """{"tag":"v1","sha":"cafebabe","tagger":{"date":"1969-12-31T23:59:59Z"}}"""
    val result = Json.decode[AnnotatedTagDto](body).flatMap(_.toDomain)
    assertEquals(result.map(_.tagger.flatMap(_.date)), Right(Some(Instant.parse("1969-12-31T23:59:59Z"))))

  Vector("0001-01-01T00:00:00Z", "0001-01-01T01:00:00+01:00").foreach: zero =>
    test(s"Git metadata still folds precisely the Go-zero instant $zero into absence"):
      val quoted = Json.render(JsonValue.Str(zero))
      val body   = s"""{"sha":"cafebabe","created":$quoted}"""
      assertEquals(Json.decode[CommitMetaDto](body).flatMap(_.toDomain).map(_.created), Right(None))
      assertEquals(Json.decode[GitIdentityDto](s"""{"date":$quoted}""").map(_.toDomain.date), Right(None))

  test("an embedded tree retains its captured Go-zero sentinel"):
    val body   = """{"sha":"cafebabe","commit":{"tree":{"sha":"abcdef12","created":"0001-01-01T00:00:00Z"}}}"""
    val result = Json.decode[CommitDto](body).flatMap(_.toDomain)
    assertEquals(result.map(_.details.flatMap(_.tree).flatMap(_.created)), Right(None))

  Vector("1970-01-01T00:00:00Z", "1970-01-01T01:00:00+01:00", "1969-12-31T23:59:59Z", "0001-01-01T00:00:00Z").foreach:
    date =>
      test(s"legacy service timestamp parsing keeps its sentinel policy for $date"):
        assertEquals(Timestamps.parse(date), None)
        assertEquals(Timestamps.parseOptional(Some(date)), None)

      test(s"repository archived-at keeps its service sentinel policy for $date"):
        val body = s"""{"id":1,"name":"repo","owner":{"id":1,"login":"ada"},"archived_at":"$date"}"""
        assertEquals(Json.decode[RepositoryDto](body).flatMap(_.toDomain).map(_.archivedAt), Right(None))

      test(s"user last-login keeps its service sentinel policy for $date"):
        val body = s"""{"id":1,"login":"ada","last_login":"$date"}"""
        assertEquals(Json.decode[UserDto](body).flatMap(_.toDomain).map(_.lastLoginAt), Right(None))

  Vector("", " ", "notadate", "1969-12-31", "1969-02-29T00:00:00Z", "1969-12-31T23:59:59").foreach: date =>
    test(s"malformed Git date ${Json.render(JsonValue.Str(date))} remains optional absence"):
      val body = s"""{"date":${Json.render(JsonValue.Str(date))}}"""
      assertEquals(Json.decode[GitIdentityDto](body).map(_.toDomain.date), Right(None))
