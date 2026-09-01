package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.admin.ActivityOperation
import com.worxbend.codeberg4s.repositories.admin.CreateRepository
import com.worxbend.codeberg4s.users.UserVisibility
import com.worxbend.codeberg4s.users.account.AvatarImage

import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import java.time.LocalDate

/** The organisation resource itself: creating, editing, renaming and deleting it, its avatar, its members, its blocks,
  * its repositories and its two cross-account reads.
  *
  * The ten operations `OrganizationApiSuite` covers are not repeated here.
  */
final class OrganizationAdminApiSuite extends FunSuite with OrganizationStubs:

  // --- create, edit, delete -------------------------------------------------

  test("orgs.create posts to the collection and sends the handle under username, as CreateOrgOption spells it"):
    val backend = RecordingBackend(responding(201, OrganizationAdminApiSuite.OrgBody))

    onApi(backend): api =>
      api
        .create(CreateOrganization.named(Org).displayedAs("Forgejo").visibleAs(UserVisibility.Public))
        .map: organization =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs")
          assertEquals(
            bodyOf(backend),
            """{"username":"forgejo","full_name":"Forgejo","visibility":"public"}""",
          )
          assertEquals(organization.name.value, "forgejo")

  test("orgs.create is never retried, because a repeat answers 422 for a handle now taken"):
    val backend = RecordingBackend(flakyThen(201, OrganizationAdminApiSuite.OrgBody))

    onApi(backend): api =>
      api.attempt
        .create(CreateOrganization.named(Org))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the create was repeated"))

  test("orgs.edit patches the organisation and never sends a name"):
    val backend = RecordingBackend(responding(200, OrganizationAdminApiSuite.OrgBody))

    onApi(backend): api =>
      api
        .edit(Org, EditOrganization.Empty.describedAs("we forge"))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo")
          assertEquals(bodyOf(backend), """{"description":"we forge"}""")

  test("orgs.edit is never retried, because the handle in its path is one rename can move"):
    val backend = RecordingBackend(flakyThen(200, OrganizationAdminApiSuite.OrgBody))

    onApi(backend): api =>
      api.attempt
        .edit(Org, EditOrganization.Empty.describedAs("we forge"))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the edit was repeated"))

  test("orgs.delete sends a bodiless DELETE"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api
        .delete(Org)
        .map: _ =>
          assertEquals(methodOf(backend), "DELETE")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo")
          assertEquals(bodyOf(backend), NoBody)

  test("orgs.delete is never retried, because a retry after a lost success could delete a recreated organisation"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt.delete(Org).map(_ => assertEquals(attemptsOn(backend), 1, "the delete was repeated"))

  // --- rename ---------------------------------------------------------------

  test("orgs.rename posts the new handle to the rename sub-resource"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api
        .rename(Org, orFail(OrgName.from("forgejo-forge")))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/rename")
          assertEquals(bodyOf(backend), """{"new_name":"forgejo-forge"}""")

  test("orgs.rename is never retried, because a repeat would rename whatever now holds the old handle"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt
        .rename(Org, orFail(OrgName.from("forgejo-forge")))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the rename was repeated"))

  // --- avatar ---------------------------------------------------------------

  test("orgs.avatar.update posts base64 JSON, not a multipart upload"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api
        .updateAvatar(Org, AvatarImage.ofBytes("forge".getBytes("UTF-8")))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/avatar")
          assertEquals(bodyOf(backend), """{"image":"Zm9yZ2U="}""")

  test("orgs.avatar.delete sends a bodiless DELETE and is never retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt
        .deleteAvatar(Org)
        .map: _ =>
          assertEquals(methodOf(backend), "DELETE")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/avatar")
          assertEquals(attemptsOn(backend), 1, "the avatar delete was repeated")

  // --- repositories ---------------------------------------------------------

  test("orgs.repos.create posts CreateRepoOption to the organisation's repository collection"):
    val backend = RecordingBackend(responding(201, OrganizationAdminApiSuite.RepoBody))

    onApi(backend): api =>
      api
        .createRepository(Org, CreateRepository.named(orFail(RepoName.from("codeberg4s"))).initialised)
        .map: repository =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/repos")
          assertEquals(
            bodyOf(backend),
            """{"name":"codeberg4s","private":false,"auto_init":true,"template":false}""",
          )
          assertEquals(repository.slug.value, "forgejo/forgejo")

  test("the deprecated repository create really is the singular /org path, not a duplicate of the plural one"):
    val plural   = RecordingBackend(responding(201, OrganizationAdminApiSuite.RepoBody))
    val singular = RecordingBackend(responding(201, OrganizationAdminApiSuite.RepoBody))
    val command  = CreateRepository.named(orFail(RepoName.from("codeberg4s")))

    for
      _ <- onApi(plural)(api => api.createRepository(Org, command))
      _ <- onApi(singular)(api => api.createRepositoryDeprecated(Org, command))
    yield
      assertEquals(pathOf(plural), "https://forge.example/api/v1/orgs/forgejo/repos")
      assertEquals(pathOf(singular), "https://forge.example/api/v1/org/forgejo/repos")
      assertEquals(bodyOf(singular), bodyOf(plural))

  test("the two repository creates carry different operation ids, so the deprecated path can be alerted on"):
    onApi(responding(404, OrganizationStubs.NotFoundBody)): api =>
      val command = CreateRepository.named(orFail(RepoName.from("codeberg4s")))

      for
        plural   <- api.attempt.createRepository(Org, command)
        singular <- api.attempt.createRepositoryDeprecated(Org, command)
      yield
        assertEquals(operationOf(plural), OrganizationApi.CreateRepositoryOperation)
        assertEquals(operationOf(singular), OrganizationApi.CreateRepositoryDeprecatedOperation)

  // --- membership probes ----------------------------------------------------

  test("orgs.members.check reads a 204 as membership"):
    onApi(responding(204, "")): api =>
      api.isMember(Org, Account).map(member => assertEquals(member, true))

  test("orgs.members.check reads a 404 as an answer, not as a failure"):
    onApi(responding(404, "")): api =>
      api.isMember(Org, Account).map(member => assertEquals(member, false))

  test("a 404 from the membership probe is a Right(false) on the typed rail too"):
    onApi(responding(404, "")): api =>
      api.attempt.isMember(Org, Account).map(result => assertEquals(result, Right(false)))

  test("a 403 from the membership probe still fails, so an unreadable organisation is not a non-member"):
    onApi(responding(403, OrganizationStubs.UnauthorizedBody)): api =>
      api.attempt.isMember(Org, Account).map:
        case Left(CodebergError.Api(_, status, _, _)) => assertEquals(status, 403)
        case other                                    => fail(s"expected an Api failure, got $other")

  test("the two membership probes are different paths, not one with a flag"):
    val member       = RecordingBackend(responding(204, ""))
    val publicMember = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(member)(api => api.isMember(Org, Account))
      _ <- onApi(publicMember)(api => api.isPublicMember(Org, Account))
    yield
      assertEquals(pathOf(member), "https://forge.example/api/v1/orgs/forgejo/members/earl-warren")
      assertEquals(pathOf(publicMember), "https://forge.example/api/v1/orgs/forgejo/public_members/earl-warren")

  // --- membership writes ----------------------------------------------------

  test("removing a member, publicising one and concealing one are three methods on two paths"):
    val removed    = RecordingBackend(responding(204, ""))
    val publicised = RecordingBackend(responding(204, ""))
    val concealed  = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(removed)(api => api.removeMember(Org, Account))
      _ <- onApi(publicised)(api => api.publicizeMember(Org, Account))
      _ <- onApi(concealed)(api => api.concealMember(Org, Account))
    yield
      assertEquals(
        (methodOf(removed), pathOf(removed)),
        ("DELETE", "https://forge.example/api/v1/orgs/forgejo/members/earl-warren"),
      )
      assertEquals(
        (methodOf(publicised), pathOf(publicised)),
        ("PUT", "https://forge.example/api/v1/orgs/forgejo/public_members/earl-warren"),
      )
      assertEquals(
        (methodOf(concealed), pathOf(concealed)),
        ("DELETE", "https://forge.example/api/v1/orgs/forgejo/public_members/earl-warren"),
      )

  test("every membership write carries no body, because the path is the whole statement"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.publicizeMember(Org, Account).map(_ => assertEquals(bodyOf(backend), NoBody))

  test("no membership write is retried, because an organisation handle is not an identifier"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt
        .publicizeMember(Org, Account)
        .map(_ => assertEquals(attemptsOn(backend), 1, "a membership write was repeated"))

  // --- blocks ---------------------------------------------------------------

  test("orgs.blocks.add and orgs.blocks.remove are both PUT, at two different segments"):
    val blocked   = RecordingBackend(responding(204, ""))
    val unblocked = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(blocked)(api => api.blockUser(Org, Account))
      _ <- onApi(unblocked)(api => api.unblockUser(Org, Account))
    yield
      assertEquals(
        (methodOf(blocked), pathOf(blocked)),
        ("PUT", "https://forge.example/api/v1/orgs/forgejo/block/earl-warren"),
      )
      assertEquals(
        (methodOf(unblocked), pathOf(unblocked)),
        ("PUT", "https://forge.example/api/v1/orgs/forgejo/unblock/earl-warren"),
      )

  test("orgs.blocks.list pages the block records and decodes their two fields"):
    val backend = RecordingBackend(responding(200, """[{"block_id":41,"created_at":"2026-02-09T10:11:12Z"}]"""))

    onApi(backend): api =>
      api
        .blockedUsers(Org, window(2, 5))
        .map: page =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/list_blocked")
          assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "5"))
          assertEquals(page.items.map(_.blockId.value), Vector(41L))

  // --- activity feed --------------------------------------------------------

  test("orgs.activities.list sends no date when the caller named no day"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .activities(Org, None, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/activities/feeds")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))

  test("orgs.activities.list renders a named day as ISO-8601 ahead of the window"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .activities(Org, Some(LocalDate.of(2026, 2, 9)), PageParams.First)
        .map(_ => assertEquals(queryOf(backend), List("date" -> "2026-02-09", "page" -> "1", "limit" -> "30")))

  test("orgs.activities.list yields the Activity model the repository group owns, not a second one"):
    onApi(responding(200, OrganizationAdminApiSuite.ActivityListBody)): api =>
      api.activities(Org, None, PageParams.First).map: page =>
        assertEquals(page.items.map(_.id.value), Vector(9001L))
        assertEquals(page.items.map(_.operation), Vector(Some(ActivityOperation.CreateRepo)))

  // --- cross-account reads --------------------------------------------------

  test("orgs.currentUserOrgs.list names nobody, so it sits at the singular /user/orgs"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .currentUserOrganizations(window(1, 10))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/user/orgs")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "10"))

  test("the `orgs.userPermissions.get` route hangs off the person, then the organisation"):
    val backend = RecordingBackend(responding(200, """{"is_owner":false,"can_read":true}"""))

    onApi(backend): api =>
      api
        .userPermissions(Account, Org)
        .map: permissions =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/users/earl-warren/orgs/forgejo/permissions",
          )
          assertEquals(permissions.canRead, true)
          assertEquals(permissions.isOwner, false)

  test("an absent permission flag reads as 'may not' all the way through the pipeline"):
    onApi(responding(200, "{}")): api =>
      api
        .userPermissions(Account, Org)
        .map(permissions => assertEquals(permissions, OrganizationPermissions(false, false, false, false, false)))

  // --- failures -------------------------------------------------------------

  test("a 403 on the create reaches both rails identically"):
    onApi(responding(403, OrganizationStubs.UnauthorizedBody)): api =>
      for
        raised <- api.create(CreateOrganization.named(Org)).failed
        typed  <- api.attempt.create(CreateOrganization.named(Org))
      yield
        assertEquals(operationOf(typed), OrganizationApi.CreateOperation)
        assertRailsAgree(raised, typed)

  test("a 422 on the rename reaches both rails identically"):
    onApi(responding(422, OrganizationStubs.NotFoundBody)): api =>
      val newName = orFail(OrgName.from("forgejo-forge"))

      for
        raised <- api.rename(Org, newName).failed
        typed  <- api.attempt.rename(Org, newName)
      yield
        assertEquals(operationOf(typed), OrganizationApi.RenameOperation)
        assertRailsAgree(raised, typed)

  test("a 404 on the block listing reaches both rails identically"):
    onApi(responding(404, OrganizationStubs.NotFoundBody)): api =>
      for
        raised <- api.blockedUsers(Org, PageParams.First).failed
        typed  <- api.attempt.blockedUsers(Org, PageParams.First)
      yield
        assertEquals(operationOf(typed), OrganizationApi.BlockedUsersOperation)
        assertRailsAgree(raised, typed)

  test("a 401 on the authenticated organisation listing reaches both rails identically"):
    onApi(responding(401, OrganizationStubs.UnauthorizedBody)): api =>
      for
        raised <- api.currentUserOrganizations(PageParams.First).failed
        typed  <- api.attempt.currentUserOrganizations(PageParams.First)
      yield
        assertEquals(operationOf(typed), OrganizationApi.CurrentUserOrganizationsOperation)
        assertRailsAgree(raised, typed)

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(401, OrganizationStubs.UnauthorizedBody)): api =>
      api.blockedUsers(Org, PageParams.First).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (OrganizationApi.BlockedUsersOperation, 401, Some("token is required")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a bad element of the block listing reports its position all the way through the pipeline"):
    onApi(responding(200, """[{"block_id":1},{"block_id":0}]""")): api =>
      api.attempt.blockedUsers(Org, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].block_id")
        case other                                             => fail(s"expected a decoding failure, got $other")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object OrganizationAdminApiSuite:

  /** `golden/organization/org-single.json`, reduced to the keys these tests assert on. */
  val OrgBody: String =
    """{"id": 70422, "name": "forgejo", "full_name": "Forgejo", "visibility": "public"}"""

  /** An element of `golden/organization/org-repos-list.json`, reduced likewise. */
  val RepoBody: String =
    """{"id": 70845, "name": "forgejo", "full_name": "forgejo/forgejo", "owner": {"id": 70422, "login": "forgejo"}}"""

  /** An `Activity` as the pinned spec defines one; no capture of a feed exists. */
  val ActivityListBody: String =
    """[{"id": 9001, "op_type": "create_repo", "is_private": false, "created": "2026-02-09T10:11:12Z"}]"""
