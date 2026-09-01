package com.worxbend.codeberg4s.quota

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The one quota model both the organisation and the account routes read, and the one value type they both send.
  *
  * The subject is what a caller can observe without a network: what the smart constructor refuses, how a rule reads
  * Forgejo's negative-limit sentinel, and the distinction the breakdown is built around — an absent size is not a zero
  * one, so a heading the instance never mentioned contributes nothing to a total.
  */
final class QuotaModelSuite extends FunSuite:

  // --- quota subjects -------------------------------------------------------

  test("a quota subject keeps Forgejo's dotted vocabulary verbatim"):
    assertEquals(subject("size:assets:packages:all").value, "size:assets:packages:all")

  test("a quota subject is trimmed and otherwise untouched"):
    assertEquals(subject("  size:repos:public ").value, "size:repos:public")

  test("a quota subject this library has never heard of is still accepted, because the spec enumerates none"):
    assertEquals(subject("size:something:forgejo:adds:later").value, "size:something:forgejo:adds:later")

  test("a blank quota subject is refused on the quotaSubject field, because it asks nothing"):
    assertEquals(fieldOf(QuotaSubject.from("   ")), "quotaSubject")

  test("a quota subject carrying a control character is refused, because it becomes a query parameter"):
    assertEquals(fieldOf(QuotaSubject.from("size:\nall")), "quotaSubject")

  // --- rules ----------------------------------------------------------------

  test("a negative limit is Forgejo's spelling of unlimited, and is kept rather than folded away"):
    val rule = QuotaRule(name = Some("default"), limit = Some(-1L), subjects = Vector.empty)

    assertEquals(rule.isUnlimited, true)
    assertEquals(rule.limit, Some(-1L))

  test("an absent limit is not unlimited, because unknown is not the same as unbounded"):
    assertEquals(QuotaRule(name = None, limit = None, subjects = Vector.empty).isUnlimited, false)

  test("a stated ceiling is not unlimited"):
    assertEquals(QuotaRule(name = None, limit = Some(0L), subjects = Vector.empty).isUnlimited, false)

  test("a rule carries its subjects as the instance spelled them, so an unrecognised one is not lost"):
    val rule = QuotaRule(name = None, limit = None, subjects = Vector("size:all", "size:forgejo:adds:later"))

    assertEquals(rule.subjects, Vector("size:all", "size:forgejo:adds:later"))

  test("the rules of a quota report are every group's rules, repeats included"):
    val rule   = QuotaRule(name = Some("default"), limit = Some(10L), subjects = Vector.empty)
    val report = QuotaInfo(groups = Vector(group(rule), group(rule)), used = QuotaUsedSize.Empty)

    assertEquals(report.rules, Vector(rule, rule))

  // --- the usage breakdown --------------------------------------------------

  test("an absent size is absent rather than zero, at every heading of the empty breakdown"):
    val empty = QuotaUsedSize.Empty

    assertEquals(empty.publicRepositories, None)
    assertEquals(empty.privateRepositories, None)
    assertEquals(empty.gitLfs, None)
    assertEquals(empty.artifacts, None)
    assertEquals(empty.issueAttachments, None)
    assertEquals(empty.releaseAttachments, None)
    assertEquals(empty.packages, None)

  test("the reported total adds up only the headings the instance sent"):
    val used = QuotaUsedSize.Empty.copy(publicRepositories = Some(100L), artifacts = Some(5L))

    assertEquals(used.reportedTotal, 105L)

  test("a breakdown in which nothing was reported totals zero, which is a lower bound and not a measurement"):
    assertEquals(QuotaUsedSize.Empty.reportedTotal, 0L)

  test("an instance with quota disabled leaves the whole report empty rather than reporting a subject at zero"):
    val info = QuotaInfo(groups = Vector.empty, used = QuotaUsedSize.Empty)

    assertEquals(info.groups, Vector.empty[QuotaGroup])
    assertEquals(info.rules, Vector.empty[QuotaRule])
    assertEquals(info.used.publicRepositories, None)

  // --- helpers --------------------------------------------------------------

  private def group(rule: QuotaRule): QuotaGroup =
    QuotaGroup(name = Some("default"), rules = Vector(rule))

  private def subject(value: String): QuotaSubject =
    QuotaSubject.from(value) match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def fieldOf[A](result: Either[ValidationError, A]): String =
    result match
      case Left(error)  => error.field
      case Right(value) => fail(s"expected a rejection, got $value")
