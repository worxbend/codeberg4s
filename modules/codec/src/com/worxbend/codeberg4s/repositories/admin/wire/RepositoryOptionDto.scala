package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.repositories.admin.{
  CreateRepository,
  EditRepository,
  ExternalTrackerSettings,
  ExternalWikiSettings,
  InternalTrackerSettings
}

/** Forgejo's `CreateRepoOption` and `EditRepoOption` request models — the bodies of `POST /user/repos` and
  * `PATCH /repos/{owner}/{repo}`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds. One object for two models because they overlap in
  * `name`, `description`, `private`, `default_branch` and `template`, and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is written exactly once.
  *
  * ==Absent means "leave it alone", and only on the edit==
  *
  * `PATCH /repos/{owner}/{repo}` is documented as "Only fields that are set will be changed", so [[renderEdit]] emits
  * nothing for a `None`. That is why every property of [[com.worxbend.codeberg4s.repositories.admin.EditRepository]] is
  * an `Option[Boolean]` rather than a `Boolean`: rendering `false` for an unset flag would turn every unmentioned
  * feature off in one call.
  *
  * [[renderCreate]] is the other way round. `POST /user/repos` has no "leave alone" semantics — the repository does not
  * exist yet — so its Booleans are plain and are always emitted, which makes the request state exactly what it is
  * asking for rather than depending on the instance's defaults.
  */
private[codeberg4s] object RepositoryOptionDto:

  /** The wire key of the repository's name, on both models. */
  val NameKey: String = "name"

  /** The wire key of the flag that writes an initial commit. Only `CreateRepoOption` has it. */
  val AutoInitKey: String = "auto_init"

  /** Renders `command` as the JSON body to `POST`.
    *
    * Every Boolean is emitted; every optional string and enum is emitted only when set. See the object note for why the
    * two rules differ from [[renderEdit]]'s.
    */
  def renderCreate(command: CreateRepository): String =
    Json.render(JsonValue.Obj.from(createFields(command)))

  /** Renders `command` as the JSON body to `PATCH`.
    *
    * Emits only what the command set. An [[com.worxbend.codeberg4s.repositories.admin.EditRepository.Empty]] renders as
    * `{}`, which Forgejo accepts and which changes nothing.
    */
  def renderEdit(command: EditRepository): String =
    Json.render(JsonValue.Obj.from(editFields(command)))

  private def createFields(command: CreateRepository): List[(String, JsonValue)] =
    List(
      Some(NameKey     -> JsonValue.Str(command.name.value)),
      Some("private"   -> JsonValue.Bool(command.isPrivate)),
      Some(AutoInitKey -> JsonValue.Bool(command.autoInit)),
      Some("template"  -> JsonValue.Bool(command.isTemplate)),
      command.description.map(text     => "description" -> JsonValue.Str(text)),
      command.defaultBranch.map(branch => "default_branch" -> JsonValue.Str(branch.value)),
      command.readme.map(template      => "readme" -> JsonValue.Str(template)),
      command.gitignores.map(templates => "gitignores" -> JsonValue.Str(templates)),
      command.license.map(template     => "license" -> JsonValue.Str(template)),
      command.issueLabels.map(labels   => "issue_labels" -> JsonValue.Str(labels)),
      command.objectFormat.map(format  => "object_format_name" -> JsonValue.Str(format.wireValue)),
      command.trustModel.map(model     => "trust_model" -> JsonValue.Str(model.wireValue)),
    ).flatten

  private def editFields(command: EditRepository): List[(String, JsonValue)] =
    List(
      command.name.map(value          => NameKey -> JsonValue.Str(value.value)),
      command.description.map(text    => "description" -> JsonValue.Str(text)),
      command.website.map(url         => "website" -> JsonValue.Str(url)),
      command.isPrivate.map(flag      => "private" -> JsonValue.Bool(flag)),
      command.isTemplate.map(flag     => "template" -> JsonValue.Bool(flag)),
      command.defaultBranch.map(value => "default_branch" -> JsonValue.Str(value.value)),
      command.archived.map(flag       => "archived" -> JsonValue.Bool(flag)),
    ).concat(unitFields(command))
      .concat(mergeFields(command))
      .concat(mirrorFields(command))
      .concat(trackerFields(command))
      .flatten

  private def unitFields(command: EditRepository): List[Option[(String, JsonValue)]] =
    List(
      command.hasIssues.map(flag       => "has_issues" -> JsonValue.Bool(flag)),
      command.hasWiki.map(flag         => "has_wiki" -> JsonValue.Bool(flag)),
      command.hasPullRequests.map(flag => "has_pull_requests" -> JsonValue.Bool(flag)),
      command.hasProjects.map(flag     => "has_projects" -> JsonValue.Bool(flag)),
      command.hasReleases.map(flag     => "has_releases" -> JsonValue.Bool(flag)),
      command.hasPackages.map(flag     => "has_packages" -> JsonValue.Bool(flag)),
      command.hasActions.map(flag      => "has_actions" -> JsonValue.Bool(flag)),
    )

  private def mergeFields(command: EditRepository): List[Option[(String, JsonValue)]] =
    List(
      command.allowMergeCommits.map(flag             => "allow_merge_commits" -> JsonValue.Bool(flag)),
      command.allowRebase.map(flag                   => "allow_rebase" -> JsonValue.Bool(flag)),
      command.allowRebaseExplicit.map(flag           => "allow_rebase_explicit" -> JsonValue.Bool(flag)),
      command.allowSquashMerge.map(flag              => "allow_squash_merge" -> JsonValue.Bool(flag)),
      command.allowFastForwardOnlyMerge.map(flag     => "allow_fast_forward_only_merge" -> JsonValue.Bool(flag)),
      command.allowManualMerge.map(flag              => "allow_manual_merge" -> JsonValue.Bool(flag)),
      command.allowRebaseUpdate.map(flag             => "allow_rebase_update" -> JsonValue.Bool(flag)),
      command.autodetectManualMerge.map(flag         => "autodetect_manual_merge" -> JsonValue.Bool(flag)),
      command.defaultDeleteBranchAfterMerge.map(flag => "default_delete_branch_after_merge" -> JsonValue.Bool(flag)),
      command.defaultAllowMaintainerEdit.map(flag    => "default_allow_maintainer_edit" -> JsonValue.Bool(flag)),
      command.defaultMergeStyle.map(style            => "default_merge_style" -> JsonValue.Str(style.wireValue)),
      command.defaultUpdateStyle.map(style           => "default_update_style" -> JsonValue.Str(style.wireValue)),
      command.ignoreWhitespaceConflicts.map(flag     => "ignore_whitespace_conflicts" -> JsonValue.Bool(flag)),
    )

  private def mirrorFields(command: EditRepository): List[Option[(String, JsonValue)]] =
    List(
      command.enablePrune.map(flag          => "enable_prune" -> JsonValue.Bool(flag)),
      command.mirrorInterval.map(duration   => "mirror_interval" -> JsonValue.Str(duration)),
      command.wikiBranch.map(branch         => "wiki_branch" -> JsonValue.Str(branch.value)),
      command.globallyEditableWiki.map(flag => "globally_editable_wiki" -> JsonValue.Bool(flag)),
    )

  private def trackerFields(command: EditRepository): List[Option[(String, JsonValue)]] =
    List(
      command.externalTracker.map(settings => "external_tracker" -> externalTracker(settings)),
      command.externalWiki.map(settings    => "external_wiki" -> externalWiki(settings)),
      command.internalTracker.map(settings => "internal_tracker" -> internalTracker(settings)),
    )

  private def externalTracker(settings: ExternalTrackerSettings): JsonValue =
    JsonValue.Obj.from(
      List(
        settings.url.map(value           => "external_tracker_url" -> JsonValue.Str(value)),
        settings.format.map(value        => "external_tracker_format" -> JsonValue.Str(value)),
        settings.style.map(value         => "external_tracker_style" -> JsonValue.Str(value)),
        settings.regexpPattern.map(value => "external_tracker_regexp_pattern" -> JsonValue.Str(value)),
      ).flatten
    )

  private def externalWiki(settings: ExternalWikiSettings): JsonValue =
    JsonValue.Obj.from(settings.url.map(value => "external_wiki_url" -> JsonValue.Str(value)).toList)

  private def internalTracker(settings: InternalTrackerSettings): JsonValue =
    JsonValue.Obj.from(
      List(
        settings.enableTimeTracker.map(flag                => "enable_time_tracker" -> JsonValue.Bool(flag)),
        settings.allowOnlyContributorsToTrackTime.map(flag =>
          "allow_only_contributors_to_track_time" -> JsonValue.Bool(flag)
        ),
        settings.enableIssueDependencies.map(flag          => "enable_issue_dependencies" -> JsonValue.Bool(flag)),
      ).flatten
    )
