package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.{NodeInfo, NodeInfoServices, NodeInfoSoftware, NodeInfoUsage, NodeInfoUsers}

/** Forgejo's `NodeInfo` model — the body of `GET /nodeinfo`.
  *
  * '''The keys are camelCase.''' `openRegistrations`, and inside the nested models `localPosts`, `localComments`,
  * `activeHalfyear`, `activeMonth`. This is the one model in the codec module whose spellings are not Forgejo's own:
  * NodeInfo is a cross-project schema, and Forgejo emits it as the schema defines it. Rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] is unaffected — each name is still written exactly once, below.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' `golden/MANIFEST.md` records that
  * `GET /nodeinfo` answered `404` with a plain-text body on codeberg.org, so nothing was harvested.
  *
  * '''`metadata` is read and discarded.''' The schema types it as a free-form object and the domain module depends on
  * nothing beyond the standard library, so there is nowhere honest to put it — see [[NodeInfo]].
  *
  * @param version
  *   `version`, the NodeInfo schema version
  * @param software
  *   `software`, the nested software description
  * @param protocols
  *   `protocols`, the federation protocols spoken
  * @param services
  *   `services`, the nested third-party service lists
  * @param usage
  *   `usage`, the nested activity counts
  * @param openRegistrations
  *   `openRegistrations`, whether anyone may sign up
  */
final case class NodeInfoDto(
    version: Option[String],
    software: Option[NodeInfoSoftwareDto],
    protocols: Vector[String],
    services: Option[NodeInfoServicesDto],
    usage: Option[NodeInfoUsageDto],
    openRegistrations: Option[Boolean],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `version` and `software` are both required, and `software.name` with them: a NodeInfo document that names neither
    * its schema version nor the software it describes answers neither of the two questions the endpoint exists for. A
    * missing `software.name` is reported at `$.software.name`, which is why the nested conversion is threaded through
    * `toDomainAt` rather than flattened here.
    *
    * `openRegistrations` defaults to `false` when absent — an instance that will not say it is open is not promised to
    * be.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, NodeInfo] =
    for
      schema        <- Wire.required(at, "version", version)
      softwareDto   <- Wire.required(at, "software", software)
      softwareValue <- softwareDto.toDomainAt(at.field("software"))
    yield NodeInfo(
      version              = schema,
      software             = softwareValue,
      protocols            = protocols,
      services             = services.map(_.toDomainValue),
      usage                = usage.map(_.toDomainValue),
      hasOpenRegistrations = openRegistrations.getOrElse(false),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, NodeInfo] =
    toDomainAt(JsonPath.Root)

object NodeInfoDto:

  /** Reads a `NodeInfo` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[NodeInfoDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): NodeInfoDto =
    NodeInfoDto(
      version           = fields.text("version"),
      software          = fields.nested("software").map(NodeInfoSoftwareDto.fromFields),
      protocols         = fields.texts("protocols"),
      services          = fields.nested("services").map(NodeInfoServicesDto.fromFields),
      usage             = fields.nested("usage").map(NodeInfoUsageDto.fromFields),
      openRegistrations = fields.boolean("openRegistrations"),
    )

/** Forgejo's `NodeInfoSoftware` model, nested inside [[NodeInfoDto]].
  *
  * @param name
  *   `name`, the software's lowercase name
  * @param version
  *   `version`, the release string
  * @param repository
  *   `repository`, where the source is published
  * @param homepage
  *   `homepage`, the project site
  */
final case class NodeInfoSoftwareDto(
    name: Option[String],
    version: Option[String],
    repository: Option[String],
    homepage: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `name` is required: it is the field a consumer of a NodeInfo document branches on, and a fabricated empty name
    * would make a Forgejo look like an unidentified server.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, NodeInfoSoftware] =
    Wire
      .required(at, "name", name)
      .map(value =>
        NodeInfoSoftware(
          name       = value,
          version    = version,
          repository = repository,
          homepage   = homepage,
        )
      )

object NodeInfoSoftwareDto:

  /** Reads a `NodeInfoSoftware` object standing alone; nested use goes through [[fromFields]]. */
  given JsonDecoder[NodeInfoSoftwareDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): NodeInfoSoftwareDto =
    NodeInfoSoftwareDto(
      name       = fields.text("name"),
      version    = fields.text("version"),
      repository = fields.text("repository"),
      homepage   = fields.text("homepage"),
    )

/** Forgejo's `NodeInfoServices` model, nested inside [[NodeInfoDto]].
  *
  * @param inbound
  *   `inbound`, services content may arrive from
  * @param outbound
  *   `outbound`, services content may be published to
  */
final case class NodeInfoServicesDto(
    inbound: Vector[String],
    outbound: Vector[String],
):

  /** Converts to the domain.
    *
    * Total rather than `Either`, unlike most of rule 5 of [[com.worxbend.codeberg4s.codec.WireConventions]]: both
    * fields are collections, and rule 2 makes an absent or `null` array an empty one, so there is no field this
    * conversion could find missing. A `toDomainAt` taking a [[com.worxbend.codeberg4s.JsonPath]] would return `Right`
    * at every call site and force the caller to handle a failure that cannot happen.
    */
  def toDomainValue: NodeInfoServices =
    NodeInfoServices(inbound = inbound, outbound = outbound)

object NodeInfoServicesDto:

  /** Reads a `NodeInfoServices` object standing alone; nested use goes through [[fromFields]]. */
  given JsonDecoder[NodeInfoServicesDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): NodeInfoServicesDto =
    NodeInfoServicesDto(
      inbound  = fields.texts("inbound"),
      outbound = fields.texts("outbound"),
    )

/** Forgejo's `NodeInfoUsage` model, nested inside [[NodeInfoDto]].
  *
  * @param users
  *   `users`, the nested account counts
  * @param localPosts
  *   `localPosts`, issues created on this instance
  * @param localComments
  *   `localComments`, comments created on this instance
  */
final case class NodeInfoUsageDto(
    users: Option[NodeInfoUsersDto],
    localPosts: Option[Long],
    localComments: Option[Long],
):

  /** Converts to the domain.
    *
    * Total rather than `Either`, for the reason [[NodeInfoServicesDto.toDomainValue]] gives: nothing in the usage block
    * is required, because a deployment is free to publish none of it and a fabricated `0` would be indistinguishable
    * from a genuinely empty instance.
    */
  def toDomainValue: NodeInfoUsage =
    NodeInfoUsage(
      users         = users.map(_.toDomainValue),
      localPosts    = localPosts,
      localComments = localComments,
    )

object NodeInfoUsageDto:

  /** Reads a `NodeInfoUsage` object standing alone; nested use goes through [[fromFields]]. */
  given JsonDecoder[NodeInfoUsageDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): NodeInfoUsageDto =
    NodeInfoUsageDto(
      users         = fields.nested("users").map(NodeInfoUsersDto.fromFields),
      localPosts    = fields.number("localPosts"),
      localComments = fields.number("localComments"),
    )

/** Forgejo's `NodeInfoUsageUsers` model, nested inside [[NodeInfoUsageDto]].
  *
  * @param total
  *   `total`, accounts that exist
  * @param activeHalfyear
  *   `activeHalfyear`, accounts active in the last six months
  * @param activeMonth
  *   `activeMonth`, accounts active in the last month
  */
final case class NodeInfoUsersDto(
    total: Option[Long],
    activeHalfyear: Option[Long],
    activeMonth: Option[Long],
):

  /** Converts to the domain.
    *
    * Total rather than `Either`, and the only conversion in the module that is: no field here can be required, so there
    * is no failure to report and an `Either` would be a `Right` at every call site.
    */
  def toDomainValue: NodeInfoUsers =
    NodeInfoUsers(total = total, activeHalfyear = activeHalfyear, activeMonth = activeMonth)

object NodeInfoUsersDto:

  /** Reads a `NodeInfoUsageUsers` object standing alone; nested use goes through [[fromFields]]. */
  given JsonDecoder[NodeInfoUsersDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): NodeInfoUsersDto =
    NodeInfoUsersDto(
      total          = fields.number("total"),
      activeHalfyear = fields.number("activeHalfyear"),
      activeMonth    = fields.number("activeMonth"),
    )
