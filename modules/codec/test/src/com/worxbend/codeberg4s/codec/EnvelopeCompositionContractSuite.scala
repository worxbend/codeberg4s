package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.wire.IssueDto
import com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto
import com.worxbend.codeberg4s.repositories.wire.RepositoryContentDto
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

import munit.FunSuite

/** Synthetic structural failures through public generic readers, not live response captures. */
final class EnvelopeCompositionContractSuite extends FunSuite:

  private val entry: String = """{"name":"a","path":"a","sha":"cafebabe","type":"file"}"""

  Vector("null", "7", "[]").foreach: bad =>
    (0 until 3).foreach: index =>
      val labels   = Vector.fill(3)("{}").updated(index, bad).mkString("[", ",", "]")
      val issue    = s"""{"labels":$labels}"""
      val search   = s"""{"ok":true,"data":[{}, {},$issue]}"""
      val workflow = s"""{"total_count":3,"workflow_runs":[{}, {},$issue]}"""

      test(s"vector of search envelopes retains every child component for $bad at $index"):
        assertPath[Vector[SearchEnvelopeDto[IssueDto]]](
          s"""[{"data":[]},$search]""",
          s"$$[1].data[2].labels[$index]",
        )

      test(s"vector of workflow envelopes retains every child component for $bad at $index"):
        assertPath[Vector[WorkflowRunsEnvelopeDto[IssueDto]]](
          s"""[{"workflow_runs":[]},$workflow]""",
          s"$$[1].workflow_runs[2].labels[$index]",
        )

      test(s"search of search envelopes retains every child component for $bad at $index"):
        assertPath[SearchEnvelopeDto[SearchEnvelopeDto[IssueDto]]](
          s"""{"data":[{},$search]}""",
          s"$$.data[1].data[2].labels[$index]",
        )

      test(s"search of workflow envelopes retains every child component for $bad at $index"):
        assertPath[SearchEnvelopeDto[WorkflowRunsEnvelopeDto[IssueDto]]](
          s"""{"data":[{},$workflow]}""",
          s"$$.data[1].workflow_runs[2].labels[$index]",
        )

      test(s"workflow of search envelopes retains every child component for $bad at $index"):
        assertPath[WorkflowRunsEnvelopeDto[SearchEnvelopeDto[IssueDto]]](
          s"""{"workflow_runs":[{},$search]}""",
          s"$$.workflow_runs[1].data[2].labels[$index]",
        )

      test(s"workflow of workflow envelopes retains every child component for $bad at $index"):
        assertPath[WorkflowRunsEnvelopeDto[WorkflowRunsEnvelopeDto[IssueDto]]](
          s"""{"workflow_runs":[{},$workflow]}""",
          s"$$.workflow_runs[1].workflow_runs[2].labels[$index]",
        )

      val contents = Vector.fill(3)(entry).updated(index, bad).mkString("[", ",", "]")

      test(s"primary contents reader rejects malformed member $bad at original index $index"):
        assertPath[RepositoryContentDto](contents, s"$$[$index]")

      test(s"vector of contents unions retains original child index for $bad at $index"):
        assertPath[Vector[RepositoryContentDto]](s"[[$entry],$contents]", s"$$[1][$index]")

      test(s"search envelope of contents unions retains original child index for $bad at $index"):
        assertPath[SearchEnvelopeDto[RepositoryContentDto]](
          s"""{"data":[[$entry],$contents]}""",
          s"$$.data[1][$index]",
        )

      test(s"workflow envelope of contents unions retains original child index for $bad at $index"):
        assertPath[WorkflowRunsEnvelopeDto[RepositoryContentDto]](
          s"""{"workflow_runs":[[$entry],$contents]}""",
          s"$$.workflow_runs[1][$index]",
        )

  test("mapped search envelopes keep their child paths when array decoded"):
    val reader = JsonDecoder.arrayOf(JsonDecoder[SearchEnvelopeDto[IssueDto]].map(_.data.size))
    val value  = parsed("""[{"data":[]},{"data":[{}, {"labels":[{},null]}]}]""")
    assertEquals(reader.decode(value).swap.toOption.map(_.path.render), Some("$[1].data[1].labels[1]"))

  test("mapped workflow envelopes keep their child paths when array decoded"):
    val reader = JsonDecoder.arrayOf(JsonDecoder[WorkflowRunsEnvelopeDto[IssueDto]].map(_.entries.size))
    val value  = parsed("""[{"workflow_runs":[]},{"workflow_runs":[{}, {"labels":[{},null]}]}]""")
    assertEquals(reader.decode(value).swap.toOption.map(_.path.render), Some("$[1].workflow_runs[1].labels[1]"))

  test("primary contents reader names the first malformed member even with later malformed members"):
    assertPath[RepositoryContentDto](s"[$entry,null,7,[]]", "$[1]")

  test("nested vectors retain array and envelope child paths"):
    assertPath[Vector[Vector[SearchEnvelopeDto[IssueDto]]]](
      """[[],[{"data":[]},{"data":[{}, {"labels":[{},null]}]}]]""",
      "$[1][1].data[1].labels[1]",
    )

  test("search of vectors of workflow envelopes retains every array component"):
    assertPath[SearchEnvelopeDto[Vector[WorkflowRunsEnvelopeDto[IssueDto]]]](
      """{"data":[[],[{"workflow_runs":[]},{"workflow_runs":[{}, {"labels":[{},null]}]}]]}""",
      "$.data[1][1].workflow_runs[1].labels[1]",
    )

  test("workflow of vectors of search envelopes retains every array component"):
    assertPath[WorkflowRunsEnvelopeDto[Vector[SearchEnvelopeDto[IssueDto]]]](
      """{"workflow_runs":[[],[{"data":[]},{"data":[{}, {"labels":[{},null]}]}]]}""",
      "$.workflow_runs[1][1].data[1].labels[1]",
    )

  test("mapped nested array readers retain child paths"):
    val reader = JsonDecoder.arrayOf(JsonDecoder[Vector[SearchEnvelopeDto[IssueDto]]].map(_.size))
    val value  = parsed("""[[],[{"data":[]},{"data":[{}, {"labels":[{},null]}]}]]""")
    assertEquals(reader.decode(value).swap.toOption.map(_.path.render), Some("$[1][1].data[1].labels[1]"))

  test("mapped primary contents readers retain their malformed member index"):
    val reader = JsonDecoder.arrayOf(JsonDecoder[RepositoryContentDto].map(_.toDomain.isRight))
    val value  = parsed(s"[[$entry],[$entry,null]]")
    assertEquals(reader.decode(value).swap.toOption.map(_.path.render), Some("$[1][1]"))

  test("nested vectors of contents unions preserve malformed member indexes"):
    assertPath[Vector[Vector[RepositoryContentDto]]](s"[[],[[$entry],[$entry,null]]]", "$[1][1][1]")

  Vector("", "null", "7", "{}", "\"wrong-kind\"", "[]").foreach: values =>
    val searchField   = optionalField("data", values)
    val workflowField = optionalField("workflow_runs", values)

    test(s"composed search keeps whole optional-container leniency for $values"):
      val body   = s"""[{"ok":true$searchField}]"""
      val result = Json.decode[Vector[SearchEnvelopeDto[IssueDto]]](body)
      assertEquals(result.map(_.map(dto => (dto.ok, dto.data.size))), Right(Vector((Some(true), 0))))

    test(s"composed workflow keeps whole optional-container leniency for $values"):
      val body   = s"""[{"total_count":7$workflowField}]"""
      val result = Json.decode[Vector[WorkflowRunsEnvelopeDto[IssueDto]]](body)
      assertEquals(result.map(_.map(dto => (dto.totalCount, dto.entries.size))), Right(Vector((Some(7L), 0))))

  test("valid search envelope composition retains both envelopes and original member order"):
    val body   = """[{"ok":false,"data":[]},{"ok":true,"data":[{"id":11},{"id":12}]}]"""
    val result = Json.decode[Vector[SearchEnvelopeDto[IssueDto]]](body)
    assertEquals(
      result.map(_.map(dto => (dto.ok, dto.data.map(_.id)))),
      Right(Vector((Some(false), Vector.empty), (Some(true), Vector(Some(11L), Some(12L))))),
    )

  private def optionalField(key: String, value: String): String =
    if value.isEmpty then "" else s""","$key":$value"""

  private def parsed(body: String): JsonValue =
    Json.parse(body).fold(problem => fail(problem.message), identity)

  private def assertPath[A: JsonDecoder](body: String, expected: String): Unit =
    val result: Either[DecodeFailure, A] = Json.decode[A](body)
    assertEquals(result.swap.toOption.map(_.path.render), Some(expected))
