package com.worxbend.codeberg4s.repositories.hooks

import munit.FunSuite

/** The four half-open enumerations of this group, and the property that makes them safe: nothing is ever lost.
  *
  * Each one recognises a documented vocabulary and carries anything else through in an `Other` case, so parsing a wire
  * value and rendering it again is the identity. That is what lets a hook subscribed to an event a later Forgejo
  * release adds still round-trip through this library — and it is the reason none of these parsers answers `None`.
  */
final class HookEnumSuite extends FunSuite:

  private val EventSpellings: Vector[String] = Vector(
    "create",
    "delete",
    "fork",
    "push",
    "issues",
    "issue_assign",
    "issue_label",
    "issue_milestone",
    "issue_comment",
    "pull_request",
    "pull_request_assign",
    "pull_request_label",
    "pull_request_milestone",
    "pull_request_comment",
    "pull_request_review_approved",
    "pull_request_review_rejected",
    "pull_request_review_comment",
    "pull_request_review_request",
    "pull_request_sync",
    "wiki",
    "repository",
    "release",
    "package",
    "status",
  )

  /** The eleven values `CreateHookOption.type` enumerates in `spec/swagger.v1.json`, verbatim. */
  private val TypeSpellings: Vector[String] = Vector(
    "forgejo",
    "dingtalk",
    "discord",
    "gitea",
    "gogs",
    "msteams",
    "slack",
    "telegram",
    "feishu",
    "wechatwork",
    "packagist",
  )

  test("every documented event spelling parses to a named case and renders back unchanged"):
    EventSpellings.foreach: spelling =>
      val parsed = HookEvent.parse(spelling)

      assertEquals(parsed.wireValue, spelling)
      assert(!isOtherEvent(parsed), s"$spelling should have a named case")

  test("every named event case renders one of the documented spellings, and they line up one for one"):
    assertEquals(HookEnumSuite.NamedEvents.map(_.wireValue), EventSpellings)

  test("an event name this library does not know survives as Other, rather than being dropped"):
    assertEquals(HookEvent.parse("action_run_failure"), HookEvent.Other("action_run_failure"))
    assertEquals(HookEvent.parse("action_run_failure").wireValue, "action_run_failure")

  test("event parsing trims and lower-cases, so two spellings of one unknown event compare equal"):
    assertEquals(HookEvent.parse("  PUSH  "), HookEvent.Push)
    assertEquals(HookEvent.parse(" Weird_Thing "), HookEvent.parse("weird_thing"))

  test("every spec-enumerated hook type parses to a named case and renders back unchanged"):
    TypeSpellings.foreach: spelling =>
      val parsed = HookType.parse(spelling)

      assertEquals(parsed.wireValue, spelling)
      assert(!isOtherType(parsed), s"$spelling should have a named case")

  test("every named hook type case is one the spec enumerates, and they line up one for one"):
    assertEquals(HookEnumSuite.NamedTypes.map(_.wireValue).sorted, TypeSpellings.sorted)

  test("a hook type outside the spec's enum survives as Other, because a response is not constrained by it"):
    assertEquals(HookType.parse("matrix"), HookType.Other("matrix"))
    assertEquals(HookType.parse("matrix").wireValue, "matrix")

  test("a content type parses to its named case and renders back unchanged"):
    assertEquals(HookContentType.parse("json"), HookContentType.Json)
    assertEquals(HookContentType.parse("FORM").wireValue, "form")

  test("an unknown content type survives as Other"):
    assertEquals(HookContentType.parse("protobuf"), HookContentType.Other("protobuf"))

  test("an unknown content type renders back the spelling it arrived with, so an edit cannot rewrite it"):
    assertEquals(HookContentType.parse("protobuf").wireValue, "protobuf")
    assertEquals(HookContentType.Other("application/cbor").wireValue, "application/cbor")

  test("every documented issue form field type parses and renders back unchanged"):
    Vector("markdown", "textarea", "input", "dropdown", "checkboxes").foreach: spelling =>
      assertEquals(IssueFormFieldType.parse(spelling).wireValue, spelling)

  test("an unknown issue form field type survives as Other"):
    assertEquals(IssueFormFieldType.parse("slider"), IssueFormFieldType.Other("slider"))

  test("an unknown issue form field type renders back the spelling it arrived with"):
    assertEquals(IssueFormFieldType.parse("slider").wireValue, "slider")
    assertEquals(IssueFormFieldType.Other("matrix").wireValue, "matrix")

  private def isOtherEvent(event: HookEvent): Boolean =
    event match
      case HookEvent.Other(_) => true
      case _                  => false

  private def isOtherType(hookType: HookType): Boolean =
    hookType match
      case HookType.Other(_) => true
      case _                 => false

/** The named cases of the two half-open enums, written out.
  *
  * `HookEvent` and `HookType` both carry a parameterised `Other` case, so Scala generates no `values` for either and
  * the list has to be stated. That is a cost of the shape rather than an oversight: what the list buys is that a case
  * added without a wire spelling, or with one the spec does not name, fails this suite.
  */
object HookEnumSuite:

  private val NamedEvents: Vector[HookEvent] = Vector(
    HookEvent.Create,
    HookEvent.Delete,
    HookEvent.Fork,
    HookEvent.Push,
    HookEvent.Issues,
    HookEvent.IssueAssign,
    HookEvent.IssueLabel,
    HookEvent.IssueMilestone,
    HookEvent.IssueComment,
    HookEvent.PullRequest,
    HookEvent.PullRequestAssign,
    HookEvent.PullRequestLabel,
    HookEvent.PullRequestMilestone,
    HookEvent.PullRequestComment,
    HookEvent.PullRequestReviewApproved,
    HookEvent.PullRequestReviewRejected,
    HookEvent.PullRequestReviewComment,
    HookEvent.PullRequestReviewRequest,
    HookEvent.PullRequestSync,
    HookEvent.Wiki,
    HookEvent.Repository,
    HookEvent.Release,
    HookEvent.Package,
    HookEvent.Status,
  )

  private val NamedTypes: Vector[HookType] = Vector(
    HookType.Forgejo,
    HookType.Gitea,
    HookType.Gogs,
    HookType.Slack,
    HookType.Discord,
    HookType.Dingtalk,
    HookType.MsTeams,
    HookType.Telegram,
    HookType.Feishu,
    HookType.WeChatWork,
    HookType.Packagist,
  )
