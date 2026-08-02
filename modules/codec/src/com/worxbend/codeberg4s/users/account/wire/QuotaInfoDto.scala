package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.account.QuotaGroup
import com.worxbend.codeberg4s.users.account.QuotaInfo
import com.worxbend.codeberg4s.users.account.QuotaRule
import com.worxbend.codeberg4s.users.account.QuotaSubject
import com.worxbend.codeberg4s.users.account.QuotaUsedSize

/** Forgejo's `QuotaInfo` model, and the four definitions nested inside it.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `/user/quota` requires a token and the golden harvest
  * was anonymous, so no fixture exists for this shape.
  *
  * ==Four wire levels become one field==
  *
  * The payload nests `used → size → {repos, git, assets}` and then `assets → {artifacts, attachments, packages}`, with
  * `attachments` and `packages` each being another object. Six of those seven objects exist only to hold one or two
  * integers, and none of them carries information a caller could act on — an absent `used.size.assets` and an absent
  * `used.size.assets.artifacts` mean the same thing. So the whole tree is read here and projected into the flat
  * [[com.worxbend.codeberg4s.users.account.QuotaUsedSize]], which is the one place in this module a DTO does not mirror
  * the wire shape one for one. That decision is stated on the domain model as well, so a reader who starts from either
  * end finds it.
  *
  * '''`LFS` is upper-case on the wire.''' `QuotaUsedSizeGit` declares its single property as `LFS`, not `lfs` — the one
  * field in this group whose spelling a reader would otherwise guess wrong. Rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] is why it is written down exactly once, here.
  */
final case class QuotaInfoDto(
    groups: Vector[QuotaGroupDto],
    used: QuotaUsedSize,
):

  /** Converts to the domain.
    *
    * '''Cannot fail''', and there is no `toDomainAt` for the reason [[UserSettingsDto]] gives: nothing in this model is
    * required, because nothing in it addresses anything. A group whose rules the instance sent in a shape this library
    * cannot read loses those rules and keeps the group, which is the leniency `docs/HAZARDS.md` §1 demands.
    */
  def toDomain: Either[DecodeFailure, QuotaInfo] =
    Right(QuotaInfo(groups = groups.map(_.toDomain), used = used))

object QuotaInfoDto:

  /** The wire key of the size-based Git LFS total, spelled upper-case by Forgejo; see the class note. */
  val LfsKey: String = "LFS"

  /** Reads a `QuotaInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[QuotaInfoDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, flattening the `used` tree; see the class note. */
  def fromFields(fields: JsonFields): QuotaInfoDto =
    QuotaInfoDto(
      groups = fields.nestedAll("groups").map(QuotaGroupDto.fromFields),
      used   = usedFrom(fields.nested("used")),
    )

  /** The flat size breakdown, read out of the `used` object's four levels.
    *
    * Every step tolerates an absent or wrongly typed object by answering the empty view, so a payload that stops
    * nesting halfway yields the headings it did carry and `None` for the rest.
    */
  private def usedFrom(used: Option[JsonFields]): QuotaUsedSize =
    val size        = used.flatMap(_.nested("size"))
    val repos       = size.flatMap(_.nested("repos"))
    val git         = size.flatMap(_.nested("git"))
    val assets      = size.flatMap(_.nested("assets"))
    val attachments = assets.flatMap(_.nested("attachments"))
    val packages    = assets.flatMap(_.nested("packages"))

    QuotaUsedSize(
      publicRepositories  = repos.flatMap(_.number("public")),
      privateRepositories = repos.flatMap(_.number("private")),
      gitLfs              = git.flatMap(_.number(LfsKey)),
      artifacts           = assets.flatMap(_.number("artifacts")),
      issueAttachments    = attachments.flatMap(_.number("issues")),
      releaseAttachments  = attachments.flatMap(_.number("releases")),
      packages            = packages.flatMap(_.number("all")),
    )

/** Forgejo's `QuotaGroup` model — a named collection of rules.
  *
  * '''Derived from the spec, not from a capture'''; see [[QuotaInfoDto]].
  */
final case class QuotaGroupDto(
    name: Option[String],
    rules: Vector[QuotaRuleDto],
):

  /** Converts to the domain. Cannot fail; see [[QuotaInfoDto.toDomain]]. */
  def toDomain: QuotaGroup =
    QuotaGroup(name = name, rules = rules.map(_.toDomain))

object QuotaGroupDto:

  /** Reads a `QuotaGroup` object. */
  given JsonDecoder[QuotaGroupDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaGroupDto =
    QuotaGroupDto(
      name  = fields.text("name"),
      rules = fields.nestedAll("rules").map(QuotaRuleDto.fromFields),
    )

/** Forgejo's `QuotaRuleInfo` model — one limit and what it counts.
  *
  * '''Derived from the spec, not from a capture'''; see [[QuotaInfoDto]].
  */
final case class QuotaRuleDto(
    name: Option[String],
    limit: Option[Long],
    subjects: Vector[String],
):

  /** Converts to the domain. Cannot fail.
    *
    * A subject that [[com.worxbend.codeberg4s.users.account.QuotaSubject.from]] refuses — blank, or carrying a control
    * character — is dropped rather than failing the rule, because losing one entry of a diagnostic listing is a much
    * smaller cost than losing the caller's whole quota report. The limit is carried verbatim, negatives included; see
    * [[com.worxbend.codeberg4s.users.account.QuotaRule.isUnlimited]].
    */
  def toDomain: QuotaRule =
    QuotaRule(
      name     = name,
      limit    = limit,
      subjects = subjects.flatMap(subject => QuotaSubject.from(subject).toOption),
    )

object QuotaRuleDto:

  /** Reads a `QuotaRuleInfo` object. */
  given JsonDecoder[QuotaRuleDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaRuleDto =
    QuotaRuleDto(
      name     = fields.text("name"),
      limit    = fields.number("limit"),
      subjects = fields.texts("subjects"),
    )
