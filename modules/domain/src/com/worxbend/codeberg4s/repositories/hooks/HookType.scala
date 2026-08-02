package com.worxbend.codeberg4s.repositories.hooks

import java.util.Locale

/** Which delivery format a webhook speaks — the `type` of the `Hook` model and of `CreateHookOption`.
  *
  * '''The spec enumerates this one, unlike [[HookEvent]].''' `CreateHookOption.type` in `spec/swagger.v1.json` declares
  * `enum: [forgejo, dingtalk, discord, gitea, gogs, msteams, slack, telegram, feishu, wechatwork, packagist]`, which is
  * where the named cases below come from verbatim. The `type` '''field''' of the `Hook` response model is declared as a
  * bare `type: string`, so a hook created before a type was removed — or by a newer instance than this library knows —
  * can read back as something outside that list. [[HookType.Other]] is that case, for the reason [[HookEvent.Other]]
  * gives.
  *
  * The type decides the shape of the payload Forgejo posts, not where it posts it: the destination is the `url` entry
  * of the hook's [[HookConfig]].
  */
enum HookType:

  /** Forgejo's own payload format — `forgejo`. */
  case Forgejo

  /** Gitea's payload format, which Forgejo still emits for compatibility — `gitea`. */
  case Gitea

  /** The Gogs payload format — `gogs`. */
  case Gogs

  /** Slack incoming webhooks — `slack`. */
  case Slack

  /** Discord webhooks — `discord`. */
  case Discord

  /** DingTalk robots — `dingtalk`. */
  case Dingtalk

  /** Microsoft Teams connectors — `msteams`. */
  case MsTeams

  /** Telegram bots — `telegram`. */
  case Telegram

  /** Feishu / Lark bots — `feishu`. */
  case Feishu

  /** WeCom (WeChat Work) robots — `wechatwork`. */
  case WeChatWork

  /** Packagist package updates — `packagist`. */
  case Packagist

  /** A type this library does not recognise, kept exactly as the instance spelled it. See the enum note. */
  case Other(name: String)

object HookType:

  /** Reads Forgejo's lowercase wire spelling.
    *
    * '''Total''', for the reason [[HookEvent.parse]] gives: a type outside the enumerated set becomes
    * [[HookType.Other]] rather than costing the caller the whole hook. Trims and is case-insensitive.
    */
  def parse(value: String): HookType =
    value.trim.toLowerCase(Locale.ROOT) match
      case "forgejo"    => Forgejo
      case "gitea"      => Gitea
      case "gogs"       => Gogs
      case "slack"      => Slack
      case "discord"    => Discord
      case "dingtalk"   => Dingtalk
      case "msteams"    => MsTeams
      case "telegram"   => Telegram
      case "feishu"     => Feishu
      case "wechatwork" => WeChatWork
      case "packagist"  => Packagist
      case unrecognised => Other(unrecognised)

  extension (hookType: HookType)

    /** The wire spelling, which is what a create request sends and what a response carries. */
    def wireValue: String =
      hookType match
        case Forgejo     => "forgejo"
        case Gitea       => "gitea"
        case Gogs        => "gogs"
        case Slack       => "slack"
        case Discord     => "discord"
        case Dingtalk    => "dingtalk"
        case MsTeams     => "msteams"
        case Telegram    => "telegram"
        case Feishu      => "feishu"
        case WeChatWork  => "wechatwork"
        case Packagist   => "packagist"
        case Other(name) => name

/** How a webhook's payload is encoded — the `content_type` entry of a hook's config.
  *
  * '''Not enumerated by the spec.''' `CreateHookOptionConfig` is declared as an open `additionalProperties: {type:
  * string}` map whose description says only that `content_type` and `url` are required; it names no values. The two
  * cases below are Forgejo's own — `json` posts the payload as an `application/json` document, `form` posts it as an
  * `application/x-www-form-urlencoded` `payload` field — and [[HookContentType.Other]] carries anything else through
  * untouched, exactly as [[HookEvent.Other]] does.
  */
enum HookContentType:

  /** `application/json` — the payload is the request body. */
  case Json

  /** `application/x-www-form-urlencoded` — the payload is a form field named `payload`. */
  case Form

  /** A content type this library does not recognise, kept verbatim. */
  case Other(name: String)

object HookContentType:

  /** Reads the `content_type` config entry. Total, trimming and case-insensitive; see [[HookEvent.parse]]. */
  def parse(value: String): HookContentType =
    value.trim.toLowerCase(Locale.ROOT) match
      case "json"       => Json
      case "form"       => Form
      case unrecognised => Other(unrecognised)

  extension (contentType: HookContentType)

    /** The wire spelling, as it appears in the config map. */
    def wireValue: String =
      contentType match
        case Json        => "json"
        case Form        => "form"
        case Other(name) => name
