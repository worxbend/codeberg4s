package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.Owner

import munit.FunSuite

import java.time.Instant

/** [[IssueSearchQuery]] — the eighteen-parameter cross-repository search — and the two closed sets it carries.
  *
  * Every builder is applied to a query that already has '''every''' filter set, and compared against that query with
  * the one field replaced. That is what catches the copy-shaped defect a builder type exists to prevent: a builder
  * applied to [[IssueSearchQuery.Empty]] cannot show that it dropped a sibling, because there was no sibling to drop.
  *
  * The five account-relative flags get the mirror treatment — each is applied to a query where that one flag is off and
  * the other four are on, so a builder that sets the wrong flag, sets none, or clears one of the others all fail.
  */
final class IssueSearchQuerySuite extends FunSuite:

  private val June: Instant = Instant.parse("2026-06-01T00:00:00Z")

  private val July: Instant = Instant.parse("2026-07-01T00:00:00Z")

  private val August: Instant = Instant.parse("2026-08-01T00:00:00Z")

  private val Bug: LabelName = orFail(LabelName.from("bug"))

  private val Chore: LabelName = orFail(LabelName.from("chore"))

  private val Release: MilestoneTitle = orFail(MilestoneTitle.from("v1.0"))

  private val Next: MilestoneTitle = orFail(MilestoneTitle.from("v1.1"))

  private val Forgejo: Owner = orFail(Owner.from("forgejo"))

  private val Codeberg: Owner = orFail(Owner.from("codeberg"))

  // --- the empty query ------------------------------------------------------

  test("the empty search carries no filter at all, so the instance's own defaults apply"):
    assertEquals(IssueSearchQuery.Empty.state, None)
    assertEquals(IssueSearchQuery.Empty.labels, Vector.empty[LabelName])
    assertEquals(IssueSearchQuery.Empty.milestones, Vector.empty[MilestoneTitle])
    assertEquals(IssueSearchQuery.Empty.text, None)
    assertEquals(IssueSearchQuery.Empty.priorityRepoId, None)
    assertEquals(IssueSearchQuery.Empty.kind, None)
    assertEquals(IssueSearchQuery.Empty.since, None)
    assertEquals(IssueSearchQuery.Empty.before, None)
    assertEquals(IssueSearchQuery.Empty.owner, None)
    assertEquals(IssueSearchQuery.Empty.team, None)
    assertEquals(IssueSearchQuery.Empty.sort, None)

  test("the five account-relative filters start off, because false and absent are the same request"):
    assertEquals(IssueSearchQuery.Empty.assigned, false)
    assertEquals(IssueSearchQuery.Empty.created, false)
    assertEquals(IssueSearchQuery.Empty.mentioned, false)
    assertEquals(IssueSearchQuery.Empty.reviewRequested, false)
    assertEquals(IssueSearchQuery.Empty.reviewed, false)

  // --- the optional filters -------------------------------------------------

  test("every optional search filter sets its own field and leaves every sibling alone"):
    val search = populated

    assertEquals(search.withState(StateFilter.Closed), search.copy(state = Some(StateFilter.Closed)))
    assertEquals(search.withLabels(Vector(Chore)), search.copy(labels = Vector(Chore)))
    assertEquals(search.withMilestones(Vector(Next)), search.copy(milestones = Vector(Next)))
    assertEquals(search.matching("smart HTTP"), search.copy(text = Some("smart HTTP")))
    assertEquals(search.prioritising(99L), search.copy(priorityRepoId = Some(99L)))
    assertEquals(search.onlyOf(IssueKind.Pulls), search.copy(kind = Some(IssueKind.Pulls)))
    assertEquals(search.updatedSince(August), search.copy(since = Some(August)))
    assertEquals(search.updatedBefore(August), search.copy(before = Some(August)))
    assertEquals(search.ownedBy(Codeberg), search.copy(owner = Some(Codeberg)))
    assertEquals(search.inTeam("owners"), search.copy(team = Some("owners")))
    assertEquals(search.sortedBy(IssueSearchSort.FarDueDate), search.copy(sort = Some(IssueSearchSort.FarDueDate)))

  test("since and before are the two ends of one window and are set independently"):
    assertEquals(IssueSearchQuery.Empty.updatedSince(June).before, None)
    assertEquals(IssueSearchQuery.Empty.updatedBefore(July).since, None)
    assertEquals(IssueSearchQuery.Empty.updatedSince(June).updatedBefore(July).since, Some(June))

  test("an empty vector drops a comma-joined filter rather than asking for issues that carry none"):
    assertEquals(populated.withLabels(Vector.empty).labels, Vector.empty[LabelName])
    assertEquals(populated.withMilestones(Vector.empty).milestones, Vector.empty[MilestoneTitle])

  test("the last call for a filter wins, so a query can be narrowed and then widened again"):
    val search = IssueSearchQuery.Empty.withState(StateFilter.All).withState(StateFilter.Open)

    assertEquals(search.state, Some(StateFilter.Open))

  test("a team restriction does not imply the owner Forgejo wants alongside it, and never invents one"):
    assertEquals(IssueSearchQuery.Empty.inTeam("reviewers").owner, None)
    assertEquals(IssueSearchQuery.Empty.inTeam("reviewers").ownedBy(Forgejo).team, Some("reviewers"))

  // --- the five account-relative filters ------------------------------------

  test("each account-relative filter turns on its own flag and disturbs nothing else"):
    assertEquals(populated.copy(assigned = false).assignedToMe, populated)
    assertEquals(populated.copy(created = false).createdByMe, populated)
    assertEquals(populated.copy(mentioned = false).mentioningMe, populated)
    assertEquals(populated.copy(reviewRequested = false).awaitingMyReview, populated)
    assertEquals(populated.copy(reviewed = false).reviewedByMe, populated)

  test("an account-relative filter applied twice is the same request as applying it once"):
    assertEquals(IssueSearchQuery.Empty.assignedToMe.assignedToMe, IssueSearchQuery.Empty.assignedToMe)

  // --- IssueSearchSort ------------------------------------------------------

  test("every ordering is spelled the way the sort parameter needs, one literal per case"):
    assertEquals(IssueSearchSort.Relevance.wireValue, "relevance")
    assertEquals(IssueSearchSort.Latest.wireValue, "latest")
    assertEquals(IssueSearchSort.Oldest.wireValue, "oldest")
    assertEquals(IssueSearchSort.RecentUpdate.wireValue, "recentupdate")
    assertEquals(IssueSearchSort.LeastUpdate.wireValue, "leastupdate")
    assertEquals(IssueSearchSort.MostComment.wireValue, "mostcomment")
    assertEquals(IssueSearchSort.LeastComment.wireValue, "leastcomment")
    assertEquals(IssueSearchSort.NearDueDate.wireValue, "nearduedate")
    assertEquals(IssueSearchSort.FarDueDate.wireValue, "farduedate")

  test("the spec's sort enum has nine values, and no two of them are spelled the same"):
    assertEquals(IssueSearchSort.values.length, 9)
    assertEquals(IssueSearchSort.values.toVector.map(_.wireValue).distinct.length, 9)

  test("no ordering is punctuated or capitalised, which is exactly how a hand-written one goes wrong"):
    IssueSearchSort.values.foreach: order =>
      assert(order.wireValue.forall(_.isLower), s"${order.toString} is not all lower case: ${order.wireValue}")

  // --- IssueKind ------------------------------------------------------------

  test("the kind filter is the pair the spec enumerates, in that order and with those spellings"):
    assertEquals(IssueKind.values.toVector.map(_.wireValue), Vector("issues", "pulls"))

  test("an unset kind is a third request and not a synonym for either case"):
    assertEquals(IssueSearchQuery.Empty.kind, None)
    assertEquals(IssueSearchQuery.Empty.onlyOf(IssueKind.Issues).kind, Some(IssueKind.Issues))
    assertEquals(IssueSearchQuery.Empty.onlyOf(IssueKind.Pulls).kind, Some(IssueKind.Pulls))

  // --- helpers --------------------------------------------------------------

  /** A search with every filter set and all five account-relative flags on, so that a builder cannot lose one
    * unnoticed.
    */
  private def populated: IssueSearchQuery =
    IssueSearchQuery(
      state           = Some(StateFilter.Open),
      labels          = Vector(Bug),
      milestones      = Vector(Release),
      text            = Some("original"),
      priorityRepoId  = Some(41L),
      kind            = Some(IssueKind.Issues),
      since           = Some(June),
      before          = Some(July),
      assigned        = true,
      created         = true,
      mentioned       = true,
      reviewRequested = true,
      reviewed        = true,
      owner           = Some(Forgejo),
      team            = Some("reviewers"),
      sort            = Some(IssueSearchSort.Latest),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
