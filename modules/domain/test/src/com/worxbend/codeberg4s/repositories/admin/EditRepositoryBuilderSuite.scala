package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.BranchName

import munit.FunSuite

/** What [[EditRepository]]'s builders change, and what they must not touch.
  *
  * The endpoint's contract is "only fields that are set will be changed", so the dangerous defect in this type is not a
  * builder that sets the wrong value — it is a builder that clears a field the caller had already set. Every builder is
  * therefore applied to an edit with '''every''' property stated, and compared against that same edit with one field
  * replaced. On an empty edit the same defect would be invisible.
  *
  * The second subject is the tri-state: for every `Option[Boolean]` here, `Some(false)` turns a feature off and `None`
  * leaves it as the instance has it, and the two are different requests.
  */
final class EditRepositoryBuilderSuite extends FunSuite:

  private val Trunk: BranchName = orFail(BranchName.from("trunk"))

  private val ExternalTracker: ExternalTrackerSettings =
    ExternalTrackerSettings(
      url           = Some("https://bugs.example/"),
      format        = Some("https://bugs.example/{user}/{repo}/{index}"),
      style         = Some("numeric"),
      regexpPattern = None,
    )

  private val ExternalWiki: ExternalWikiSettings = ExternalWikiSettings(Some("https://wiki.example/"))

  private val InternalTracker: InternalTrackerSettings =
    InternalTrackerSettings(
      enableTimeTracker                = Some(true),
      allowOnlyContributorsToTrackTime = Some(false),
      enableIssueDependencies          = Some(true),
    )

  test("every edit builder sets its own field and leaves every sibling alone"):
    val edit = populated

    assertEquals(edit.renamedTo(repo("renamed")), edit.copy(name = Some(repo("renamed"))))
    assertEquals(edit.describedAs("replaced"), edit.copy(description = Some("replaced")))
    assertEquals(edit.linkingTo("https://replaced.example"), edit.copy(website = Some("https://replaced.example")))
    assertEquals(edit.madePrivate, edit.copy(isPrivate = Some(true)))
    assertEquals(edit.madePublic, edit.copy(isPrivate = Some(false)))
    assertEquals(edit.defaultingTo(Trunk), edit.copy(defaultBranch = Some(Trunk)))
    assertEquals(edit.archivedRepository, edit.copy(archived = Some(true)))
    assertEquals(edit.unarchivedRepository, edit.copy(archived = Some(false)))
    assertEquals(edit.withIssues(true), edit.copy(hasIssues = Some(true)))
    assertEquals(edit.withWiki(true), edit.copy(hasWiki = Some(true)))
    assertEquals(edit.withPullRequests(true), edit.copy(hasPullRequests = Some(true)))
    assertEquals(edit.withActions(true), edit.copy(hasActions = Some(true)))
    assertEquals(edit.mergingBy(MergeStyle.Squash), edit.copy(defaultMergeStyle = Some(MergeStyle.Squash)))
    assertEquals(edit.updatingBy(UpdateStyle.Merge), edit.copy(defaultUpdateStyle = Some(UpdateStyle.Merge)))
    assertEquals(edit.mirroringEvery("24h0m0s"), edit.copy(mirrorInterval = Some("24h0m0s")))
    assertEquals(edit.trackingIssuesAt(ExternalTracker), edit.copy(externalTracker = Some(ExternalTracker)))
    assertEquals(edit.hostingWikiAt(ExternalWiki), edit.copy(externalWiki = Some(ExternalWiki)))
    assertEquals(edit.trackingIssuesWith(InternalTracker), edit.copy(internalTracker = Some(InternalTracker)))

  test("turning a feature off states false, which is not the same request as not mentioning it"):
    assertEquals(EditRepository.Empty.withIssues(false).hasIssues, Some(false))
    assertEquals(EditRepository.Empty.withWiki(false).hasWiki, Some(false))
    assertEquals(EditRepository.Empty.withPullRequests(false).hasPullRequests, Some(false))
    assertEquals(EditRepository.Empty.withActions(false).hasActions, Some(false))
    assertEquals(EditRepository.Empty.hasIssues, None)
    assertEquals(EditRepository.Empty.hasActions, None)

  test("each feature switch is its own field, so turning one off leaves the other three unmentioned"):
    val edit = EditRepository.Empty.withActions(false)

    assertEquals(edit.hasActions, Some(false))
    assertEquals(edit.hasIssues, None)
    assertEquals(edit.hasWiki, None)
    assertEquals(edit.hasPullRequests, None)

  test("renaming a repository does not un-archive it, which would be a write to a frozen repository"):
    val edit = EditRepository.Empty.archivedRepository.renamedTo(repo("renamed"))

    assertEquals(edit.archived, Some(true))
    assertEquals(edit.name.map(_.value), Some("renamed"))

  test("private and public are the same field, so the later statement wins rather than both being sent"):
    assertEquals(EditRepository.Empty.madePrivate.madePublic.isPrivate, Some(false))
    assertEquals(EditRepository.Empty.madePublic.madePrivate.isPrivate, Some(true))

  test("an edit stating anything at all is no longer the empty edit"):
    assertEquals(EditRepository.Empty.isEmpty, true)
    assertEquals(EditRepository.Empty.describedAs("").isEmpty, false)
    assertEquals(EditRepository.Empty.withIssues(false).isEmpty, false)
    assertEquals(EditRepository.Empty.mirroringEvery("8h0m0s").isEmpty, false)
    assertEquals(EditRepository.Empty.trackingIssuesWith(InternalTracker).isEmpty, false)

  test("the merge style and the update style are separate fields, though both name a strategy"):
    val edit = EditRepository.Empty.mergingBy(MergeStyle.Rebase)

    assertEquals(edit.defaultMergeStyle, Some(MergeStyle.Rebase))
    assertEquals(edit.defaultUpdateStyle, None)
    assertEquals(EditRepository.Empty.updatingBy(UpdateStyle.Rebase).defaultMergeStyle, None)

  test("the external tracker and the internal tracker are separate settings, not two spellings of one"):
    val edit = EditRepository.Empty.trackingIssuesAt(ExternalTracker).trackingIssuesWith(InternalTracker)

    assertEquals(edit.externalTracker.flatMap(_.url), Some("https://bugs.example/"))
    assertEquals(edit.internalTracker.flatMap(_.enableTimeTracker), Some(true))
    assertEquals(edit.externalWiki, None)

  private def populated: EditRepository =
    EditRepository(
      name                          = Some(repo("original")),
      description                   = Some("original description"),
      website                       = Some("https://original.example"),
      isPrivate                     = Some(false),
      isTemplate                    = Some(false),
      defaultBranch                 = Some(orFail(BranchName.from("main"))),
      archived                      = Some(false),
      hasIssues                     = Some(false),
      hasWiki                       = Some(false),
      hasPullRequests               = Some(false),
      hasProjects                   = Some(true),
      hasReleases                   = Some(true),
      hasPackages                   = Some(true),
      hasActions                    = Some(false),
      allowMergeCommits             = Some(true),
      allowRebase                   = Some(true),
      allowRebaseExplicit           = Some(true),
      allowSquashMerge              = Some(true),
      allowFastForwardOnlyMerge     = Some(true),
      allowManualMerge              = Some(true),
      allowRebaseUpdate             = Some(true),
      autodetectManualMerge         = Some(true),
      defaultDeleteBranchAfterMerge = Some(true),
      defaultAllowMaintainerEdit    = Some(true),
      defaultMergeStyle             = Some(MergeStyle.Merge),
      defaultUpdateStyle            = Some(UpdateStyle.Rebase),
      ignoreWhitespaceConflicts     = Some(true),
      enablePrune                   = Some(true),
      mirrorInterval                = Some("8h0m0s"),
      wikiBranch                    = Some(orFail(BranchName.from("wiki"))),
      globallyEditableWiki          = Some(true),
      externalTracker               = Some(ExternalTrackerSettings(None, None, None, None)),
      externalWiki                  = Some(ExternalWikiSettings(None)),
      internalTracker               = Some(InternalTrackerSettings(None, None, None)),
    )

  private def repo(value: String): RepoName = orFail(RepoName.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
