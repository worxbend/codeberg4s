package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.hooks.CreateHook
import com.worxbend.codeberg4s.repositories.hooks.EditGitHook
import com.worxbend.codeberg4s.repositories.hooks.EditHook
import com.worxbend.codeberg4s.repositories.hooks.HookConfig
import com.worxbend.codeberg4s.repositories.hooks.HookSecret

/** Forgejo's `CreateHookOption`, `EditHookOption` and `EditGitHookOption` request models.
  *
  * One object for three models because they share their vocabulary — `config`, `events`, `branch_filter`, `active`,
  * `authorization_header` — and rule 4 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a wire spelling is
  * written exactly once. An object rather than a case class, for the reason
  * [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]] gives: a request model is a rendering, not a value
  * anyone holds.
  *
  * ==This is the only place a hook credential is written down==
  *
  * [[com.worxbend.codeberg4s.repositories.hooks.HookSecret.reveal]] is called here and nowhere else in the library.
  * What comes out is handed straight to `Json.render`, which escapes it into the request body — so material containing
  * a newline, a quote or a backslash survives intact and cannot break out of the JSON string. The rendered body is then
  * a [[com.worxbend.codeberg4s.core.RequestBody.Json]], which the pipeline never copies into a
  * [[com.worxbend.codeberg4s.CallContext]] or an error.
  *
  * A signing secret travels '''inside''' `config`, under the key `secret`, because that is where Forgejo reads it from;
  * an `Authorization` header travels as a top-level `authorization_header`. Neither can be smuggled in through a
  * [[com.worxbend.codeberg4s.repositories.hooks.HookConfig]], which refuses to hold either — the merge happens here, at
  * the last possible moment.
  *
  * ==Rendering is reproducible==
  *
  * Config entries are emitted in key order, because a `Map` has none of its own and a test that compares request bodies
  * needs one. Forgejo does not care.
  */
private[codeberg4s] object HookOptionDto:

  /** The wire key a hook's delivery format is sent under. */
  val TypeKey: String = "type"

  /** The wire key a hook's configuration map is sent under. */
  val ConfigKey: String = "config"

  /** The wire key a hook's subscriptions are sent under. */
  val EventsKey: String = "events"

  /** The wire key a hook's push branch glob is sent under. */
  val BranchFilterKey: String = "branch_filter"

  /** The wire key deciding whether Forgejo delivers to a hook. */
  val ActiveKey: String = "active"

  /** The wire key a Git hook's script is sent under. */
  val ContentKey: String = "content"

  /** Renders `command` as the JSON body to `POST`.
    *
    * `type` and `config` are the model's required properties and are always emitted; `active` is emitted too, even at
    * its default of `false`, so that what a created hook does is a property of the request rather than of the Forgejo
    * version answering it. `events`, `branch_filter` and `authorization_header` are emitted only when the caller set
    * them.
    *
    * The returned string contains any credential the command carried, in the clear, because that is what has to reach
    * the instance. It is consumed immediately by the request builder and is never logged, never stored and never put in
    * a failure.
    */
  def renderCreate(command: CreateHook): String =
    val fields = List(
      Some(TypeKey                                   -> JsonValue.Str(command.hookType.wireValue)),
      Some(ConfigKey                                 -> config(command.config.sortedEntries, command.secret)),
      Option.when(command.events.nonEmpty)(EventsKey -> events(command)),
      command.branchFilter.map(glob        => BranchFilterKey -> JsonValue.Str(glob)),
      command.authorizationHeader.map(head => HookConfig.AuthorizationHeaderKey -> JsonValue.Str(head.reveal)),
      Some(ActiveKey -> JsonValue.Bool(command.isActive)),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))

  /** Renders `command` as the JSON body to `PATCH`.
    *
    * '''Only what the caller set is emitted''', so [[com.worxbend.codeberg4s.repositories.hooks.EditHook.Empty]]
    * renders as `{}` and changes nothing. `events` is emitted whenever the caller stated one — including as an empty
    * array, which is how a hook is unsubscribed from everything and which is a different request from not mentioning
    * `events` at all.
    *
    * A command that carries only a secret still renders a `config` object, holding just the secret: Forgejo applies the
    * config keys it receives, so the hook's destination and encoding are untouched by one that does not mention them.
    *
    * The credential note on [[renderCreate]] applies here too.
    */
  def renderEdit(command: EditHook): String =
    val entries = command.config.fold(Vector.empty)(_.sortedEntries)

    val fields = List(
      Option.when(entries.nonEmpty || command.secret.isDefined)(ConfigKey -> config(entries, command.secret)),
      command.events.map(subscriptions     =>
        EventsKey -> JsonValue.Arr.from(subscriptions.map(event => JsonValue.Str(event.wireValue)))
      ),
      command.branchFilter.map(glob        => BranchFilterKey -> JsonValue.Str(glob)),
      command.authorizationHeader.map(head => HookConfig.AuthorizationHeaderKey -> JsonValue.Str(head.reveal)),
      command.isActive.map(active          => ActiveKey -> JsonValue.Bool(active)),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))

  /** Renders `command` as the JSON body to `PATCH` on a Git hook.
    *
    * `content` is the model's only property and is always emitted, including when it is empty — an empty script is how
    * a Git hook is cleared, and omitting the key would leave the existing script in place instead.
    */
  def renderEditGit(command: EditGitHook): String =
    Json.render(JsonValue.Obj(ContentKey -> JsonValue.Str(command.content)))

  /** The `config` object: the caller's entries in key order, with the signing secret merged in last. */
  private def config(entries: Vector[(String, String)], secret: Option[HookSecret]): JsonValue.Obj =
    val stated  = entries.map((key, value) => key -> JsonValue.Str(value))
    val signing = secret.map(value => HookConfig.SecretKey -> JsonValue.Str(value.reveal))

    JsonValue.Obj.from(stated ++ signing)

  private def events(command: CreateHook): JsonValue.Arr =
    JsonValue.Arr.from(command.events.map(event => JsonValue.Str(event.wireValue)))
