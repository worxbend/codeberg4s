package com.worxbend.codeberg4s.users.account

/** What the authenticated account is allowed to store, and what it is storing — `GET /user/quota`.
  *
  * '''Derived from `spec/swagger.v1.json`'s `QuotaInfo` definition, not from a captured response'''; see
  * [[OAuth2Application]] for what that means and why no fixture exists. Quota is also a feature an instance may not
  * have enabled at all, in which case the endpoint answers rather than this model being wrong.
  *
  * ==One wire level is flattened, deliberately==
  *
  * The payload nests as `{"used": {"size": {...}}}`: `QuotaUsed` is a definition whose only property is `size`, which
  * is a `QuotaUsedSize`. That intermediate object carries no information — there is nothing else it could hold and
  * nothing that distinguishes an absent `used` from an absent `used.size` — so it is not modelled, and [[used]] is the
  * size breakdown directly. The flattening is stated here because it is the one place this model does not mirror the
  * wire shape one for one; [[com.worxbend.codeberg4s.users.account.wire.QuotaInfoDto]] performs it.
  *
  * @param groups
  *   the quota groups the account belongs to, each with its rules. Empty when the payload carried none, which is what
  *   an account under no explicit group looks like — it is not the same as "unlimited", and nothing in this response
  *   says which it is
  * @param used
  *   what the account is currently storing, broken down by what is storing it
  */
final case class QuotaInfo private[codeberg4s] (
    groups: Vector[QuotaGroup],
    used: QuotaUsedSize,
):

  /** Every rule the account is subject to, across all its groups, in the order the instance listed them.
    *
    * Rules are not de-duplicated: two groups naming the same limit for the same subject are two rules, and collapsing
    * them would hide that the account is governed twice.
    */
  def rules: Vector[QuotaRule] =
    groups.flatMap(_.rules)

/** One named collection of quota rules an account belongs to.
  *
  * @param name
  *   the group's name, absent when the instance sent none
  * @param rules
  *   the limits the group imposes. Empty when the payload carried none
  */
final case class QuotaGroup private[codeberg4s] (
    name: Option[String],
    rules: Vector[QuotaRule],
)

/** One quota limit, and what it applies to.
  *
  * @param name
  *   the rule's name. `spec/swagger.v1.json` notes it is "only shown to admins", so this is ordinarily absent on
  *   `/user/quota` — which is why it is an `Option` and not something a caller may key on
  * @param limit
  *   the ceiling the rule sets, in bytes. Absent when the instance sent none. A negative value is Forgejo's spelling of
  *   "unlimited" and is preserved rather than folded away, because folding it into absence would make an unlimited rule
  *   indistinguishable from a rule whose limit the instance did not report
  * @param subjects
  *   what the rule counts; see [[QuotaSubject]] for why the vocabulary is not enumerated. A subject the instance sent
  *   that cannot be one — blank, or carrying a control character — is dropped rather than failing the whole rule
  */
final case class QuotaRule private[codeberg4s] (
    name: Option[String],
    limit: Option[Long],
    subjects: Vector[QuotaSubject],
):

  /** Whether the rule states no ceiling at all, which Forgejo spells as a negative limit.
    *
    * `false` when the limit is absent: "the instance did not report a limit" is not "there is no limit", and a caller
    * deciding whether to warn a user must not read the two the same way.
    */
  def isUnlimited: Boolean =
    limit.exists(_ < 0L)

/** The size breakdown of what an account is storing, in bytes.
  *
  * Every field is an `Option` because `docs/HAZARDS.md` §1 measured that the spec declares nothing required, and
  * because the distinction matters here more than usual: `Some(0)` is "this instance measured nothing under this
  * heading", while `None` is "this instance did not report this heading at all" — which is what an older Forgejo, or
  * one with a storage backend that cannot be measured, looks like. Summing the two together would invent a number.
  *
  * @param publicRepositories
  *   the size of the account's public repositories
  * @param privateRepositories
  *   the size of the account's private repositories
  * @param gitLfs
  *   the size of the account's Git LFS objects. Note the wire key is the upper-case `LFS`, not `lfs`
  * @param artifacts
  *   the size of the account's Actions artifacts
  * @param issueAttachments
  *   the size of attachments on the account's issues and comments
  * @param releaseAttachments
  *   the size of attachments on the account's releases
  * @param packages
  *   the size of the account's published packages
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
    * Headings the instance did not report contribute nothing, which means this is a lower bound on what the account is
    * storing and not an authoritative total — the API publishes no total of its own. A caller displaying a figure
    * should say so.
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
