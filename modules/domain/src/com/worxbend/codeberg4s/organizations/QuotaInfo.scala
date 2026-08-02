package com.worxbend.codeberg4s.organizations

/** An organisation's quota — the rules that apply to it and what it has used, as `GET /orgs/{org}/quota` reports it.
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
  * nothing about LFS" is a different fact from "this organisation uses no LFS". Reporting the second when the first is
  * true would let a caller draw a usage chart out of silence. The '''grouping''' objects are not optional in the same
  * way: an absent `used` or `size` object becomes the corresponding `Empty`, whose leaves are all `None`, so navigating
  * the tree never needs a chain of `Option`s to reach a number that was never sent anyway.
  *
  * ==Sizes are bytes==
  *
  * The spec describes every one of them as "storage size" with `format: int64` and names no unit. Forgejo's quota
  * engine counts bytes, and that is what these are reported as; nothing here rounds, scales or formats.
  *
  * @param groups
  *   the quota groups the organisation belongs to, each carrying its rules. Empty when the payload carried none, which
  *   on an instance with quota disabled is what to expect
  * @param used
  *   what the organisation has consumed; [[QuotaUsage.Empty]] when the payload said nothing
  */
final case class QuotaInfo(groups: Vector[QuotaGroup], used: QuotaUsage)

/** One quota group — a named bundle of rules Forgejo applies together.
  *
  * @param name
  *   the group's name. Absent both when the payload omitted it and when the caller is not an administrator: the spec
  *   marks [[QuotaRule.name]] "only shown to admins" and the group name travels the same way
  * @param rules
  *   the rules in the group, empty when none came back
  */
final case class QuotaGroup(name: Option[String], rules: Vector[QuotaRule])

/** One quota rule — a limit, and the subjects it counts towards.
  *
  * @param name
  *   the rule's name, which the spec marks "only shown to admins"; absent for a caller who is not one
  * @param limit
  *   the limit the rule sets, in bytes. Absent when the instance did not say. Forgejo uses a negative limit to mean
  *   "unlimited", and that is reported verbatim rather than translated — a caller comparing usage against a limit must
  *   check the sign first
  * @param subjects
  *   what the rule counts, as the instance spells them: `size:all`, `size:repos:public` and so on. Plain strings and
  *   not [[QuotaSubject]] on purpose, for the reason [[com.worxbend.codeberg4s.issues.LabelName]] gives — a subject a
  *   newer Forgejo emits must not cost the caller the whole rule. Converting one for use as a query argument is the
  *   caller's explicit step through [[QuotaSubject.from]]
  */
final case class QuotaRule(name: Option[String], limit: Option[Long], subjects: Vector[String])

/** What an organisation has consumed. One field today, because `QuotaUsed` declares one property. */
final case class QuotaUsage(size: QuotaSizes)

object QuotaUsage:

  /** The usage a payload that said nothing decodes to. */
  val Empty: QuotaUsage = QuotaUsage(QuotaSizes.Empty)

/** The size-based usage breakdown — Forgejo's `QuotaUsedSize`.
  *
  * @param repositories
  *   Git repository storage, split public and private
  * @param assets
  *   everything that is not the repository itself: artifacts, attachments, packages
  * @param git
  *   Git object storage that is billed separately, which today means LFS
  */
final case class QuotaSizes(repositories: QuotaRepositorySizes, assets: QuotaAssetSizes, git: QuotaGitSizes)

object QuotaSizes:

  /** The breakdown a payload that said nothing decodes to. */
  val Empty: QuotaSizes = QuotaSizes(QuotaRepositorySizes.Empty, QuotaAssetSizes.Empty, QuotaGitSizes.Empty)

/** Repository storage, in bytes.
  *
  * @param publicBytes
  *   storage used by public repositories
  * @param privateBytes
  *   storage used by private repositories
  */
final case class QuotaRepositorySizes(publicBytes: Option[Long], privateBytes: Option[Long])

object QuotaRepositorySizes:

  /** Nothing said about either. */
  val Empty: QuotaRepositorySizes = QuotaRepositorySizes(None, None)

/** Asset storage, in bytes.
  *
  * @param artifactBytes
  *   storage used by Actions artifacts
  * @param attachments
  *   storage used by attachments, itself split by what they hang off
  * @param packageBytes
  *   storage used by packages — the spec's `packages.all`, which is the only property that object has
  */
final case class QuotaAssetSizes(
    artifactBytes: Option[Long],
    attachments: QuotaAttachmentSizes,
    packageBytes: Option[Long],
)

object QuotaAssetSizes:

  /** Nothing said about any of them. */
  val Empty: QuotaAssetSizes = QuotaAssetSizes(None, QuotaAttachmentSizes.Empty, None)

/** Attachment storage, in bytes.
  *
  * @param issueBytes
  *   storage used by attachments on issues and comments — the spec's `issues`, which covers both
  * @param releaseBytes
  *   storage used by attachments on releases
  */
final case class QuotaAttachmentSizes(issueBytes: Option[Long], releaseBytes: Option[Long])

object QuotaAttachmentSizes:

  /** Nothing said about either. */
  val Empty: QuotaAttachmentSizes = QuotaAttachmentSizes(None, None)

/** Separately-billed Git storage, in bytes.
  *
  * @param lfsBytes
  *   storage used by Git LFS objects. The wire key is `LFS`, in capitals — the one upper-case key in this whole model,
  *   because Go's field is `LFS` and the spec's generator left it alone
  */
final case class QuotaGitSizes(lfsBytes: Option[Long])

object QuotaGitSizes:

  /** Nothing said about it. */
  val Empty: QuotaGitSizes = QuotaGitSizes(None)
