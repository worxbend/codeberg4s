package com.worxbend.codeberg4s.repositories.hooks

/** What `PATCH /repos/{owner}/{repo}/hooks/{id}` is told.
  *
  * {{{
  * EditHook.Empty.subscribingTo(HookEvent.Push, HookEvent.Release).deactivated
  * }}}
  *
  * ==Every field is optional, and absent means "leave it alone"==
  *
  * `EditHookOption` declares no `required` list, and Forgejo applies only the keys it receives. That is modelled
  * literally: a field left `None` is not rendered at all, so [[EditHook.Empty]] is a request that changes nothing. The
  * distinction matters most for [[events]] — `Some(Vector.empty)` asks Forgejo to unsubscribe the hook from everything,
  * while `None` leaves its subscriptions untouched, and the two must not be spelled the same way.
  *
  * ==This is an assignment of whatever it names==
  *
  * Every key this command sends states a value to store; a key it does not send leaves what is stored alone. Setting a
  * value twice is setting it once, which is what makes the call idempotent and therefore safe to retry — see
  * [[RepositoryHookApi.edit]], which states the argument and its one cost.
  *
  * @param config
  *   the configuration entries to store. Credentials cannot travel here; see [[HookConfig]]
  * @param events
  *   the subscriptions to store, replacing what is there. `Some(Vector.empty)` unsubscribes; `None` changes nothing
  * @param branchFilter
  *   the push branch glob to store
  * @param secret
  *   a new signing secret. Forgejo has no way to '''remove''' one through this API, so there is no way to spell that
  *   here either
  * @param authorizationHeader
  *   a new `Authorization` header value, on the same terms as [[secret]]
  * @param isActive
  *   whether Forgejo should deliver to the hook
  */
final case class EditHook(
    config: Option[HookConfig],
    events: Option[Vector[HookEvent]],
    branchFilter: Option[String],
    secret: Option[HookSecret],
    authorizationHeader: Option[HookSecret],
    isActive: Option[Boolean],
):

  /** Stores the entries of `value` as the hook's configuration. */
  def configuredAs(value: HookConfig): EditHook =
    copy(config = Some(value))

  /** Replaces the hook's subscriptions with `events`; passing none unsubscribes it from everything. */
  def subscribingTo(events: HookEvent*): EditHook =
    copy(events = Some(events.toVector))

  /** Replaces the push branch glob. */
  def filteringBranches(glob: String): EditHook =
    copy(branchFilter = Some(glob))

  /** Sets a new signing secret, which never comes back out; see [[HookSecret]]. */
  def signedWith(value: HookSecret): EditHook =
    copy(secret = Some(value))

  /** Sets a new `Authorization` header value, which never comes back out either. */
  def authorization(value: HookSecret): EditHook =
    copy(authorizationHeader = Some(value))

  /** Asks Forgejo to deliver to the hook. */
  def activated: EditHook =
    copy(isActive = Some(true))

  /** Asks Forgejo to stop delivering to the hook without deleting it. */
  def deactivated: EditHook =
    copy(isActive = Some(false))

object EditHook:

  /** The command that changes nothing, and the starting point for every other one. */
  val Empty: EditHook =
    EditHook(
      config              = None,
      events              = None,
      branchFilter        = None,
      secret              = None,
      authorizationHeader = None,
      isActive            = None,
    )

/** What `PATCH /repos/{owner}/{repo}/hooks/git/{id}` is told.
  *
  * `EditGitHookOption` has exactly one property, `content`, so this type has exactly one field. Sending an empty
  * content is how a Git hook is emptied — the routes offer a `DELETE` that does the same thing, and
  * [[RepositoryHookApi.deleteGitHook]] is that one.
  *
  * '''The content is a shell script the instance will execute.''' It is not validated here beyond existing: this
  * library cannot tell a correct hook from a destructive one, Forgejo restricts the endpoint to administrators
  * precisely because of that, and a script this library refused would be a hook the caller could not install.
  *
  * @param content
  *   the script to store, verbatim. Plain text, never base64 — see [[GitHook.content]]
  */
final case class EditGitHook(content: String)

object EditGitHook:

  /** The command that stores `content` as the hook's script. Total; see the class note. */
  def of(content: String): EditGitHook =
    EditGitHook(content)
