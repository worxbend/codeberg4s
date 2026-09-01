package com.worxbend.codeberg4s.quota.wire

import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.quota.{QuotaGroup, QuotaInfo, QuotaRule, QuotaUsedSize}

/** Forgejo's `QuotaInfo` model and the definitions nested inside it, read once for every route that answers it —
  * `GET /orgs/{org}/quota` and `GET /user/quota` send the same payload.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''' — see
  * [[com.worxbend.codeberg4s.quota.QuotaInfo]] for why no quota fixture exists anywhere in this repository.
  *
  * ==Three DTOs, not ten==
  *
  * `QuotaInfo`, `QuotaGroup`, `QuotaRuleInfo`, `QuotaUsed`, `QuotaUsedSize`, `QuotaUsedSizeRepos`,
  * `QuotaUsedSizeAssets`, `QuotaUsedSizeAssetsAttachments`, `QuotaUsedSizeAssetsPackages` and `QuotaUsedSizeGit` are
  * ten spec definitions describing one payload, and six of them exist only to hold one or two numbers each. Rule 6 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] says a nested model nests its DTO — the point of which is that an
  * embedded model's '''failure path''' is reported once, by that model. None of the size objects can fail: every leaf
  * is a number that is either there or not, and there is no required field anywhere below `used`. Six DTOs, six
  * companions and six readers would therefore buy nothing but six more places for a wire spelling to be mistyped.
  *
  * So the whole `used` tree is read here and projected into the flat [[com.worxbend.codeberg4s.quota.QuotaUsedSize]],
  * and the '''two''' definitions that carry a list of things worth naming — `QuotaGroup` and `QuotaRuleInfo` — do get
  * DTOs of their own, because a caller reads them and because a future required field would land there.
  *
  * ==Wire spellings worth pointing at==
  *
  *   - `git.LFS` is upper-case, alone in this payload. Go's field is `LFS` and the spec's generator left it alone; a
  *     reader that lower-cased it would silently report no LFS usage at all. Rule 4 of
  *     [[com.worxbend.codeberg4s.codec.WireConventions]] is why it is written down exactly once, here.
  *   - `assets.packages.all` is an object with one property, not a number. Reading it as a number would answer `None`
  *     for every instance.
  *   - `assets.attachments.issues` covers issue '''and''' comment attachments, which the spec's own description says.
  */
final case class QuotaInfoDto(groups: Vector[QuotaGroupDto], used: QuotaUsedSize):

  /** Converts to the domain.
    *
    * '''Cannot fail''', and there is no `toDomainAt`: nothing in this payload is required, because nothing in it
    * addresses anything. A group whose rules the instance sent in a shape this library cannot read loses those rules
    * and keeps the group, which is the leniency `docs/HAZARDS.md` §1 demands.
    */
  def toDomain: Either[DecodeFailure, QuotaInfo] =
    Right(QuotaInfo(groups = groups.map(_.toDomain), used = used))

object QuotaInfoDto:

  /** The key holding the size tree inside `used`. */
  val SizeKey: String = "size"

  /** The upper-case key holding Git LFS usage; see the class note. */
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
    * Every step tolerates an absent or wrongly typed object by answering nothing, so a payload that stops nesting
    * halfway yields the headings it did carry and `None` for the rest.
    */
  private def usedFrom(used: Option[JsonFields]): QuotaUsedSize =
    val size        = used.flatMap(_.nested(SizeKey))
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

/** Forgejo's `QuotaGroup` model — a named bundle of rules.
  *
  * '''Derived from the spec, not from a capture'''; see [[QuotaInfoDto]].
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
  given JsonDecoder[QuotaGroupDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaGroupDto =
    QuotaGroupDto(name = fields.text("name"), rules = fields.nestedAll("rules").map(QuotaRuleDto.fromFields))

/** Forgejo's `QuotaRuleInfo` model — one limit and what it counts.
  *
  * '''Derived from the spec, not from a capture'''; see [[QuotaInfoDto]].
  *
  * @param name
  *   the `name` key, administrators only
  * @param limit
  *   the `limit` key, in bytes. Carried verbatim, negatives included — Forgejo uses a negative limit for "unlimited"
  *   and translating that here would hide it; see [[com.worxbend.codeberg4s.quota.QuotaRule.isUnlimited]]
  * @param subjects
  *   the `subjects` array, carried as the instance spelled them; empty when absent, `null`, or not an array. Nothing is
  *   validated or dropped here: a subject this library cannot parse is still a subject the instance applies, and losing
  *   it would report a rule that counts less than it does
  */
final case class QuotaRuleDto(name: Option[String], limit: Option[Long], subjects: Vector[String]):

  /** Converts to the domain. Total: the rule declares no required property. */
  def toDomain: QuotaRule =
    QuotaRule(name = name, limit = limit, subjects = subjects)

object QuotaRuleDto:

  /** Reads a `QuotaRuleInfo` object. */
  given JsonDecoder[QuotaRuleDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): QuotaRuleDto =
    QuotaRuleDto(name = fields.text("name"), limit = fields.number("limit"), subjects = fields.texts("subjects"))
