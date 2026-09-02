package com.worxbend.codeberg4s

import munit.FunSuite

import scala.concurrent.Future

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Enforces ADR-0005 mechanically: the convenience rail and its hand-written `Attempt` mirror cannot disagree.
  *
  * Every API group carries two rails — the methods on the group itself, failing the `Future`, and the same operations
  * on the nested `Attempt`, returning `Either[CodebergError, A]`. The mirrors are written by hand, so nothing in the
  * type system keeps them in step; this suite closes that gap by reflection. For every registered pair it checks that
  *
  *   - every public rail operation (a public method returning `Future`) has an `Attempt` method with the same name and
  *     the same erased parameter list,
  *   - the `Attempt` declares no operation the rail lacks,
  *   - the `Attempt` method's return type is exactly the rail's with `Either[CodebergError, _]` spliced inside the
  *     `Future` (compared on generic signatures, because erasure hides the type arguments),
  *   - and the registry itself is complete: walking the API-typed accessors reachable from [[CodebergClient]] finds
  *     exactly the rail classes listed here, so a new API group cannot be added without joining the check.
  */
final class AttemptParitySuite extends FunSuite:

  import AttemptParitySuite.*

  test("every rail declares at least one operation, so the parity checks cannot pass vacuously"):
    for pair <- Pairs do
      assert(
        operationKeys(pair.rail).nonEmpty,
        s"${pair.rail.getName}: no public Future-returning methods found — the operation filter is broken",
      )

  test("every rail operation has an Attempt mirror with the same name and erased parameters"):
    for pair <- Pairs do
      val rail    = operationKeys(pair.rail)
      val mirror  = operationKeys(pair.attempt)
      val missing = rail -- mirror
      assert(
        missing.isEmpty,
        s"${pair.rail.getName}: operations without an Attempt mirror: ${render(missing)}",
      )

  test("the Attempt declares no operation the rail lacks"):
    for pair <- Pairs do
      val rail   = operationKeys(pair.rail)
      val mirror = operationKeys(pair.attempt)
      val extra  = mirror -- rail
      assert(
        extra.isEmpty,
        s"${pair.attempt.getName}: Attempt methods with no rail counterpart: ${render(extra)}",
      )

  test("each Attempt mirror returns the rail's result wrapped in Either[CodebergError, _]"):
    for pair <- Pairs do
      val rails   = operations(pair.rail)
      val mirrors = operations(pair.attempt)
      for (key, railMethod) <- rails do
        mirrors.get(key).foreach: mirrorMethod =>
          val expected = s"scala.concurrent.Future<scala.util.Either<${classOf[CodebergError].getName}, " +
            s"${futureElement(railMethod)}>>"
          assertEquals(
            mirrorMethod.getGenericReturnType.getTypeName,
            expected,
            s"${pair.attempt.getName}.${key._1}: Attempt return type disagrees with the rail",
          )

  test("the pair registry covers exactly the API classes reachable from CodebergClient"):
    val registered = Pairs.map(_.rail).toSet
    val reachable  = reachableApis
    assertEquals(
      (reachable -- registered).map(_.getName).toList.sorted,
      List.empty[String],
      "API classes reachable from CodebergClient but missing from this suite's registry",
    )
    assertEquals(
      (registered -- reachable).map(_.getName).toList.sorted,
      List.empty[String],
      "registry entries no CodebergClient accessor reaches",
    )
    assertEquals(Pairs.map(_.rail).distinct.size, Pairs.size, "duplicate rail classes in the registry")

  test("each registered Attempt is the nested Attempt of its own rail's companion"):
    for pair <- Pairs do
      assertEquals(
        pair.attempt.getName,
        s"${pair.rail.getName}$$Attempt",
        s"${pair.attempt.getName} is not the Attempt nested in ${pair.rail.getName}'s companion",
      )

object AttemptParitySuite:

  /** One rail class and its hand-written mirror. */
  private final case class Pair(rail: Class[?], attempt: Class[?])

  /** Every API class with a nested `Attempt`, paired with that `Attempt`. Kept complete by the coverage test. */
  private val Pairs: Seq[Pair] = Seq(
    Pair(classOf[VersionApi], classOf[VersionApi.Attempt]),
    Pair(classOf[issues.IssueApi], classOf[issues.IssueApi.Attempt]),
    Pair(classOf[issues.IssueCommentApi], classOf[issues.IssueCommentApi.Attempt]),
    Pair(classOf[issues.IssueAttachmentApi], classOf[issues.IssueAttachmentApi.Attempt]),
    Pair(classOf[issues.IssueReactionApi], classOf[issues.IssueReactionApi.Attempt]),
    Pair(classOf[issues.IssueLabelApi], classOf[issues.IssueLabelApi.Attempt]),
    Pair(classOf[issues.IssueMilestoneApi], classOf[issues.IssueMilestoneApi.Attempt]),
    Pair(classOf[issues.IssueTimeApi], classOf[issues.IssueTimeApi.Attempt]),
    Pair(classOf[issues.IssueSubscriptionApi], classOf[issues.IssueSubscriptionApi.Attempt]),
    Pair(classOf[pulls.PullRequestApi], classOf[pulls.PullRequestApi.Attempt]),
    Pair(classOf[pulls.PullRequestReviewApi], classOf[pulls.PullRequestReviewApi.Attempt]),
    Pair(classOf[miscellaneous.MiscellaneousApi], classOf[miscellaneous.MiscellaneousApi.Attempt]),
    Pair(classOf[notifications.NotificationApi], classOf[notifications.NotificationApi.Attempt]),
    Pair(classOf[organizations.OrganizationApi], classOf[organizations.OrganizationApi.Attempt]),
    Pair(classOf[organizations.OrganizationHookApi], classOf[organizations.OrganizationHookApi.Attempt]),
    Pair(classOf[organizations.OrganizationLabelApi], classOf[organizations.OrganizationLabelApi.Attempt]),
    Pair(classOf[organizations.OrganizationMemberApi], classOf[organizations.OrganizationMemberApi.Attempt]),
    Pair(classOf[organizations.OrganizationQuotaApi], classOf[organizations.OrganizationQuotaApi.Attempt]),
    Pair(classOf[organizations.OrganizationTeamApi], classOf[organizations.OrganizationTeamApi.Attempt]),
    Pair(
      classOf[organizations.actions.OrganizationActionApi],
      classOf[organizations.actions.OrganizationActionApi.Attempt],
    ),
    Pair(classOf[repositories.RepositoryApi], classOf[repositories.RepositoryApi.Attempt]),
    Pair(classOf[repositories.access.RepositoryAccessApi], classOf[repositories.access.RepositoryAccessApi.Attempt]),
    Pair(
      classOf[repositories.access.RepositoryProtectionApi],
      classOf[repositories.access.RepositoryProtectionApi.Attempt],
    ),
    Pair(
      classOf[repositories.actions.RepositoryActionApi],
      classOf[repositories.actions.RepositoryActionApi.Attempt],
    ),
    Pair(
      classOf[repositories.actions.RepositoryActionConfigApi],
      classOf[repositories.actions.RepositoryActionConfigApi.Attempt],
    ),
    Pair(classOf[repositories.admin.RepositoryAdminApi], classOf[repositories.admin.RepositoryAdminApi.Attempt]),
    Pair(classOf[repositories.admin.RepositoryContentApi], classOf[repositories.admin.RepositoryContentApi.Attempt]),
    Pair(classOf[repositories.admin.RepositoryInsightApi], classOf[repositories.admin.RepositoryInsightApi.Attempt]),
    Pair(classOf[repositories.admin.RepositoryMirrorApi], classOf[repositories.admin.RepositoryMirrorApi.Attempt]),
    Pair(classOf[repositories.admin.RepositoryWatcherApi], classOf[repositories.admin.RepositoryWatcherApi.Attempt]),
    Pair(classOf[repositories.gitdata.RepositoryGitApi], classOf[repositories.gitdata.RepositoryGitApi.Attempt]),
    Pair(classOf[repositories.hooks.RepositoryFlagApi], classOf[repositories.hooks.RepositoryFlagApi.Attempt]),
    Pair(classOf[repositories.hooks.RepositoryHookApi], classOf[repositories.hooks.RepositoryHookApi.Attempt]),
    Pair(
      classOf[repositories.hooks.RepositoryIssueConfigApi],
      classOf[repositories.hooks.RepositoryIssueConfigApi.Attempt],
    ),
    Pair(classOf[repositories.hooks.RepositoryWikiApi], classOf[repositories.hooks.RepositoryWikiApi.Attempt]),
    Pair(
      classOf[repositories.publishing.RepositoryPublishingApi],
      classOf[repositories.publishing.RepositoryPublishingApi.Attempt],
    ),
    Pair(classOf[users.UserApi], classOf[users.UserApi.Attempt]),
    Pair(classOf[users.account.UserAccountApi], classOf[users.account.UserAccountApi.Attempt]),
    Pair(classOf[users.account.UserActionApi], classOf[users.account.UserActionApi.Attempt]),
    Pair(classOf[users.account.UserApplicationApi], classOf[users.account.UserApplicationApi.Attempt]),
    Pair(classOf[users.account.UserHookApi], classOf[users.account.UserHookApi.Attempt]),
    Pair(classOf[users.account.UserQuotaApi], classOf[users.account.UserQuotaApi.Attempt]),
    Pair(classOf[users.social.UserKeyApi], classOf[users.social.UserKeyApi.Attempt]),
    Pair(classOf[users.social.UserSocialApi], classOf[users.social.UserSocialApi.Attempt]),
    Pair(classOf[users.social.UserTokenApi], classOf[users.social.UserTokenApi.Attempt]),
  )

  /** An operation's identity across the two rails: its name and erased parameter types. */
  private type Key = (String, List[Class[?]])

  /** The public operations a class declares: public, non-synthetic methods returning `Future`, keyed for matching.
    *
    * Filtering on the `Future` return type is what separates operations from the other public members of a rail — the
    * `attempt` accessor and the sub-API accessors return API classes, not futures. Synthetic and bridge methods are
    * compiler plumbing, and `name$default$n` methods carry default-argument values, so none of them are operations
    * either.
    */
  private def operations(cls: Class[?]): Map[Key, Method] =
    cls.getDeclaredMethods.toList
      .filterNot(method => method.isSynthetic || method.isBridge)
      .filterNot(_.getName.contains("$default$"))
      .filter(method => Modifier.isPublic(method.getModifiers))
      .filter(_.getReturnType == classOf[Future[?]])
      .map(method => (method.getName, method.getParameterTypes.toList) -> method)
      .toMap

  private def operationKeys(cls: Class[?]): Set[Key] = operations(cls).keySet

  /** The `A` in a rail method's `Future[A]`, read off the generic signature so erasure does not hide it. */
  private def futureElement(railMethod: Method): String =
    val typeName = railMethod.getGenericReturnType.getTypeName
    val prefix   = "scala.concurrent.Future<"
    assert(
      typeName.startsWith(prefix) && typeName.endsWith(">"),
      s"${railMethod.getDeclaringClass.getName}.${railMethod.getName}: expected a Future return, saw $typeName",
    )
    typeName.drop(prefix.length).dropRight(1)

  /** Every API class reachable from [[CodebergClient]] through public accessors returning `*Api` types.
    *
    * This is the registry's completeness oracle: sub-APIs such as `client.issues.comments` are found transitively, so a
    * newly added group shows up here before anyone remembers to register it above.
    */
  private def reachableApis: Set[Class[?]] =
    def apiAccessors(cls: Class[?]): Set[Class[?]] =
      cls.getDeclaredMethods.toSet
        .filterNot(_.isSynthetic)
        .filter(method => Modifier.isPublic(method.getModifiers))
        .map(_.getReturnType)
        .filter(returned => returned.getName.startsWith("com.worxbend.codeberg4s"))
        .filter(_.getSimpleName.endsWith("Api"))

    @scala.annotation.tailrec
    def walk(frontier: Set[Class[?]], seen: Set[Class[?]]): Set[Class[?]] =
      val discovered = frontier.flatMap(apiAccessors) -- seen
      if discovered.isEmpty then seen else walk(discovered, seen ++ discovered)

    walk(Set(classOf[CodebergClient]), Set.empty)

  private def render(keys: Set[Key]): String =
    keys.toList
      .map((name, params) => s"$name(${params.map(_.getSimpleName).mkString(", ")})")
      .sorted
      .mkString("; ")
