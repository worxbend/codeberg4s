package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.wire.IssueDto
import com.worxbend.codeberg4s.quota.wire.{QuotaGroupDto, QuotaInfoDto}
import com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto
import com.worxbend.codeberg4s.repositories.gitdata.wire.{CombinedStatusDto, NoteDto}
import com.worxbend.codeberg4s.repositories.hooks.wire.{IssueConfigDto, IssueTemplateDto, WikiCommitListDto}
import com.worxbend.codeberg4s.users.social.wire.{AccessTokenDto, GpgKeyDto}
import com.worxbend.codeberg4s.users.wire.UserDto
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

import munit.FunSuite

/** Remaining consumed object arrays and recursive/embedded seams not covered by the initial RED suite. */
final class NestedMemberConsumersSuite extends FunSuite:

  private def path[A: JsonDecoder](body: String): Option[String] =
    val decoded: Either[DecodeFailure, A] = Json.decode[A](body)
    decoded.swap.toOption.map(_.path.render)

  test("quota groups reject malformed members"):
    assertEquals(path[QuotaInfoDto]("""{"groups":[{},null]}"""), Some("$.groups[1]"))

  test("quota groups validate their embedded rule array"):
    assertEquals(path[QuotaInfoDto]("""{"groups":[{}, {"rules":[{},7]}]}"""), Some("$.groups[1].rules[1]"))

  test("a standalone quota group uses the same validation"):
    assertEquals(path[QuotaGroupDto]("""{"rules":[{},[]]}"""), Some("$.rules[1]"))

  test("GPG emails reject malformed members"):
    assertEquals(path[GpgKeyDto]("""{"emails":[{},null]}"""), Some("$.emails[1]"))

  test("recursive GPG subkeys retain all original indexes"):
    val body = """{"subkeys":[{}, {"subkeys":[{}, {"emails":[{},7]}]}]}"""
    assertEquals(path[GpgKeyDto](body), Some("$.subkeys[1].subkeys[1].emails[1]"))

  test("token repository metadata rejects malformed members"):
    assertEquals(path[AccessTokenDto]("""{"repositories":[{},[]]}"""), Some("$.repositories[1]"))

  test("combined status rejects malformed members"):
    assertEquals(path[CombinedStatusDto]("""{"statuses":[{},7]}"""), Some("$.statuses[1]"))

  test("a note validates its embedded commit"):
    assertEquals(path[NoteDto]("""{"commit":{"files":[{},null]}}"""), Some("$.commit.files[1]"))

  test("issue configuration contact links reject malformed members"):
    assertEquals(path[IssueConfigDto]("""{"contact_links":[{},null]}"""), Some("$.contact_links[1]"))

  test("issue form fields reject malformed members"):
    assertEquals(path[IssueTemplateDto]("""{"body":[{},null]}"""), Some("$.body[1]"))

  test("wiki commit listings reject malformed members"):
    assertEquals(path[WikiCommitListDto]("""{"commits":[{},null]}"""), Some("$.commits[1]"))

  test("generic search envelopes retain the original index of a checked DTO child failure"):
    val body = """{"data":[{}, {"labels":[{},null]}]}"""
    assertEquals(path[SearchEnvelopeDto[IssueDto]](body), Some("$.data[1].labels[1]"))

  test("generic workflow envelopes retain the original index of a checked DTO child failure"):
    val body = """{"workflow_runs":[{}, {"labels":[{},null]}]}"""
    assertEquals(path[WorkflowRunsEnvelopeDto[IssueDto]](body), Some("$.workflow_runs[1].labels[1]"))

  test("mapped checked decoders retain contextual member indexes"):
    val element = JsonDecoder[IssueDto].map(_.labels.size)
    val parsed  = Json.parse("""[{}, {"labels":[{},null]}]""").fold(problem => fail(problem.message), identity)
    assertEquals(JsonDecoder.arrayOf(element).decode(parsed).swap.toOption.map(_.path.render), Some("$[1].labels[1]"))

  test("schema-known names in unrelated DTO extension data are not globally validated"):
    assert(Json.decode[UserDto]("""{"id":1,"login":"ada","labels":[null],"groups":[7]}""").isRight)

  test("unknown array fields inside a checked DTO remain untouched"):
    assert(Json.decode[QuotaInfoDto]("""{"groups":[{"extension":{"rules":[null]}}],"labels":[null]}""").isRight)

  test("recursive whole wrong-kind GPG containers retain optional-container leniency"):
    assert(Json.decode[GpgKeyDto]("""{"emails":7,"subkeys":[{"emails":{},"subkeys":"unknown"}]}""").isRight)
