package com.worxbend.codeberg4s

import munit.FunSuite

import java.lang.reflect.Modifier

/** Pins the operation ids, which are public API even though no signature mentions them.
  *
  * Every rail names its calls with a `…Operation: String` constant on its companion — `RepositoryApi.GetOperation` is
  * `"repos.get"`. That string travels in the `CallContext` of every request and every failure, so it is what a caller's
  * logs, metrics and dashboards group on: changing one silently re-partitions someone's telemetry, and reusing one
  * merges two endpoints into a single bucket.
  *
  * Nothing in the compiler enforces any of that, and the split into per-concern rails already shipped one mistake of
  * exactly this kind — repository Actions ids that were not scoped under `repos.actions`, found by reading the diff
  * rather than by a failing test. The three checks below are that missing test:
  *
  *   - the ids are '''unique''' across all rails, so two endpoints cannot share a bucket,
  *   - each id is a '''dotted lowerCamel path''' such as `repos.actions.runs.list`, so the shape stays greppable,
  *   - and each id sits under one of the '''roots declared for its rail''' in [[OperationIdSuite.Roots]] below.
  *
  * The root table is the load-bearing part, and it is written out by hand on purpose. Inferring the expected prefix
  * from the constants themselves would make a copy-pasted wrong scope agree with itself and pass — which is precisely
  * how the Actions mistake stayed invisible. Writing the root down means a rail's scope changes only when someone edits
  * this table and says why. A fourth test keeps the table honest by asserting its keys are exactly the rails reachable
  * from [[CodebergClient]], so a newly added group cannot skip the check by simply not being listed.
  */
final class OperationIdSuite extends FunSuite:

  import OperationIdSuite.*

  test("every rail declares at least one operation id, so the checks cannot pass vacuously"):
    val silent = reachableApis.filter(rail => operationIds(rail).isEmpty).map(_.getName).toList.sorted
    assertEquals(silent, List.empty[String], "rails whose companion declares no …Operation constant")

  test("no two rails declare the same operation id"):
    val owners     = reachableApis.toList
      .flatMap(rail => operationIds(rail).map((constant, id) => id -> s"${rail.getName}.$constant"))
      .groupMap((id, _) => id)((_, declaration) => declaration)
    val collisions = owners.toList
      .collect:
        case (id, declarations) if declarations.sizeIs > 1 =>
          s"\"$id\" declared by ${declarations.sorted.mkString(" and ")}"
      .sorted
    assertEquals(collisions, List.empty[String], "operation ids used by more than one rail")

  test("every operation id is a dotted lowerCamel path"):
    val malformed =
      for
        rail           <- reachableApis.toList
        (constant, id) <- operationIds(rail)
        if !IdShape.matches(id)
      yield s"${rail.getName}.$constant = \"$id\""

    assertEquals(malformed.sorted, List.empty[String], s"operation ids that do not match $IdShape")

  test("every operation id sits under a root declared for its rail"):
    val misscoped =
      for
        rail           <- reachableApis.toList
        roots          <- Roots.get(rail.getName).toList
        (constant, id) <- operationIds(rail)
        if !roots.exists(root => id == root || id.startsWith(s"$root."))
      yield s"${rail.getName}.$constant = \"$id\" is not under ${roots.toList.sorted.mkString(" or ")}"

    assertEquals(misscoped.sorted, List.empty[String], "operation ids scoped outside their rail's roots")

  test("the root table names exactly the rails reachable from CodebergClient"):
    val reachable = reachableApis.map(_.getName)
    assertEquals(
      (reachable -- Roots.keySet).toList.sorted,
      List.empty[String],
      "rails with no entry in the root table — add one rather than letting the scope go unchecked",
    )
    assertEquals(
      (Roots.keySet -- reachable).toList.sorted,
      List.empty[String],
      "root table entries no CodebergClient accessor reaches",
    )

object OperationIdSuite:

  /** The shape every id must have: dotted segments, each starting lowercase, at least two of them. */
  private val IdShape = "[a-z][a-zA-Z]*(\\.[a-z][a-zA-Z]*)+".r

  /** The id roots each rail is allowed to use, written out by hand so a mis-scoped id cannot agree with itself.
    *
    * A rail's root is normally the path of the resource it serves — `IssueCommentApi` owns `issues.comments.*`. Two
    * kinds of entry are deliberately broader:
    *
    *   - rails that read '''several''' sub-resources of one parent keep the parent as their root, because no narrower
    *     string covers them: `RepositoryGitApi` serves `repos.git.*` but also `repos.archive.*` and `repos.raw.*`;
    *   - `MiscellaneousApi` is the one rail with two unrelated roots, `misc` and `settings`, which is why the table
    *     holds a set per rail rather than a single string.
    */
  private val Roots: Map[String, Set[String]] = Map(
    "com.worxbend.codeberg4s.VersionApi"                          -> Set("version"),
    "com.worxbend.codeberg4s.issues.IssueApi"                     -> Set("issues"),
    "com.worxbend.codeberg4s.issues.IssueDependencyApi"           -> Set("issues.blocks", "issues.dependencies"),
    "com.worxbend.codeberg4s.issues.IssuePinApi"                  -> Set("issues.pin", "issues.unpin"),
    "com.worxbend.codeberg4s.issues.IssueAttachmentApi"           -> Set("issues"),
    "com.worxbend.codeberg4s.issues.IssueCommentApi"              -> Set("issues.comments"),
    "com.worxbend.codeberg4s.issues.IssueLabelApi"                -> Set("issues.labels"),
    "com.worxbend.codeberg4s.issues.IssueMilestoneApi"            -> Set("issues.milestones"),
    "com.worxbend.codeberg4s.issues.IssueReactionApi"             -> Set("issues"),
    "com.worxbend.codeberg4s.issues.IssueSubscriptionApi"         -> Set("issues.subscriptions"),
    "com.worxbend.codeberg4s.issues.IssueTimeApi"                 -> Set("issues"),
    "com.worxbend.codeberg4s.pulls.PullRequestApi"                -> Set("pulls"),
    "com.worxbend.codeberg4s.pulls.PullRequestReviewApi"          -> Set("pulls.reviews", "pulls.reviewRequests"),
    "com.worxbend.codeberg4s.pulls.ReviewCommentApi"              -> Set("pulls.reviews.comments"),
    "com.worxbend.codeberg4s.miscellaneous.MiscellaneousApi"      -> Set("misc", "settings"),
    "com.worxbend.codeberg4s.notifications.NotificationApi"       -> Set("notifications"),
    "com.worxbend.codeberg4s.organizations.OrganizationApi"       -> Set("orgs"),
    "com.worxbend.codeberg4s.organizations.OrganizationHookApi"   -> Set("orgs.hooks"),
    "com.worxbend.codeberg4s.organizations.OrganizationLabelApi"  -> Set("orgs.labels"),
    "com.worxbend.codeberg4s.organizations.OrganizationMemberApi" -> Set(
      "orgs.members",
      "orgs.publicMembers",
      "orgs.blocks",
      "orgs.userOrgs",
      "orgs.currentUserOrgs",
      "orgs.userPermissions",
    ),
    "com.worxbend.codeberg4s.organizations.OrganizationQuotaApi"  -> Set("orgs.quota"),
    "com.worxbend.codeberg4s.organizations.OrganizationTeamApi"   -> Set("orgs.teams"),
    "com.worxbend.codeberg4s.organizations.actions.OrganizationActionApi"     -> Set("orgs.actions"),
    "com.worxbend.codeberg4s.repositories.RepositoryApi"                      -> Set("repos"),
    "com.worxbend.codeberg4s.repositories.access.RepositoryAccessApi"         -> Set("repos"),
    "com.worxbend.codeberg4s.repositories.access.RepositoryProtectionApi"     -> Set(
      "repos.branchProtections",
      "repos.tagProtections",
    ),
    "com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi"        -> Set("repos.actions"),
    "com.worxbend.codeberg4s.repositories.actions.RepositoryActionConfigApi"  -> Set("repos.actions"),
    "com.worxbend.codeberg4s.repositories.admin.RepositoryAdminApi"           -> Set("repos.admin"),
    "com.worxbend.codeberg4s.repositories.admin.RepositoryContentApi"         -> Set("repos.admin.contents"),
    "com.worxbend.codeberg4s.repositories.admin.RepositoryInsightApi"         -> Set("repos.admin"),
    "com.worxbend.codeberg4s.repositories.admin.RepositoryMirrorApi"          -> Set("repos.admin"),
    "com.worxbend.codeberg4s.repositories.admin.RepositoryWatcherApi"         -> Set("repos.admin"),
    "com.worxbend.codeberg4s.repositories.gitdata.RepositoryGitApi"           -> Set("repos"),
    "com.worxbend.codeberg4s.repositories.gitdata.CommitStatusApi"            -> Set("repos.commits"),
    "com.worxbend.codeberg4s.repositories.gitdata.RepositoryFileApi"          -> Set(
      "repos.raw",
      "repos.media",
      "repos.archive",
      "repos.editorconfig",
    ),
    "com.worxbend.codeberg4s.repositories.hooks.RepositoryFlagApi"            -> Set("repos.flags"),
    "com.worxbend.codeberg4s.repositories.hooks.RepositoryHookApi"            -> Set("repos.hooks"),
    "com.worxbend.codeberg4s.repositories.hooks.RepositoryIssueConfigApi"     -> Set("repos"),
    "com.worxbend.codeberg4s.repositories.hooks.RepositoryWikiApi"            -> Set("repos.wiki"),
    "com.worxbend.codeberg4s.repositories.publishing.RepositoryPublishingApi" -> Set("repos"),
    "com.worxbend.codeberg4s.repositories.publishing.ReleaseAssetApi"         -> Set("repos.releases.assets"),
    "com.worxbend.codeberg4s.users.UserApi"                                   -> Set("users"),
    "com.worxbend.codeberg4s.users.account.UserAccountApi"                    -> Set("users.account"),
    "com.worxbend.codeberg4s.users.account.UserActionApi"                     -> Set("users.account.actions"),
    "com.worxbend.codeberg4s.users.account.UserApplicationApi"                -> Set("users.account.applications"),
    "com.worxbend.codeberg4s.users.account.UserHookApi"                       -> Set("users.account.hooks"),
    "com.worxbend.codeberg4s.users.account.UserQuotaApi"                      -> Set("users.account.quota"),
    "com.worxbend.codeberg4s.users.social.UserKeyApi"                         -> Set("users.keys"),
    "com.worxbend.codeberg4s.users.social.UserSocialApi"                      -> Set("users.social"),
    "com.worxbend.codeberg4s.users.social.UserTokenApi"                       -> Set("users.tokens"),
  )

  /** The operation ids a rail's companion declares, as `constant name -> id` pairs.
    *
    * The constants are `val`s on the companion object, which the compiler turns into public no-argument accessor
    * methods on the `MODULE$` singleton, so reading them back means loading the companion class — `Foo$` — taking that
    * singleton, and invoking every public accessor whose name ends in `Operation` and whose result is a `String`.
    */
  private def operationIds(rail: Class[?]): List[(String, String)] =
    val companion = Class.forName(s"${rail.getName}$$", true, rail.getClassLoader)
    val module    = companion.getField("MODULE$").get(null)
    companion.getDeclaredMethods.toList
      .filterNot(method => method.isSynthetic || method.isBridge)
      .filter(method => Modifier.isPublic(method.getModifiers))
      .filter(_.getName.endsWith("Operation"))
      .filter(_.getParameterCount == 0)
      .filter(_.getReturnType == classOf[String])
      .map(method => method.getName -> method.invoke(module).asInstanceOf[String])

  /** Every API class reachable from [[CodebergClient]] through public accessors returning `*Api` types.
    *
    * Sub-APIs such as `client.issues.comments` are found transitively, so a newly added group appears here — and so
    * fails the root-table test — before anyone remembers to register it.
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
