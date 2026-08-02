package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.QuotaAssetSizes
import com.worxbend.codeberg4s.organizations.QuotaAttachmentSizes
import com.worxbend.codeberg4s.organizations.QuotaGitSizes
import com.worxbend.codeberg4s.organizations.QuotaGroup
import com.worxbend.codeberg4s.organizations.QuotaInfo
import com.worxbend.codeberg4s.organizations.QuotaRepositorySizes
import com.worxbend.codeberg4s.organizations.QuotaRule
import com.worxbend.codeberg4s.organizations.QuotaSizes
import com.worxbend.codeberg4s.organizations.QuotaUsage

/** Forgejo's `QuotaInfo` model and the seven definitions nested inside it.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''' — see
  * [[com.worxbend.codeberg4s.organizations.QuotaInfo]] for why no quota fixture exists anywhere in this repository.
  *
  * ==One DTO, not eight==
  *
  * `QuotaInfo`, `QuotaGroup`, `QuotaRuleInfo`, `QuotaUsed`, `QuotaUsedSize`, `QuotaUsedSizeRepos`,
  * `QuotaUsedSizeAssets`, `QuotaUsedSizeAssetsAttachments`, `QuotaUsedSizeAssetsPackages` and `QuotaUsedSizeGit` are
  * ten spec definitions describing one payload, and six of them exist only to hold two numbers each. Rule 6 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a nested model nests its DTO — the point of which is that an
  * embedded model's '''failure path''' is reported once, by that model. None of the size objects can fail: every leaf
  * is a number that is either there or not, and there is no required field anywhere below `used`. Six DTOs, six
  * companions and six readers would therefore buy nothing but six more places for a wire spelling to be mistyped.
  *
  * So the size tree is read directly out of [[com.worxbend.codeberg4s.codec.JsonFields]] here, and the '''two'''
  * definitions that carry a list of things worth naming — `QuotaGroup` and `QuotaRuleInfo` — do get DTOs of their own,
  * because a caller reads them and because a future required field would land there.
  *
  * ==Wire spellings worth pointing at==
  *
  *   - `git.LFS` is upper-case, alone in this payload. Go's field is `LFS` and the spec's generator left it alone; a
  *     reader that lower-cased it would silently report no LFS usage at all.
  *   - `assets.packages.all` is an object with one property, not a number. Reading it as a number would answer `None`
  *     for every instance.
  *   - `assets.attachments.issues` covers issue '''and''' comment attachments, which the spec's own description says.
  */
final case class QuotaInfoDto(groups: Vector[QuotaGroupDto], used: Option[QuotaUsage]):

  /** Converts to the domain. Total: nothing in this payload is required, so there is no failure to report.
    *
    * An absent `used` becomes [[com.worxbend.codeberg4s.organizations.QuotaUsage.Empty]] rather than `None`, so a
    * caller reaching for a number walks a tree of values rather than a chain of `Option`s to arrive at the same `None`
    * either way.
    */
  def toDomain: Either[DecodeFailure, QuotaInfo] =
    Right(QuotaInfo(groups = groups.map(_.toDomain), used = used.getOrElse(QuotaUsage.Empty)))

object QuotaInfoDto:

  /** The key holding the size tree inside `used`. */
  val SizeKey: String = "size"

  /** The upper-case key holding Git LFS usage; see the class note. */
  val LfsKey: String = "LFS"

  /** Reads a `QuotaInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[QuotaInfoDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): QuotaInfoDto =
    QuotaInfoDto(
      groups = fields.nestedAll("groups").map(QuotaGroupDto.fromFields),
      used   = fields.nested("used").map(usage),
    )

  /** The `used` object as a domain value; every branch of it defaults to its `Empty`. */
  private def usage(fields: JsonFields): QuotaUsage =
    QuotaUsage(size = fields.nested(SizeKey).fold(QuotaSizes.Empty)(sizes))

  private def sizes(fields: JsonFields): QuotaSizes =
    QuotaSizes(
      repositories = fields.nested("repos").fold(QuotaRepositorySizes.Empty)(repositories),
      assets       = fields.nested("assets").fold(QuotaAssetSizes.Empty)(assets),
      git          = fields.nested("git").fold(QuotaGitSizes.Empty)(git),
    )

  private def repositories(fields: JsonFields): QuotaRepositorySizes =
    QuotaRepositorySizes(publicBytes = fields.number("public"), privateBytes = fields.number("private"))

  private def assets(fields: JsonFields): QuotaAssetSizes =
    QuotaAssetSizes(
      artifactBytes = fields.number("artifacts"),
      attachments   = fields.nested("attachments").fold(QuotaAttachmentSizes.Empty)(attachments),
      packageBytes  = fields.nested("packages").flatMap(_.number("all")),
    )

  private def attachments(fields: JsonFields): QuotaAttachmentSizes =
    QuotaAttachmentSizes(issueBytes = fields.number("issues"), releaseBytes = fields.number("releases"))

  private def git(fields: JsonFields): QuotaGitSizes =
    QuotaGitSizes(lfsBytes = fields.number(LfsKey))

/** Forgejo's `QuotaGroup` model — a named bundle of rules.
  *
  * @param name
  *   the `name` key, which the spec marks visible to administrators only
  * @param rules
  *   the `rules` array; empty when absent, `null`, or not an array
  */
final case class QuotaGroupDto(name: Option[String], rules: Vector[QuotaRuleDto]):

  /** Converts to the domain. Total: the group declares no required property. */
  def toDomain: QuotaGroup =
    QuotaGroup(name = name, rules = rules.map(_.toDomain))

object QuotaGroupDto:

  /** Reads a `QuotaGroup` object. */
  given upickle.default.Reader[QuotaGroupDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaGroupDto =
    QuotaGroupDto(name = fields.text("name"), rules = fields.nestedAll("rules").map(QuotaRuleDto.fromFields))

/** Forgejo's `QuotaRuleInfo` model — one limit and what it counts.
  *
  * @param name
  *   the `name` key, administrators only
  * @param limit
  *   the `limit` key, in bytes. Carried verbatim, negatives included — Forgejo uses a negative limit for "unlimited"
  *   and translating that here would hide it
  * @param subjects
  *   the `subjects` array; empty when absent, `null`, or not an array
  */
final case class QuotaRuleDto(name: Option[String], limit: Option[Long], subjects: Vector[String]):

  /** Converts to the domain. Total: the rule declares no required property. */
  def toDomain: QuotaRule =
    QuotaRule(name = name, limit = limit, subjects = subjects)

object QuotaRuleDto:

  /** Reads a `QuotaRuleInfo` object. */
  given upickle.default.Reader[QuotaRuleDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaRuleDto =
    QuotaRuleDto(name = fields.text("name"), limit = fields.number("limit"), subjects = fields.texts("subjects"))
