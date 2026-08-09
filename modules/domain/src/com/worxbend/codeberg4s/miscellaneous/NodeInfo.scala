package com.worxbend.codeberg4s.miscellaneous

/** The federation document `GET /nodeinfo` answers: what software this instance runs, what it speaks, and how much it
  * is used.
  *
  * NodeInfo is a cross-project schema — Mastodon, PeerTube, Forgejo and the rest of the fediverse all serve it — which
  * is exactly why it is modelled here rather than handed back as a string: a caller reaching for `nodeinfo` is trying
  * to branch on `software.name` or on `protocols`, and doing that against raw JSON puts the schema's spelling into
  * their code instead of into this library's.
  *
  * '''The wire keys are camelCase, not snake_case.''' `openRegistrations`, `localPosts`, `activeHalfyear` — this is the
  * one model in the library whose spellings are not Forgejo's own, because the schema is not Forgejo's own. Rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] still holds: each of those names is written exactly once, in the
  * DTO's reader.
  *
  * '''Derived from `spec/swagger.v1.json`'s `NodeInfo` definition, not from a captured response.'''
  * `golden/MANIFEST.md` records that `GET /nodeinfo` answered `404` on codeberg.org with a '''plain-text''' body, so no
  * fixture exists and a caller must expect this endpoint to be absent on a deployment that does not federate.
  *
  * '''`metadata` is not modelled.''' The schema types it as a free-form JSON object, and the `domain` module depends on
  * nothing beyond the standard library, so there is no honest representation for it here — a `Map[String, String]`
  * would silently drop every non-string entry. Forgejo populates it with an empty object.
  *
  * @param version
  *   the NodeInfo schema version this document conforms to, `"2.1"` for Forgejo. Required: without it a consumer cannot
  *   know how to read the rest, which is the whole point of the field
  * @param software
  *   which forge, and which release of it. Required for the same reason a caller made the request
  * @param protocols
  *   the federation protocols the instance speaks, `activitypub` for a Forgejo that federates. Empty when the instance
  *   listed none
  * @param services
  *   the third-party services the instance can exchange content with
  * @param usage
  *   the instance's own account and activity counts
  * @param hasOpenRegistrations
  *   whether anyone may create an account without an invitation
  */
final case class NodeInfo private[codeberg4s] (
    version: String,
    software: NodeInfoSoftware,
    protocols: Vector[String],
    services: Option[NodeInfoServices],
    usage: Option[NodeInfoUsage],
    hasOpenRegistrations: Boolean,
)

/** Which forge software the instance runs, and where its source lives.
  *
  * @param name
  *   the software's own lowercase name — `forgejo`, `gitea`. Required: it is the field every consumer of a NodeInfo
  *   document branches on
  * @param version
  *   the release string, absent when the instance withholds it. Some deployments do, on purpose
  * @param repository
  *   where the source is published
  * @param homepage
  *   the project's own site
  */
final case class NodeInfoSoftware private[codeberg4s] (
    name: String,
    version: Option[String],
    repository: Option[String],
    homepage: Option[String],
)

/** The third-party services this instance can exchange content with.
  *
  * Both directions are empty on a stock Forgejo; the fields exist because the NodeInfo schema declares them, and an
  * absent array is an empty one here rather than a `None`, per rule 2 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * @param inbound
  *   services the instance can receive content from
  * @param outbound
  *   services the instance can publish content to
  */
final case class NodeInfoServices private[codeberg4s] (
    inbound: Vector[String],
    outbound: Vector[String],
)

/** How much the instance is used.
  *
  * '''These are counts, not a measurement of anything a caller can act on.''' A Forgejo instance reports `localPosts`
  * and `localComments` as the number of issues and issue comments it holds; the mapping is the schema's, not this
  * library's, and it is stated here so nobody has to guess what "post" means on a forge.
  *
  * @param users
  *   the account counts, absent when the instance withheld them
  * @param localPosts
  *   issues created on this instance
  * @param localComments
  *   comments created on this instance
  */
final case class NodeInfoUsage private[codeberg4s] (
    users: Option[NodeInfoUsers],
    localPosts: Option[Long],
    localComments: Option[Long],
)

/** The instance's account counts.
  *
  * Every field is optional because a deployment may decline to publish any of them, and a fabricated `0` would be
  * indistinguishable from a genuinely empty instance.
  *
  * @param total
  *   accounts that exist
  * @param activeHalfyear
  *   accounts that signed in within the last six months
  * @param activeMonth
  *   accounts that signed in within the last month
  */
final case class NodeInfoUsers private[codeberg4s] (
    total: Option[Long],
    activeHalfyear: Option[Long],
    activeMonth: Option[Long],
)
