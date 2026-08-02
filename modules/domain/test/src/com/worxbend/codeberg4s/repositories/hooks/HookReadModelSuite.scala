package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import java.time.Instant

/** The two questions the read models of this group answer without a caller matching on their fields.
  *
  * [[Webhook.subscribesTo]] is what decides whether an event a caller cares about will actually be delivered, and it
  * has to be right about an event this library does not have a named case for — a subscription to a Forgejo release's
  * new event arrives as [[HookEvent.Other]] and still has to compare equal to itself.
  *
  * [[IssueTemplate.isForm]] is the only thing that tells a Markdown template from a form one, and the two are read
  * completely differently: a Markdown template's body is in `content`, a form's is a list of fields.
  */
final class HookReadModelSuite extends FunSuite:

  test("a hook subscribes to exactly the events it lists, and to nothing it does not"):
    val hook = webhook(Vector(HookEvent.Push, HookEvent.PullRequest))

    assertEquals(hook.subscribesTo(HookEvent.Push), true)
    assertEquals(hook.subscribesTo(HookEvent.PullRequest), true)
    assertEquals(hook.subscribesTo(HookEvent.Release), false)
    assertEquals(hook.subscribesTo(HookEvent.PullRequestSync), false)

  test("a hook subscribing to nothing subscribes to nothing, rather than to everything"):
    assertEquals(webhook(Vector.empty).subscribesTo(HookEvent.Push), false)

  test("a subscription to an event this library has no case for is still reported"):
    val hook = webhook(Vector(HookEvent.parse("action_run_failure")))

    assertEquals(hook.subscribesTo(HookEvent.Other("action_run_failure")), true)
    assertEquals(hook.subscribesTo(HookEvent.Other("action_run_success")), false)
    assertEquals(hook.subscribesTo(HookEvent.Push), false)

  test("a template with fields is a form, and one with only a body is not"):
    assertEquals(template(Vector.empty).isForm, false)
    assertEquals(template(Vector(field("summary"))).isForm, true)

  test("a template whose body is empty text is still not a form, because a form is made of fields"):
    assertEquals(template(Vector.empty).copy(content = Some("")).isForm, false)

  private def webhook(events: Vector[HookEvent]): Webhook =
    Webhook(
      id            = orFail(HookId.from(7L)),
      hookType      = Some(HookType.Forgejo),
      configuration = HookConfig.of("https://ci.example/hook", HookContentType.Json),
      events        = events,
      url           = Some("https://codeberg.org/api/v1/repos/a/b/hooks/7"),
      branchFilter  = Some("*"),
      isActive      = Some(true),
      createdAt     = Some(Instant.EPOCH),
      updatedAt     = None,
    )

  private def template(fields: Vector[IssueFormField]): IssueTemplate =
    IssueTemplate(
      fileName = "bug.yaml",
      name     = Some("Bug report"),
      about    = Some("Something is broken"),
      title    = None,
      content  = Some("## What happened"),
      labels   = Vector("bug"),
      ref      = None,
      fields   = fields,
    )

  private def field(id: String): IssueFormField =
    IssueFormField(
      id          = Some(id),
      fieldType   = Some(IssueFormFieldType.Textarea),
      attributes  = Map("label" -> "Summary"),
      validations = Map("required" -> "true"),
      visible     = Vector("form"),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
