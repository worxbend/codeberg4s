package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.repositories.admin.CreatePushMirror
import com.worxbend.codeberg4s.repositories.admin.MigrateRepository
import com.worxbend.codeberg4s.repositories.admin.TransferRepository

/** Forgejo's `MigrateRepoOptions` request model — the body of `POST /repos/migrate`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives: a request model is a rendering, not a value anyone holds.
  *
  * ==This is one of three places a secret is written down==
  *
  * [[com.worxbend.codeberg4s.repositories.admin.RemoteCredential.reveal]] is called here and in
  * [[PushMirrorOptionDto]], and nowhere else. What comes out is handed straight to `ujson.write`, which escapes it into
  * the request body — so a credential containing a quote, a backslash or a newline survives intact and cannot break out
  * of the JSON string. The rendered body becomes a [[com.worxbend.codeberg4s.core.RequestBody.Json]], which the
  * pipeline never copies into a [[com.worxbend.codeberg4s.CallContext]] or an error.
  *
  * `auth_password` and `auth_token` are both emitted when both are set. Forgejo decides which one it wants for the
  * `service` it was given, and a library that dropped one on the caller's behalf would be guessing.
  *
  * ==What is not emitted==
  *
  * `uid`, which the spec marks "deprecated (only for backwards compatibility)", is not sent. `repo_owner` supersedes it
  * and is what [[com.worxbend.codeberg4s.repositories.admin.MigrateRepository.ownedBy]] sets.
  */
private[codeberg4s] object MigrateRepoOptionsDto:

  /** The wire key of the remote to fetch from. */
  val CloneAddressKey: String = "clone_addr"

  /** The wire key the remote's password travels under. */
  val AuthPasswordKey: String = "auth_password"

  /** The wire key the remote's token travels under. */
  val AuthTokenKey: String = "auth_token"

  /** Renders `command` as the JSON body to `POST`.
    *
    * The returned string contains any credential in the clear, because that is what has to reach the instance. It is
    * consumed immediately by the request builder and is never logged, never stored and never put in a failure.
    *
    * The two required properties and the six content flags are always emitted; everything else only when set. The flags
    * are unconditional so that a migration states what it wants copied rather than depending on the instance's
    * defaults.
    */
  def render(command: MigrateRepository): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: MigrateRepository): List[(String, ujson.Value)] =
    List(
      Some(CloneAddressKey -> ujson.Str(command.cloneAddress)),
      Some("repo_name"     -> ujson.Str(command.repoName.value)),
      Some("private"       -> ujson.Bool(command.isPrivate)),
      Some("mirror"        -> ujson.Bool(command.isMirror)),
      Some("lfs"           -> ujson.Bool(command.lfs)),
      Some("issues"        -> ujson.Bool(command.includesIssues)),
      Some("labels"        -> ujson.Bool(command.includesLabels)),
      Some("milestones"    -> ujson.Bool(command.includesMilestones)),
      Some("pull_requests" -> ujson.Bool(command.includesPullRequests)),
      Some("releases"      -> ujson.Bool(command.includesReleases)),
      Some("wiki"          -> ujson.Bool(command.includesWiki)),
      command.repoOwner.map(owner         => "repo_owner" -> ujson.Str(owner.value)),
      command.description.map(text        => "description" -> ujson.Str(text)),
      command.service.map(service         => "service" -> ujson.Str(service.wireValue)),
      command.username.map(user           => "auth_username" -> ujson.Str(user)),
      command.credential.map(secret       => AuthPasswordKey -> ujson.Str(secret.reveal)),
      command.token.map(secret            => AuthTokenKey -> ujson.Str(secret.reveal)),
      command.mirrorInterval.map(duration => "mirror_interval" -> ujson.Str(duration)),
      command.lfsEndpoint.map(endpoint    => "lfs_endpoint" -> ujson.Str(endpoint)),
    ).flatten

/** Forgejo's `TransferRepoOption` request model — the body of `POST /repos/{owner}/{repo}/transfer`.
  *
  * `new_owner` is the model's single required property and is always emitted. `team_ids` is emitted only when the
  * command names a team: an empty array and an absent key are the same request to Forgejo, and sending `[]` would
  * suggest the caller meant something by it.
  */
private[codeberg4s] object TransferRepoOptionDto:

  /** The wire key of the account receiving the repository. */
  val NewOwnerKey: String = "new_owner"

  /** The wire key of the teams granted access afterwards. */
  val TeamIdsKey: String = "team_ids"

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: TransferRepository): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: TransferRepository): List[(String, ujson.Value)] =
    List(
      Some(NewOwnerKey -> ujson.Str(command.newOwner.value)),
      Option.when(command.teamIds.nonEmpty)(
        TeamIdsKey     -> ujson.Arr.from(command.teamIds.map(team => ujson.Num(team.value.toDouble)))
      ),
    ).flatten

/** Forgejo's `CreatePushMirrorOption` request model — the body of `POST /repos/{owner}/{repo}/push_mirrors`.
  *
  * The second of the two places a [[com.worxbend.codeberg4s.repositories.admin.RemoteCredential]] is written down; see
  * [[MigrateRepoOptionsDto]] for what that means and why it is safe.
  *
  * `remote_address` is emitted unconditionally even though the spec declares nothing required, because a mirror without
  * one has nowhere to push — see [[com.worxbend.codeberg4s.repositories.admin.CreatePushMirror.to]]. The two Booleans
  * are emitted unconditionally so the request states what it wants rather than depending on the instance's defaults.
  */
private[codeberg4s] object PushMirrorOptionDto:

  /** The wire key of the remote being pushed to. */
  val RemoteAddressKey: String = "remote_address"

  /** The wire key the remote's password travels under. */
  val RemotePasswordKey: String = "remote_password"

  /** Renders `command` as the JSON body to `POST`.
    *
    * The returned string contains the credential in the clear when the command carries one. It is consumed immediately
    * by the request builder and is never logged, never stored and never put in a failure.
    */
  def render(command: CreatePushMirror): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: CreatePushMirror): List[(String, ujson.Value)] =
    List(
      Some(RemoteAddressKey -> ujson.Str(command.remoteAddress)),
      Some("sync_on_commit" -> ujson.Bool(command.syncOnCommit)),
      Some("use_ssh"        -> ujson.Bool(command.useSsh)),
      command.remoteUsername.map(user     => "remote_username" -> ujson.Str(user)),
      command.remoteCredential.map(secret => RemotePasswordKey -> ujson.Str(secret.reveal)),
      command.interval.map(duration       => "interval" -> ujson.Str(duration)),
      command.branchFilter.map(glob       => "branch_filter" -> ujson.Str(glob)),
    ).flatten
