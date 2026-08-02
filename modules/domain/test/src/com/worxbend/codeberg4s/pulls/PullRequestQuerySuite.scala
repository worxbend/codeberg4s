package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.MilestoneId
import com.worxbend.codeberg4s.issues.StateFilter
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.Owner

import munit.FunSuite

/** [[PullRequestQuery]]'s builders. The behaviour worth pinning is that each one sets exactly the filter it names: a
  * builder that also reset a sibling would silently widen or narrow the listing, and the caller would see a plausible
  * page of the wrong pull requests. Every builder is therefore applied to a query with '''every''' filter already set,
  * and the result compared against the one-field change it promises.
  */
final class PullRequestQuerySuite extends FunSuite:

  private val Bug: LabelId = orFail(LabelId.from(102L))

  private val Chore: LabelId = orFail(LabelId.from(103L))

  private val Release: MilestoneId = orFail(MilestoneId.from(3109L))

  private val Next: MilestoneId = orFail(MilestoneId.from(3110L))

  private val Main: BranchName = orFail(BranchName.from("main"))

  private val Stable: BranchName = orFail(BranchName.from("v1/stable"))

  private val Fork: PullRequestHead = PullRequestHead.crossRepository(orFail(Owner.from("earl-warren")), Main)

  /** Every filter set, and set to something the assertions below will visibly replace. */
  private val Filtered: PullRequestQuery =
    PullRequestQuery(
      state     = Some(StateFilter.Open),
      sort      = Some(PullRequestSort.Oldest),
      milestone = Some(Release),
      labels    = Vector(Bug),
      poster    = Some("earl-warren"),
      base      = Some(Main),
      head      = Some(PullRequestHead.branch(Stable)),
    )

  test("the empty query carries no filter at all, so Forgejo's own default of open only applies"):
    assertEquals(PullRequestQuery.Empty.state, None)
    assertEquals(PullRequestQuery.Empty.sort, None)
    assertEquals(PullRequestQuery.Empty.milestone, None)
    assertEquals(PullRequestQuery.Empty.labels, Vector.empty[LabelId])
    assertEquals(PullRequestQuery.Empty.poster, None)
    assertEquals(PullRequestQuery.Empty.base, None)
    assertEquals(PullRequestQuery.Empty.head, None)

  test("withState changes the state and nothing else"):
    assertEquals(Filtered.withState(StateFilter.All), Filtered.copy(state = Some(StateFilter.All)))

  test("sortedBy changes the ordering and nothing else"):
    assertEquals(
      Filtered.sortedBy(PullRequestSort.RecentUpdate),
      Filtered.copy(sort = Some(PullRequestSort.RecentUpdate)),
    )

  test("inMilestone changes the milestone and nothing else"):
    assertEquals(Filtered.inMilestone(Next), Filtered.copy(milestone = Some(Next)))

  test("withLabels changes the labels and nothing else"):
    assertEquals(Filtered.withLabels(Vector(Chore)), Filtered.copy(labels = Vector(Chore)))

  test("authoredBy changes the poster and nothing else"):
    assertEquals(Filtered.authoredBy("crystal"), Filtered.copy(poster = Some("crystal")))

  test("withBase changes the base and nothing else — in particular it leaves the head alone"):
    assertEquals(Filtered.withBase(Stable), Filtered.copy(base = Some(Stable)))

  test("withHead changes the head and nothing else — in particular it leaves the base alone"):
    assertEquals(Filtered.withHead(Fork), Filtered.copy(head = Some(Fork)))

  test("a builder on the empty query leaves every other filter absent"):
    val query = PullRequestQuery.Empty.sortedBy(PullRequestSort.MostComment)

    assertEquals(query.sort, Some(PullRequestSort.MostComment))
    assertEquals(query.state, None)
    assertEquals(query.labels, Vector.empty[LabelId])
    assertEquals(query.base, None)

  test("builders compose, and the last call for one filter wins"):
    val query = PullRequestQuery.Empty.withState(StateFilter.All).withState(StateFilter.Closed).withBase(Main)

    assertEquals(query.state, Some(StateFilter.Closed))
    assertEquals(query.base, Some(Main))

  test("an empty label vector removes the filter rather than asking for pull requests with no label"):
    assertEquals(Filtered.withLabels(Vector.empty).labels, Vector.empty[LabelId])

  test("labels are required together, so the order the caller gave is the order that is sent"):
    assertEquals(PullRequestQuery.Empty.withLabels(Vector(Chore, Bug)).labels, Vector(Chore, Bug))

  test("a cross-repository head keeps Forgejo's owner:branch spelling"):
    assertEquals(Fork.value, "earl-warren:main")

  test("a same-repository head is the bare branch name, with no colon"):
    assertEquals(PullRequestHead.branch(Stable).value, "v1/stable")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
