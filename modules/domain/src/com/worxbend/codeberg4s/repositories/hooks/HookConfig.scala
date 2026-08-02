package com.worxbend.codeberg4s.repositories.hooks

import java.util.Locale

/** A webhook's configuration: an open map of strings that Forgejo stores and passes through.
  *
  * ==Why a map and not a record==
  *
  * `spec/swagger.v1.json` declares `Hook.config` and `EditHookOption.config` as `type: object` with
  * `additionalProperties: {type: string}`, and `CreateHookOptionConfig` the same way with a description saying only
  * that `content_type` and `url` are required. There is no property list to model. Which keys are meaningful depends on
  * the [[HookType]] — a Slack hook understands `channel`, `username` and `icon_url` that a Forgejo hook ignores — and
  * Forgejo adds keys per hook type across releases. A record would therefore have to be wrong for every type but one,
  * so this is a map, and [[url]] and [[contentType]] are conveniences over the two keys every type shares.
  *
  * ==The secret is not in here, and cannot be put in here==
  *
  * `secret` and `authorization_header` are credentials, and this type refuses to hold either:
  *
  *   - reading. Whatever an instance sends under a redacted key is dropped by [[HookConfig.from]] before a value
  *     exists, so a `secret` that a present or future Forgejo release echoes back cannot reach a caller, a log line, or
  *     the generated `toString` of anything holding a config;
  *   - writing. [[CreateHook]] and [[EditHook]] carry a [[HookSecret]] in their own fields, and the request renderers
  *     merge it into the config map at the last moment. A caller who puts `"secret" -> "hunter2"` in a config here has
  *     it silently dropped rather than sent, which is the safe direction to fail: the hook is created without signing
  *     instead of the credential travelling through a type that would print it.
  *
  * See [[HookConfig.RedactedKeys]] for the exact set and [[HookSecret]] for the discipline this mirrors.
  *
  * @param entries
  *   the configuration, keyed by the wire (snake_case) key. Never contains a key in [[HookConfig.RedactedKeys]]
  */
final case class HookConfig private (entries: Map[String, String]):

  /** The value stored under `key`, absent when the config has no such key.
    *
    * Matching is exact and case-sensitive, because Forgejo's keys are: `content_type` is a key and `Content_Type` is a
    * different one.
    */
  def valueOf(key: String): Option[String] =
    entries.get(key)

  /** Where deliveries are posted — the `url` entry, which every hook type requires. */
  def url: Option[String] =
    valueOf(HookConfig.UrlKey)

  /** How deliveries are encoded — the `content_type` entry, read through [[HookContentType.parse]]. */
  def contentType: Option[HookContentType] =
    valueOf(HookConfig.ContentTypeKey).map(HookContentType.parse)

  /** The same config with `key` set to `value`.
    *
    * A key in [[HookConfig.RedactedKeys]] is '''ignored''', for the reason the class note gives: credentials travel in
    * a [[HookSecret]] and nowhere else. Any other key replaces whatever was stored under it.
    */
  def withEntry(key: String, value: String): HookConfig =
    if HookConfig.isRedacted(key) then this else HookConfig(entries.updated(key, value))

  /** The keys in wire order for rendering — sorted, so a rendered request body is reproducible.
    *
    * A `Map` has no order of its own, and a test that compares request bodies needs one. Forgejo does not care which
    * order the keys arrive in.
    */
  def sortedEntries: Vector[(String, String)] =
    entries.toVector.sortBy((key, _) => key)

object HookConfig:

  /** The wire key naming a hook's destination. */
  val UrlKey: String = "url"

  /** The wire key naming a hook's payload encoding. */
  val ContentTypeKey: String = "content_type"

  /** The wire key a signing secret would occupy. Never present in a [[HookConfig]]; see the class note. */
  val SecretKey: String = "secret"

  /** The wire key an `Authorization` header would occupy. Never present in a [[HookConfig]]; see the class note. */
  val AuthorizationHeaderKey: String = "authorization_header"

  /** The keys this type refuses to hold, in either direction, because their values are credentials.
    *
    * Compared case-insensitively, since a key is dropped on suspicion here and an instance spelling `Secret` must not
    * slip past a case-sensitive comparison. That is stricter than [[HookConfig.valueOf]], which is exact — the
    * asymmetry is deliberate: reading a key is a lookup, dropping one is a safety net.
    */
  val RedactedKeys: Set[String] = Set(SecretKey, AuthorizationHeaderKey)

  /** The configuration Forgejo requires of every hook type: where to post, and in what encoding. */
  def of(url: String, contentType: HookContentType): HookConfig =
    HookConfig(Map(UrlKey -> url, ContentTypeKey -> contentType.wireValue))

  /** Builds a configuration from arbitrary entries, dropping every credential-bearing key.
    *
    * This is the only constructor, so no `HookConfig` anywhere — decoded from a response or written by a caller — can
    * carry a value in [[RedactedKeys]].
    */
  def from(entries: Map[String, String]): HookConfig =
    HookConfig(entries.filterNot((key, _) => isRedacted(key)))

  /** An empty configuration, for an [[EditHook]] that changes something other than the config. */
  val Empty: HookConfig = HookConfig(Map.empty)

  private def isRedacted(key: String): Boolean =
    RedactedKeys.contains(key.trim.toLowerCase(Locale.ROOT))
