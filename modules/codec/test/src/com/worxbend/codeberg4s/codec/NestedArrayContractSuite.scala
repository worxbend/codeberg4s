package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.wire.{IssueDto, TimelineCommentDto, TrackedTimeDto}
import com.worxbend.codeberg4s.pulls.wire.PullRequestDto
import com.worxbend.codeberg4s.repositories.admin.wire.{FilesResponseDto, TopicSearchEnvelopeDto}
import com.worxbend.codeberg4s.repositories.gitdata.wire.{CompareDto, FileCommitDto, FileResponseDto, GitTreeDto}
import com.worxbend.codeberg4s.repositories.wire.{CommitDto, ReleaseDto, TopicNamesDto}

import munit.FunSuite

/** Synthetic corruption cases exercise actual DTO decoders, not a proposed strict helper API. */
final class NestedArrayContractSuite extends FunSuite:

  private val issueFields: String = """"id":1,"number":2,"title":"t","state":"open""""
  private val user: String        = """{"id":1,"login":"ada"}"""
  private val label: String       = """{"id":1,"name":"bug","color":"abcdef"}"""
  private val entry: String       = """{"name":"x","path":"x","sha":"cafebabe","type":"file"}"""
  private val commit: String      = """{"sha":"cafebabe"}"""
  private val file: String        = """{"filename":"x"}"""

  objectMembers(
    "issue labels",
    label,
    "id",
    "$.labels",
    values => issueBody("labels", values),
    body   => Json.decode[IssueDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "issue assignees",
    user,
    "id",
    "$.assignees",
    values => issueBody("assignees", values),
    body   => Json.decode[IssueDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "pull labels",
    label,
    "id",
    "$.labels",
    values => issueBody("labels", values),
    body   => Json.decode[PullRequestDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "pull assignees",
    user,
    "id",
    "$.assignees",
    values => issueBody("assignees", values),
    body   => Json.decode[PullRequestDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "pull requested reviewers",
    user,
    "id",
    "$.requested_reviewers",
    values => issueBody("requested_reviewers", values),
    body   => Json.decode[PullRequestDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "primary tree entries",
    entry,
    "path",
    "$.tree",
    values => envelope("tree", values),
    body   => Json.decode[GitTreeDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "primary topic search",
    """{"id":1,"topic_name":"git"}""",
    "id",
    "$.topics",
    values => envelope("topics", values),
    body   =>
      Json.decode[TopicSearchEnvelopeDto](body).flatMap(dto =>
        WireModel.all(JsonPath.Root.field(TopicSearchEnvelopeDto.EntriesKey), dto.entries)
      )
  )
  objectMembers(
    "primary batch files",
    entry,
    "name",
    "$.files",
    values => envelope("files", values),
    body   => Json.decode[FilesResponseDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "commit parents",
    commit,
    "sha",
    "$.parents",
    values => s"""{"sha":"cafebabe","parents":$values}""",
    body   => Json.decode[CommitDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "commit files",
    file,
    "filename",
    "$.files",
    values => s"""{"sha":"cafebabe","files":$values}""",
    body   => Json.decode[CommitDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "file commit parents",
    commit,
    "sha",
    "$.parents",
    values => s"""{"sha":"cafebabe","parents":$values}""",
    body   => Json.decode[FileCommitDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "compare commits",
    commit,
    "sha",
    "$.commits",
    values => envelope("commits", values),
    body   => Json.decode[CompareDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "compare files",
    file,
    "filename",
    "$.files",
    values => envelope("files", values),
    body   => Json.decode[CompareDto](body).flatMap(_.toDomain)
  )
  objectMembers(
    "release assets",
    """{"id":1,"name":"asset"}""",
    "id",
    "$.assets",
    values => s"""{"id":1,"tag_name":"v1","assets":$values}""",
    body   => Json.decode[ReleaseDto](body).flatMap(_.toDomain)
  )

  Vector("null", "7", "[]").foreach: bad =>
    (0 until 3).foreach: index =>
      test(s"primary topic names reject $bad at original index $index"):
        val values = Vector.fill(3)("\"git\"").updated(index, bad).mkString("[", ",", "]")
        assertPath(Json.decode[TopicNamesDto](envelope("topics", values)).flatMap(_.toDomain), s"$$.topics[$index]")

  Vector("[]", "null", "\"wrong-kind\"", "{}", "7").foreach: values =>
    test(s"primary topic names keep whole-container leniency for $values"):
      assertEquals(Json.decode[TopicNamesDto](envelope("topics", values)).flatMap(_.toDomain), Right(Vector.empty))

  test("primary topic names keep absent containers lenient"):
    assertEquals(Json.decode[TopicNamesDto]("{}").flatMap(_.toDomain), Right(Vector.empty))

  test("topic validation reports the first malformed member rather than a later invalid name"):
    assertPath(Json.decode[TopicNamesDto]("""{"topics":["git",null,"../escape"]}""").flatMap(_.toDomain), "$.topics[1]")

  test("a tracked-time DTO cannot bypass its embedded issue's member validation"):
    val issue = issueBody("labels", s"[$label,null]")
    val body  = s"""{"id":1,"time":3,"issue":$issue}"""
    assertPath(Json.decode[TrackedTimeDto](body).flatMap(_.toDomain), "$.issue.labels[1]")

  test("timeline nested-under-nested issue validation retains every path component"):
    val issue = issueBody("assignees", s"[$user,null]")
    val body  = s"""{"id":1,"tracked_time":{"id":1,"time":3,"issue":$issue}}"""
    assertPath(Json.decode[TimelineCommentDto](body).flatMap(_.toDomain), "$.tracked_time.issue.assignees[1]")

  test("a file response cannot bypass its embedded commit's member validation"):
    val body = s"""{"commit":{"sha":"cafebabe","parents":[$commit,null]}}"""
    assertPath(Json.decode[FileResponseDto](body).flatMap(_.toDomain), "$.commit.parents[1]")

  test("a batch response cannot bypass its embedded commit's member validation"):
    val body = s"""{"commit":{"sha":"cafebabe","parents":[$commit,null]}}"""
    assertPath(Json.decode[FilesResponseDto](body).flatMap(_.toDomain), "$.commit.parents[1]")

  test("a compare envelope retains the path into a nested commit's files"):
    val body = s"""{"commits":[{"sha":"cafebabe"},{"sha":"cafebabe","files":[$file,null]}]}"""
    assertPath(Json.decode[CompareDto](body).flatMap(_.toDomain), "$.commits[1].files[1]")

  test("a top-level issue listing prefixes a nested structural failure with the original outer index"):
    val good   = s"{$issueFields}"
    val bad    = issueBody("labels", s"[$label,null]")
    val result = Json.decode[Vector[IssueDto]](s"[$good,$bad]").flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
    assertPath(result, "$[1].labels[1]")

  test("a top-level pull listing prefixes a nested structural failure with the original outer index"):
    val good = s"{$issueFields}"
    val bad  = issueBody("requested_reviewers", s"[$user,null]")
    val result = Json.decode[Vector[PullRequestDto]](s"[$good,$bad]").flatMap(dtos => WireModel.all(JsonPath.Root, dtos))
    assertPath(result, "$[1].requested_reviewers[1]")

  test("public fromFields remains an infallible lenient projection for compatibility"):
    val fields              = fieldsOf(issueBody("labels", s"[$label,null]"))
    val projected: IssueDto = IssueDto.fromFields(fields)
    assertEquals(projected.labels.size, 1)
    assertEquals(fields.nestedAll("labels").size, 1)

  test("optional whole wrong-kind issue containers and scalars remain lenient"):
    val result = Json.decode[IssueDto](s"""{$issueFields,"labels":"wrong","assignees":{},"user":[],"body":7}""")
      .flatMap(_.toDomain)
    assertEquals(
      result.map(issue => (issue.labels.size, issue.assignees.size, issue.author, issue.body)),
      Right((0, 0, None, None))
    )

  test("optional whole wrong-kind pull containers and scalars remain lenient"):
    val body   = s"""{$issueFields,"labels":"wrong","assignees":{},"requested_reviewers":7,"head":[],"body":7}"""
    val result = Json.decode[PullRequestDto](body).flatMap(_.toDomain)
    assertEquals(
      result.map(pull => (pull.labels.size, pull.assignees.size, pull.requestedReviewers.size, pull.head)),
      Right((0, 0, 0, None))
    )

  test("fractional identifiers retain the existing truncation contract"):
    val result = Json.decode[IssueDto]("""{"id":1.9,"number":2.9,"title":"t","state":"open"}""").flatMap(_.toDomain)
    assertEquals(result.map(issue => (issue.id, issue.number.value)), Right((1L, 2L)))

  private def objectMembers[A](
      name: String,
      valid: String,
      missingField: String,
      path: String,
      bodyOf: String => String,
      convert: String => Either[DecodeFailure, A],
  ): Unit =
    Vector("null", "7", "[]").foreach: bad =>
      (0 until 3).foreach: index =>
        test(s"$name reject $bad at original index $index"):
          val values = Vector.fill(3)(valid).updated(index, bad).mkString("[", ",", "]")
          assertPath(convert(bodyOf(values)), s"$path[$index]")

    test(s"$name report the first malformed member before a later incomplete object"):
      assertPath(convert(bodyOf(s"[$valid,null,{}]")), s"$path[1]")

    test(s"$name retain an incomplete object's original index after valid objects"):
      assertPath(convert(bodyOf(s"[$valid,{},$valid]")), s"$path[1].$missingField")

    Vector("[]", "null", "\"wrong-kind\"", "{}", "7").foreach: values =>
      test(s"$name keep the whole-container leniency for $values"):
        assert(convert(bodyOf(values)).isRight)

    test(s"$name keep absent containers lenient"):
      // bodyOf supplies all required fields; remove only this array's property through the parsed representation.
      val value  = Json.parse(bodyOf("[]")).fold(problem => fail(problem.message), identity)
      val key    = path.stripPrefix("$.")
      val fields = value.objOpt.getOrElse(fail("expected an object"))
      assert(convert(Json.render(JsonValue.Obj(fields.filterNot((name, _) => name.equals(key))))).isRight)

  private def assertPath[A](result: Either[DecodeFailure, A], expected: String): Unit =
    assertEquals(result.swap.toOption.map(_.path.render), Some(expected))

  private def envelope(key: String, values: String): String =
    s"""{"$key":$values}"""

  private def issueBody(key: String, values: String): String =
    s"""{$issueFields,"$key":$values}"""

  private def fieldsOf(body: String): JsonFields =
    Json.parse(body) match
      case Right(JsonValue.Obj(fields)) => JsonFields(fields)
      case other                        => fail(s"expected an object, got $other")
