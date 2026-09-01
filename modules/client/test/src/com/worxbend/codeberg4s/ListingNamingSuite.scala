package com.worxbend.codeberg4s

import munit.FunSuite

import scala.concurrent.Future

import java.lang.reflect.Modifier

/** Enforces the listing-naming rule in `SCALA_CODE_STYLE.md` mechanically, so it cannot drift back.
  *
  * The rule, in one line: on an API group, `list` names the listing of the group's '''own''' resource, and a listing of
  * a '''sub'''-resource is named after its plural noun — `client.pulls.reviews(...)`, not
  * `client.pulls.listReviews(...)`. The receiver already says which group is being asked, so the extra word carries no
  * information, and one convention beats two.
  *
  * Two things are allowed to keep a `list` prefix, and both are listed below rather than pattern-matched, because a
  * pattern would quietly re-admit the drift this suite exists to catch:
  *
  *   - '''scope-disambiguated readers''', where the same sub-resource hangs off two different parents and the noun
  *     alone would not say which is meant — `IssueAttachmentApi.listForIssue` versus `listForComment`;
  *   - '''collisions''', where the bare noun on that class is already a sub-API accessor — `IssueApi.listComments`
  *     cannot become `comments`, because `client.issues.comments` is the `IssueCommentApi`.
  *
  * Adding a name here is a deliberate act: it means writing down, next to the entry, why the plural noun could not be
  * used, and saying the same thing in the method's Scaladoc.
  */
final class ListingNamingSuite extends FunSuite:

  import ListingNamingSuite.*

  test("no API group has a public list-prefixed operation outside the recorded exemptions"):
    val offenders =
      for
        api       <- reachableApis.toList
        operation <- listPrefixedOperations(api)
        if !Exempt.contains((railNameOf(api), operation))
      yield s"${api.getName}.$operation"

    assertEquals(
      offenders.sorted,
      List.empty[String],
      "list-prefixed listings that should be named after their plural noun (see SCALA_CODE_STYLE.md)",
    )

  test("every recorded exemption still names a real operation, so the list cannot rot"):
    val declared = reachableApis.groupMapReduce(railNameOf)(listPrefixedOperations)(_ ++ _)
    val stale    =
      for
        (owner, operation) <- Exempt.toList
        if !declared.get(owner).exists(_.contains(operation))
      yield s"$owner.$operation"

    assertEquals(stale.sorted, List.empty[String], "exemptions naming an operation that no longer exists")

object ListingNamingSuite:

  /** The `(class name, method name)` pairs allowed to keep a `list` prefix, with the reason each one cannot lose it. */
  private val Exempt: Set[(String, String)] = Set(
    // Scope-disambiguated: the same sub-resource hangs off two parents, so the name carries the scope, not the noun.
    "com.worxbend.codeberg4s.issues.IssueAttachmentApi"     -> "listForIssue",
    "com.worxbend.codeberg4s.issues.IssueAttachmentApi"     -> "listForComment",
    "com.worxbend.codeberg4s.issues.IssueCommentApi"        -> "listForRepository",
    "com.worxbend.codeberg4s.issues.IssueLabelApi"          -> "listOnIssue",
    "com.worxbend.codeberg4s.issues.IssueReactionApi"       -> "listOnIssue",
    "com.worxbend.codeberg4s.issues.IssueReactionApi"       -> "listOnComment",
    "com.worxbend.codeberg4s.notifications.NotificationApi" -> "listRepository",
    // Collisions: on IssueApi the bare nouns are the comments, labels and milestones sub-API accessors.
    "com.worxbend.codeberg4s.issues.IssueApi"               -> "listComments",
    "com.worxbend.codeberg4s.issues.IssueApi"               -> "listLabels",
    "com.worxbend.codeberg4s.issues.IssueApi"               -> "listMilestones",
  )

  /** The rail a class belongs to: an `Attempt` mirror is recorded under the rail whose companion nests it. */
  private def railNameOf(cls: Class[?]): String = cls.getName.stripSuffix("$Attempt")

  /** The public operations of a class whose name is `list` followed by something — `list` itself does not qualify.
    *
    * An operation is a public, non-synthetic method returning `Future`, the same definition [[AttemptParitySuite]]
    * uses: the other public members of a rail are the `attempt` accessor and the sub-API accessors, which return API
    * classes rather than futures.
    */
  private def listPrefixedOperations(cls: Class[?]): Set[String] =
    cls.getDeclaredMethods.toSet
      .filterNot(method => method.isSynthetic || method.isBridge)
      .filterNot(_.getName.contains("$default$"))
      .filter(method => Modifier.isPublic(method.getModifiers))
      .filter(_.getReturnType == classOf[Future[?]])
      .map(_.getName)
      .filter(name => name.startsWith("list") && name.length > "list".length)

  /** Every API class reachable from [[CodebergClient]], plus the `Attempt` mirror nested in each one's companion.
    *
    * The mirrors are included on purpose: they are hand-written, so a rename applied to only one rail is exactly the
    * kind of half-finished change this suite should fail on.
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

    val rails = walk(Set(classOf[CodebergClient]), Set.empty)

    rails ++ rails.flatMap: rail =>
      scala.util.Try(Class.forName(s"${rail.getName}$$Attempt", false, rail.getClassLoader)).toOption
