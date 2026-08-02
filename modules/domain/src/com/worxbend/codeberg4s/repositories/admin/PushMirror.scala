package com.worxbend.codeberg4s.repositories.admin

import java.time.Instant

/** A remote this repository pushes itself to — Forgejo's `PushMirror`.
  *
  * A push mirror is the opposite of a pull mirror: Forgejo holds the authoritative copy and pushes it outward on a
  * schedule, so the remote is a replica. [[com.worxbend.codeberg4s.repositories.Repository.isMirror]] describes the
  * other direction and has nothing to do with this.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Every push-mirror endpoint requires a
  * token, and the golden harvest was anonymous, so no fixture backs the field set.
  *
  * ==No credential comes back==
  *
  * `remote_password` is accepted by [[CreatePushMirror]] and is '''not''' a property of this model, because Forgejo
  * does not return it. That is not an omission this library made for safety — it is the wire contract, and modelling it
  * as an absent `Option` would suggest a caller could someday read it back. See [[RemoteCredential]].
  *
  * [[remoteAddress]] is returned, with any credential Forgejo embedded in it stripped by the instance itself. Treat it
  * as a display value.
  *
  * @param remoteName
  *   the handle Forgejo generated for this mirror, and the only way to address it — see [[MirrorName]]
  * @param remoteAddress
  *   where the mirror pushes to, as the instance renders it
  * @param repoName
  *   the name of the repository being mirrored, as the instance renders it
  * @param branchFilter
  *   the glob deciding which branches are pushed; absent or empty means all of them
  * @param interval
  *   how often the mirror runs, in Go's duration spelling such as `8h0m0s`. Left as a string because Forgejo accepts
  *   and returns that spelling verbatim and parsing it would invent a precision the API does not promise
  * @param syncsOnCommit
  *   whether a push to Forgejo triggers a mirror push immediately, rather than waiting for [[interval]]
  * @param lastError
  *   what went wrong on the most recent run, when something did. A non-empty value here is the only way this library
  *   learns that a mirror is failing: a broken mirror does not make any call fail
  * @param publicKey
  *   the SSH public key Forgejo authenticates with, when the mirror was created with [[CreatePushMirror.overSsh]].
  *   Install it on the remote as a deploy key
  * @param createdAt
  *   when the mirror was set up
  * @param lastUpdateAt
  *   when it last ran, absent when it has never run
  */
final case class PushMirror(
    remoteName: MirrorName,
    remoteAddress: Option[String],
    repoName: Option[String],
    branchFilter: Option[String],
    interval: Option[String],
    syncsOnCommit: Boolean,
    lastError: Option[String],
    publicKey: Option[String],
    createdAt: Option[Instant],
    lastUpdateAt: Option[Instant],
):

  /** Whether the most recent run reported a problem.
    *
    * The honest reading of [[lastError]]: Forgejo sends `""` for "no error", which
    * [[com.worxbend.codeberg4s.codec.JsonFields.text]] has already folded into absence by the time a value reaches
    * here. A caller monitoring mirrors polls [[PushMirror]] and watches this, because nothing else surfaces the
    * failure.
    */
  def isFailing: Boolean = lastError.isDefined

/** Everything `POST /repos/{owner}/{repo}/push_mirrors` may be told, as one value.
  *
  * Derived from `CreatePushMirrorOption` in `spec/swagger.v1.json`, which declares nothing required — which is a
  * fiction: a mirror with no remote address has nowhere to push. [[CreatePushMirror.to]] therefore demands the address
  * up front rather than letting a caller discover that at runtime.
  *
  * ==Two ways to authenticate, and they are mutually exclusive in practice==
  *
  * Either a username and a [[RemoteCredential]], which Forgejo embeds in the remote URL, or [[overSsh]], which makes
  * Forgejo generate a key pair and report the public half as [[PushMirror.publicKey]] for the caller to install on the
  * remote. Setting both is not rejected by this type — Forgejo is the authority on which combinations it accepts, and a
  * library that guesses will eventually be wrong about a combination that works.
  *
  * @param remoteAddress
  *   where to push. Required in practice; see the type note
  * @param remoteUsername
  *   the account on the remote, when authenticating with a credential
  * @param remoteCredential
  *   the password or token for the remote. Write-only — see [[RemoteCredential]]
  * @param interval
  *   how often to push, in Go's duration spelling such as `8h0m0s`. Absent leaves the instance's default
  * @param branchFilter
  *   a glob limiting which branches are pushed. Absent pushes all of them
  * @param syncOnCommit
  *   push immediately on every push to Forgejo, rather than only on [[interval]]
  * @param useSsh
  *   authenticate with a Forgejo-generated SSH key instead of a credential; see the type note
  */
final case class CreatePushMirror(
    remoteAddress: String,
    remoteUsername: Option[String],
    remoteCredential: Option[RemoteCredential],
    interval: Option[String],
    branchFilter: Option[String],
    syncOnCommit: Boolean,
    useSsh: Boolean,
):

  /** Authenticates as `username` with `credential`. The credential never leaves the request body. */
  def authenticatedAs(username: String, credential: RemoteCredential): CreatePushMirror =
    copy(remoteUsername = Some(username), remoteCredential = Some(credential))

  /** Pushes on the given interval, in Go's duration spelling such as `8h0m0s`. */
  def every(duration: String): CreatePushMirror = copy(interval = Some(duration))

  /** Limits the mirror to the branches matching `glob`. */
  def onlyBranches(glob: String): CreatePushMirror = copy(branchFilter = Some(glob))

  /** Pushes as soon as Forgejo receives a commit, as well as on the interval. */
  def syncingOnCommit: CreatePushMirror = copy(syncOnCommit = true)

  /** Asks Forgejo to authenticate with an SSH key it generates; read it back from [[PushMirror.publicKey]]. */
  def overSsh: CreatePushMirror = copy(useSsh = true)

object CreatePushMirror:

  /** Starts a command for the remote at `remoteAddress`.
    *
    * Cannot fail. The address is not validated here: Forgejo accepts `https://`, `ssh://` and `git@host:path` forms and
    * is the authority on which of them this instance permits, so a smart constructor here could only reject something
    * that would have worked.
    */
  def to(remoteAddress: String): CreatePushMirror =
    CreatePushMirror(
      remoteAddress    = remoteAddress,
      remoteUsername   = None,
      remoteCredential = None,
      interval         = None,
      branchFilter     = None,
      syncOnCommit     = false,
      useSsh           = false,
    )
