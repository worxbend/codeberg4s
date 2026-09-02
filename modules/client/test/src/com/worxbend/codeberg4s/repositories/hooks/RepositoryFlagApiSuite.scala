package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

/** [[RepositoryFlagApi]] over a `BackendStub`.
  *
  * The interesting endpoint here is [[RepositoryFlagApi.check]], which answers a question with a status rather than
  * with a body — so the assertion that matters is which rail the answer arrives on.
  */
final class RepositoryFlagApiSuite extends FunSuite with HookStubs:

  private val Featured: RepositoryFlag = orFail(RepositoryFlag.from("featured"))

  private val Archived: RepositoryFlag = orFail(RepositoryFlag.from("archived-2024"))

  test("the flag listing targets the repository's flags, unpaged, and validates every element"):
    val backend = RecordingBackend(responding(200, """["featured","archived-2024"]"""))

    onApi(backend): api =>
      api.list(Handle, Name).map: flags =>
        assertEquals(pathOf(backend), s"$Repository/flags")
        assertEquals(queryOf(backend), Nil)
        assertEquals(flags.map(_.value), Vector("featured", "archived-2024"))

  test("a flag that could forge a path fails the listing at its own position"):
    onApi(responding(200, """["featured","a/b"]""")): api =>
      api.attempt.list(Handle, Name).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1]")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a present flag completes the convenience rail and is a Right on the typed one"):
    onApi(responding(204, "")): api =>
      api.attempt.check(Handle, Name, Featured).map(outcome => assertEquals(outcome, Right(())))

  test("an absent flag is a 404 on both rails, which is how the question is answered"):
    onApi(responding(404, HookStubs.NotFoundBody)): api =>
      for
        raised <- api.check(Handle, Name, Featured).failed
        typed  <- api.attempt.check(Handle, Name, Featured)
      yield assertRailsAgree(raised, typed)

  test("a flag check addresses the flag as a path segment"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.check(Handle, Name, Archived).map: _ =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$Repository/flags/archived-2024")

  test("flags.replaceAll PUTs the whole set and is retried, because it is an assignment"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api
        .replaceAll(Handle, Name, Vector(Featured, Archived))
        .map: _ =>
          assertEquals(methodOf(backend), "PUT")
          assertEquals(pathOf(backend), s"$Repository/flags")
          assertEquals(bodyOf(backend), """{"flags":["featured","archived-2024"]}""")
          assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("flags.replaceAll with an empty vector asks for every flag to be cleared"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.replaceAll(Handle, Name, Vector.empty).map(_ => assertEquals(bodyOf(backend), """{"flags":[]}"""))

  test("flags.deleteAll addresses the repository's flags and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.deleteAll(Handle, Name).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Repository/flags")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("flags.add PUTs an empty body to the named flag and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.add(Handle, Name, Featured).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Repository/flags/featured")
        assertEquals(bodyOf(backend), "")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("flags.delete addresses the named flag and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, Featured).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Repository/flags/featured")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("a 403 on a write reaches both rails identically, as an administrative refusal does"):
    onApi(responding(403, HookStubs.NotFoundBody)): api =>
      for
        raised <- api.add(Handle, Name, Featured).failed
        typed  <- api.attempt.add(Handle, Name, Featured)
      yield
        assertRailsAgree(raised, typed)
        assertEquals(summary(materialise(typed))._1, RepositoryFlagApi.AddOperation)

  private def materialise[A](result: Either[CodebergError, A]): CodebergError =
    result match
      case Left(error) => error
      case Right(_)    => fail("expected a failure")

  private def onApi[A](backend: Backend[Future])(use: RepositoryFlagApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(RepositoryFlagApi(pipeline)))
