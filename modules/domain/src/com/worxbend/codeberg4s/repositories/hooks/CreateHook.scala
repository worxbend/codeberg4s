package com.worxbend.codeberg4s.repositories.hooks

/** What `POST /repos/{owner}/{repo}/hooks` is told.
  *
  * {{{
  * CreateHook
  *   .to(HookType.Forgejo, "https://ci.example/forgejo", HookContentType.Json)
  *   .subscribingTo(HookEvent.Push, HookEvent.PullRequest)
  *   .signedWith(secret)
  *   .activated
  * }}}
  *
  * ==What is required, and by whom==
  *
  * `CreateHookOption` is one of the 38 request models in `spec/swagger.v1.json` that do declare a `required` list, and
  * it names `type` and `config`. `CreateHookOptionConfig`'s own description adds that `content_type` and `url` are
  * required inside the config. [[CreateHook.to]] takes exactly those three values and nothing else, so a command that
  * exists is a command Forgejo will accept the shape of.
  *
  * ==The credentials are separate fields, on purpose==
  *
  * [[secret]] and [[authorizationHeader]] are not entries of [[config]], because [[HookConfig]] refuses to hold a
  * credential at all — see its note. They are merged into the rendered body by the request renderer and exist nowhere
  * else. That is what makes "a webhook secret cannot be printed" a property of the types rather than a rule reviewers
  * have to remember.
  *
  * @param hookType
  *   the delivery format
  * @param config
  *   the destination and encoding, plus whatever else the chosen type understands
  * @param events
  *   what to subscribe to, in the order they will be sent. Empty leaves the instance's default, which is `push`
  * @param branchFilter
  *   a glob limiting which branches push events fire for, absent to fire for all of them
  * @param secret
  *   the signing secret, absent to create an unsigned hook
  * @param authorizationHeader
  *   the `Authorization` header value to replay on every delivery, absent to send none
  * @param isActive
  *   whether Forgejo should start delivering immediately. `false` is the API's own default and this type's
  */
final case class CreateHook(
    hookType: HookType,
    config: HookConfig,
    events: Vector[HookEvent],
    branchFilter: Option[String],
    secret: Option[HookSecret],
    authorizationHeader: Option[HookSecret],
    isActive: Boolean,
):

  /** Subscribes the hook to `events`, replacing whatever it was subscribed to.
    *
    * Repeated events are kept as written rather than de-duplicated: deciding that two entries a caller wrote are one is
    * not a decision a client library should make, and Forgejo collapses them itself.
    */
  def subscribingTo(events: HookEvent*): CreateHook =
    copy(events = events.toVector)

  /** Adds one entry to the hook's config; a credential-bearing key is ignored, see [[HookConfig.withEntry]]. */
  def configured(key: String, value: String): CreateHook =
    copy(config = config.withEntry(key, value))

  /** Limits push deliveries to the branches matching `glob`. */
  def filteringBranches(glob: String): CreateHook =
    copy(branchFilter = Some(glob))

  /** Signs every delivery with `value`, which never comes back out; see [[HookSecret]]. */
  def signedWith(value: HookSecret): CreateHook =
    copy(secret = Some(value))

  /** Replays `value` as the `Authorization` header of every delivery; it never comes back out either. */
  def authorization(value: HookSecret): CreateHook =
    copy(authorizationHeader = Some(value))

  /** Asks Forgejo to start delivering as soon as the hook exists. */
  def activated: CreateHook =
    copy(isActive = true)

object CreateHook:

  /** Starts a command from the three values Forgejo requires.
    *
    * '''Total rather than validated.''' None of the three can forge a request: the type and the content type render as
    * their own wire spellings, and the URL travels inside a JSON body where it is escaped. A URL this library refused
    * would be a hook the caller could not create, and Forgejo validates the destination itself — a malformed one comes
    * back as a `400` or a `422`, which is where that judgement belongs.
    *
    * @param hookType
    *   the delivery format
    * @param url
    *   where deliveries are posted
    * @param contentType
    *   how deliveries are encoded
    */
  def to(hookType: HookType, url: String, contentType: HookContentType): CreateHook =
    CreateHook(
      hookType            = hookType,
      config              = HookConfig.of(url, contentType),
      events              = Vector.empty,
      branchFilter        = None,
      secret              = None,
      authorizationHeader = None,
      isActive            = false,
    )
