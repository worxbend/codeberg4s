package com.worxbend.codeberg4s.issues

import munit.FunSuite

/** [[LabelName]] and [[MilestoneTitle]]: the two types that keep a comma out of a comma-separated query parameter.
  *
  * The comma is the point. Forgejo joins several label names into one `labels` parameter with no escape, so a name
  * carrying a comma would silently become two filters — the query-string equivalent of the path forging
  * [[com.worxbend.codeberg4s.repositories.Owner]] rejects.
  */
final class FilterTokenSuite extends FunSuite:

  test("an ordinary label name is accepted"):
    assertEquals(LabelName.from("needs-triage").map(_.value), Right("needs-triage"))

  test("a label name is trimmed, so a copied-and-pasted value still works"):
    assertEquals(LabelName.from("  bug  ").map(_.value), Right("bug"))

  test("a label name containing a comma is rejected, because it would become two filters"):
    assertEquals(LabelName.from("needs,triage").left.map(_.message), Left("must not contain a comma"))

  test("a blank label name is rejected"):
    assertEquals(LabelName.from("   ").left.map(_.message), Left("must not be blank"))

  test("a label name containing a control character is rejected"):
    assertEquals(LabelName.from("bug\nfeature").left.map(_.message), Left("must not contain a control character"))

  test("a label name rejection reports the labelName field"):
    assertEquals(LabelName.from("a,b").left.map(_.field), Left("labelName"))

  test("a slash is allowed in a label name, because Forgejo uses it for label scopes"):
    assertEquals(LabelName.from("bug/confirmed").map(_.value), Right("bug/confirmed"))

  test("a milestone title follows the same rule and reports its own field name"):
    assertEquals(MilestoneTitle.from("Forgejo v1.18.0-0").map(_.value), Right("Forgejo v1.18.0-0"))
    assertEquals(MilestoneTitle.from("v1, v2").left.map(_.field), Left("milestoneTitle"))
