package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.PullRequest
import com.worxbend.codeberg4s.pulls.PullRequestState

import munit.FunSuite

import java.time.Instant

/** [[PullRequestDto]] against all four golden pull-request captures.
  *
  * The fixtures are the reason the model is shaped the way it is: `single-open.json` and `single-merged.json` were
  * harvested as separate cases precisely so the lifecycle could be modelled from evidence rather than from the spec,
  * which declares nothing required and nothing nullable (`docs/HAZARDS.md` §1).
  */
final class PullRequestDtoSuite extends FunSuite with GoldenFixtures:

  test("the open golden pull request converts field for field"):
    val pull = open

    assertEquals(pull.id, 2786975L)
    assertEquals(pull.number.value, 13731L)
    assertEquals(pull.title, "fix(package/pypi): response header based on PEP691")
    assertEquals(pull.author.map(_.login), Some("trim21"))
    assertEquals(pull.isDraft, false)
    assertEquals(pull.isLocked, false)
    assertEquals(pull.allowsMaintainerEdit, true)
    assertEquals(pull.isMergeable, Some(true))
    assertEquals(pull.htmlUrl, Some("https://codeberg.org/forgejo/forgejo/pulls/13731"))
    assertEquals(pull.createdAt, Some(Instant.parse("2026-08-01T19:56:55Z")))

  test("an open pull request is Open and carries no merge evidence at all"):
    assertEquals(open.state, PullRequestState.Open)
    assertEquals(open.state.isOpen, true)
    assertEquals(open.state.isMerged, false)

  test("the counts Forgejo keeps separate stay separate"):
    val pull = open

    assertEquals(pull.commentCount, 0L)
    assertEquals(pull.reviewCommentCount, 0L)
    assertEquals(pull.additions, 146L)
    assertEquals(pull.deletions, 19L)
    assertEquals(pull.changedFileCount, 3L)

  test("the nulls the spec swears cannot happen decode as absence, not as a crash"):
    val pull = open

    assertEquals(pull.assignees, Vector.empty)
    assertEquals(pull.milestone, None)
    assertEquals(pull.dueDate, None)

  test("requested reviewers are read, and are not the same thing as reviews"):
    assertEquals(open.requestedReviewers.map(_.login), Vector("Gusted", "Cyborus"))

  test("labels embedded in a pull request convert with the rest of it"):
    val pull = open

    assertEquals(pull.labels.map(_.name), Vector("bug/confirmed", "code/packages", "test/present"))
    assertEquals(pull.labels.map(_.id.value), Vector(201023L, 441003L, 201030L))

  test("base and head are read as two ends, each with its own repository"):
    (open.base, open.head) match
      case (Some(base), Some(head)) =>
        assertEquals(base.ref.map(_.value), Some("forgejo"))
        assertEquals(base.repositoryId, Some(73144L))
        assertEquals(base.repository.map(_.slug.value), Some("forgejo/forgejo"))
        assertEquals(head.ref.map(_.value), Some("fix-pep691"))
        assertEquals(head.repositoryId, Some(2366912L))
        assertEquals(head.repository.map(_.slug.value), Some("trim21/forgejo"))
      case other                    => fail(s"the open pull request was expected to carry both ends, got $other")

  /** A fork pull request is exactly the case where the two repository ids differ; keeping both on the model is what
    * lets a caller notice.
    */
  test("base and head repository ids are both readable, and on this pull request they differ"):
    assertEquals(
      (open.base.flatMap(_.repositoryId), open.head.flatMap(_.repositoryId)),
      (Some(73144L), Some(2366912L)),
    )

  test("the head repository's own parent converts too, so a fork chain survives"):
    assertEquals(open.head.flatMap(_.repository).flatMap(_.parent).map(_.slug.value), Some("forgejo/forgejo"))

  test("merge_base is validated as an object id rather than kept as a string"):
    assertEquals(open.mergeBase.map(_.value), Some("647de8b3279b0ce6e9721cc651e431981725edf1"))

  // --- the merged arm --------------------------------------------------------

  test("a merged pull request is Merged, carrying the instant, the account and the merge commit"):
    merged.state match
      case PullRequestState.Merged(at, by, commit) =>
        assertEquals(at, Some(Instant.parse("2026-08-01T17:15:31Z")))
        assertEquals(by.map(_.login), Some("mfenniak"))
        assertEquals(commit.map(_.value), Some("38615e78ed86c1eaaadd086f00a807ea4cc96a19"))
      case other                                   => fail(s"expected a merged pull request, got $other")

  test("a merged pull request reports state 'closed' on the wire, and is still not Closed here"):
    assertEquals(merged.state.isMerged, true)
    assertEquals(merged.state.isOpen, false)
    assert(
      merged.state match
        case PullRequestState.Closed(_) => false
        case _                          => true,
      "a merged pull request must not decode as Closed — Forgejo sends state: \"closed\" for it",
    )

  test("an embedded milestone converts, including its own lifecycle state"):
    assertEquals(merged.milestone.map(_.title), Some("Forgejo v16.0.3"))
    assertEquals(merged.milestone.map(_.id.value), Some(137464L))

  test("a head branch that was deleted on merge keeps its raw pull ref rather than losing the field"):
    assertEquals(merged.head.flatMap(_.ref).map(_.value), Some("refs/pull/13726/head"))

  // --- the listings ----------------------------------------------------------

  test("the mixed listing decodes three pull requests, two of which are still open"):
    val all = list("pull/list-all.json")

    assertEquals(all.map(_.number.value), Vector(13731L, 13730L, 13728L))
    assertEquals(all.count(_.state.isOpen), 2)

  test("a closed listing holds a merged pull request beside rejected ones, so state=closed is not 'rejected'"):
    val closed = list("pull/list-closed.json")

    assertEquals(closed.map(_.number.value), Vector(13730L, 13726L, 13711L))
    assertEquals(closed.count(_.state.isMerged), 1)
    assertEquals(closed.filterNot(_.state.isMerged).count(_.state.isClosed), 2)

  test("a rejected pull request is Closed and carries the instant it was closed"):
    assertEquals(
      list("pull/list-closed.json").headOption.map(_.state),
      Some(PullRequestState.Closed(Some(Instant.parse("2026-08-01T19:12:33Z")))),
    )

  /** The measurement [[PullRequestState.Merged]] is built around: the same pull request, read two ways. */
  test("the listing endpoint reports a merge with no merged_by, where the single read supplies one"):
    list("pull/list-closed.json").lift(1).map(_.state) match
      case Some(PullRequestState.Merged(at, by, commit)) =>
        assertEquals(at, Some(Instant.parse("2026-08-01T17:15:31Z")))
        assertEquals(by, None, "the listing endpoint does not resolve the merging account")
        assertEquals(commit.map(_.value), Some("38615e78ed86c1eaaadd086f00a807ea4cc96a19"))
      case other                                         => fail(s"expected the merged pull request second, got $other")

  // --- failure paths ---------------------------------------------------------

  test("a pull request with no number is a decoding failure at $.number"):
    assertEquals(failingPath("""{"id":1,"title":"t","state":"open"}"""), "$.number")

  test("a pull request with no title is a decoding failure at $.title"):
    assertEquals(failingPath("""{"id":1,"number":2,"state":"open"}"""), "$.title")

  test("a pull request with no state is a decoding failure at $.state"):
    assertEquals(failingPath("""{"id":1,"number":2,"title":"t"}"""), "$.state")

  test("a state that is neither spelling fails, because the lifecycle has no unknown case"):
    assertEquals(failingPath("""{"id":1,"number":2,"title":"t","state":"draft"}"""), "$.state")

  test("merge evidence outranks an unknown state, because a merge is not ambiguous"):
    val decoded = convert("""{"id":1,"number":2,"title":"t","state":"weird","merged":true}""")

    assertEquals(decoded.map(_.state.isMerged), Right(true))

  test("a merged_at with no merged flag is still a merge"):
    val body    = """{"id":1,"number":2,"title":"t","state":"closed","merged_at":"2026-08-01T19:15:31+02:00"}"""
    val decoded = convert(body)

    assertEquals(decoded.map(_.state.isMerged), Right(true))

  test("a merge_commit_sha that is not hexadecimal is reported at its own path, not dropped"):
    assertEquals(
      failingPath("""{"id":1,"number":2,"title":"t","state":"open","merge_commit_sha":"not-a-sha"}"""),
      "$.merge_commit_sha",
    )

  test("a bad sha inside head is reported at the nested path"):
    assertEquals(
      failingPath("""{"id":1,"number":2,"title":"t","state":"open","head":{"sha":"zz"}}"""),
      "$.head.sha",
    )

  test("a head ref that is not a branch name costs the field, not the pull request"):
    val decoded = convert("""{"id":1,"number":2,"title":"t","state":"open","head":{"ref":"../escape"}}""")

    assertEquals(decoded.map(_.head.flatMap(_.ref)), Right(None))

  test("a bad requested reviewer is reported at its own position"):
    assertEquals(
      failingPath(
        """{"id":1,"number":2,"title":"t","state":"open","requested_reviewers":[{"id":1,"login":"a"},{"id":2}]}"""
      ),
      "$.requested_reviewers[1].login",
    )

  test("a failing element of a list reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[PullRequestDto]](
        """[{"id":1,"number":1,"title":"t","state":"open"},{"id":2,"number":2,"title":"t"}]"""
      )
      .flatMap(dtos => PullRequestDto.toDomainAll(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].state")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a garbage body is a DecodeFailure, not an escaping codec exception"):
    assert(Json.decode[PullRequestDto]("not json at all").isLeft)
    assert(Json.decode[Vector[PullRequestDto]]("""{"id":1}""").isLeft)

  private def open: PullRequest =
    one("pull/single-open.json")

  private def merged: PullRequest =
    one("pull/single-merged.json")

  private def one(path: String): PullRequest =
    convert(golden(path)) match
      case Right(pull)   => pull
      case Left(failure) => fail(s"could not decode $path: $failure")

  private def list(path: String): Vector[PullRequest] =
    Json
      .decode[Vector[PullRequestDto]](golden(path))
      .flatMap(dtos => PullRequestDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(values) => values
      case Left(failure) => fail(s"could not decode $path: $failure")

  private def convert(body: String): Either[DecodeFailure, PullRequest] =
    Json.decode[PullRequestDto](body).flatMap(_.toDomain)

  private def failingPath(body: String): String =
    convert(body) match
      case Left(failure) => failure.path.render
      case Right(value)  => fail(s"expected a failure, converted $value")
