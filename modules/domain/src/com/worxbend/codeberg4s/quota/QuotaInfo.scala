package com.worxbend.codeberg4s.quota

/** A quota report — the rules that apply to a subject of quota and what it has used.
  *
  * One model, read by both `GET /orgs/{org}/quota` and `GET /user/quota`. Forgejo answers both routes with the same
  * `QuotaInfo` definition, so a program that reads an organisation's quota and its own gets one type rather than two
  * that would collide on import.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' Quota is a Forgejo feature that many
  * instances leave disabled and every quota route needs a token, so the anonymous golden harvest behind
  * `modules/codec/test/resources/golden` contains nothing for it. The field sets in this file are the spec's
  * `QuotaInfo`, `QuotaGroup`, `QuotaRuleInfo`, `QuotaUsed` and the four `QuotaUsedSize*` definitions, read literally
  * under the rule `docs/HAZARDS.md` §1 forces on the whole API.
  *
  * ==Absent is not zero, which is why every size is an `Option`==
  *
  * A quota tree arrives with most of it missing on an instance that does not track a category, and "the instance said
  * nothing about LFS" is a different fact from "this subject uses no LFS". Reporting the second when the first is true
  * would let a caller draw a usage chart out of silence.
  *
  * ==Sizes are bytes==
  *
  * The spec describes every one of them as "storage size" with `format: int64` and names no unit. Forgejo's quota
  * engine counts bytes, and that is what these are reported as; nothing here rounds, scales or formats.
  *
  * @param groups
  *   the quota groups the subject belongs to, each carrying its rules. Empty when the payload carried none, which is
  *   what an instance with quota disabled and an account under no explicit group both look like — it is '''not''' the
  *   same as "unlimited", and nothing in this response says which it is
  * @param used
  *   what the subject is currently storing; [[QuotaUsedSize.Empty]] when the payload said nothing
  */
final case class QuotaInfo private[codeberg4s] (groups: Vector[QuotaGroup], used: QuotaUsedSize):

  /** Every rule the subject is governed by, across all its groups, in the order the instance listed them.
    *
    * Rules are not de-duplicated: two groups naming the same limit for the same subject are two rules, and collapsing
    * them would hide that the quota is imposed twice.
    */
  def rules: Vector[QuotaRule] =
    groups.flatMap(_.rules)

/** One quota group — a named bundle of rules Forgejo applies together.
  *
  * @param name
  *   the group's name. Absent both when the payload omitted it and when the caller is not an administrator: the spec
  *   marks [[QuotaRule.name]] "only shown to admins" and the group name travels the same way
  * @param rules
  *   the rules in the group, empty when none came back
  */
final case class QuotaGroup private[codeberg4s] (name: Option[String], rules: Vector[QuotaRule])

/** One quota rule — a limit, and the subjects it counts towards.
  *
  * @param name
  *   the rule's name, which the spec marks "only shown to admins"; absent for a caller who is not one, which is
  *   ordinarily the case on `/user/quota`, and why this is an `Option` and not something a caller may key on
  * @param limit
  *   the limit the rule sets, in bytes. Absent when the instance did not say. Forgejo uses a negative limit to mean
  *   "unlimited", and that is reported verbatim rather than folded into absence — folding it would make an unlimited
  *   rule indistinguishable from a rule whose limit the instance did not report. See [[isUnlimited]]
  * @param subjects
  *   what the rule counts, as the instance spells them: `size:all`, `size:repos:public` and so on. Plain strings and
  *   not [[QuotaSubject]] on purpose, for the reason [[com.worxbend.codeberg4s.issues.LabelName]] gives — a subject a
  *   newer Forgejo emits must not cost the caller the whole rule, and dropping one it cannot parse would lose data the
  *   instance did send. Converting one for use as a query argument is the caller's explicit step through
  *   [[QuotaSubject.from]]
  */
final case class QuotaRule private[codeberg4s] (name: Option[String], limit: Option[Long], subjects: Vector[String]):

  /** Whether the rule states no ceiling at all, which Forgejo spells as a negative limit.
    *
    * `false` when the limit is absent: "the instance did not report a limit" is not "there is no limit", and a caller
    * deciding whether to warn a user must not read the two the same way.
    */
  def isUnlimited: Boolean =
    limit.exists(_ < 0L)

/** The size breakdown of what a subject of quota is storing, in bytes.
  *
  * ==One wire tree is flattened, deliberately==
  *
  * The payload nests `used → size → {repos, git, assets}` and then `assets → {artifacts, attachments, packages}`, with
  * `attachments` and `packages` each being another object. Six of those seven objects exist only to hold one or two
  * integers, and none of them carries information a caller could act on — an absent `used.size.assets` and an absent
  * `used.size.assets.artifacts` mean the same thing. So the whole tree is projected into these seven leaves, which is
  * the one place in this model that does not mirror the wire shape one for one;
  * [[com.worxbend.codeberg4s.quota.wire.QuotaInfoDto]] performs the flattening.
  *
  * Every field is an `Option` because `docs/HAZARDS.md` §1 measured that the spec declares nothing required, and
  * because the distinction matters here more than usual: `Some(0)` is "this instance measured nothing under this
  * heading", while `None` is "this instance did not report this heading at all" — which is what an older Forgejo, or
  * one with a storage backend that cannot be measured, looks like. Summing the two together would invent a number.
  *
  * @param publicRepositories
  *   the size of the public repositories
  * @param privateRepositories
  *   the size of the private repositories
  * @param gitLfs
  *   the size of the Git LFS objects. Note the wire key is the upper-case `LFS`, not `lfs`
  * @param artifacts
  *   the size of the Actions artifacts
  * @param issueAttachments
  *   the size of attachments on issues and comments — the spec's `issues`, which covers both
  * @param releaseAttachments
  *   the size of attachments on releases
  * @param packages
  *   the size of the published packages — the spec's `assets.packages.all`, which is the only property that object has
  */
final case class QuotaUsedSize private[codeberg4s] (
    publicRepositories: Option[Long],
    privateRepositories: Option[Long],
    gitLfs: Option[Long],
    artifacts: Option[Long],
    issueAttachments: Option[Long],
    releaseAttachments: Option[Long],
    packages: Option[Long],
):

  /** Everything the instance did report, added up.
    *
    * Headings the instance did not report contribute nothing, which means this is a lower bound on what is stored and
    * not an authoritative total — the API publishes no total of its own. A caller displaying a figure should say so.
    */
  def reportedTotal: Long =
    List(
      publicRepositories,
      privateRepositories,
      gitLfs,
      artifacts,
      issueAttachments,
      releaseAttachments,
      packages,
    ).flatten.sum

object QuotaUsedSize:

  /** The breakdown in which the instance reported nothing at all. */
  val Empty: QuotaUsedSize =
    QuotaUsedSize(
      publicRepositories  = None,
      privateRepositories = None,
      gitLfs              = None,
      artifacts           = None,
      issueAttachments    = None,
      releaseAttachments  = None,
      packages            = None,
    )
