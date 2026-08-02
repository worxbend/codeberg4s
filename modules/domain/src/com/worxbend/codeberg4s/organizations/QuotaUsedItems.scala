package com.worxbend.codeberg4s.organizations

/** One Actions artifact counting towards an organisation's quota — Forgejo's `QuotaUsedArtifact`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response'''; see [[QuotaInfo]] for why no quota fixture
  * exists.
  *
  * ==There is no identifier, so this is a report and not a handle==
  *
  * The spec declares three properties and none of them addresses the artifact: there is no id, and [[htmlUrl]] is a
  * browser link to the '''run''' that produced it rather than to the artifact. Nothing in this library can therefore
  * take one of these and delete it; that is what the Actions routes are for, and they are a different lane's. These
  * listings answer "what is filling the quota", not "what shall I remove".
  *
  * @param name
  *   the artifact's name as the workflow uploaded it. Not unique: two runs of one workflow produce two entries with the
  *   same name
  * @param sizeBytes
  *   the compressed size, in bytes. The spec says "compressed", so this is smaller than what the workflow uploaded
  * @param htmlUrl
  *   a browser link to the action run containing the artifact
  */
final case class QuotaArtifact(name: Option[String], sizeBytes: Option[Long], htmlUrl: Option[String])

/** Where an attachment hangs — the `contained_in` object of Forgejo's `QuotaUsedAttachment`.
  *
  * An inline object in the spec rather than a named definition, which is why it has no Forgejo type name of its own.
  * Both links point at the issue, comment or release the attachment belongs to, never at the attachment.
  *
  * @param apiUrl
  *   the API URL of the containing object
  * @param htmlUrl
  *   the browser URL of the containing object
  */
final case class QuotaAttachmentContext(apiUrl: Option[String], htmlUrl: Option[String])

/** One attachment counting towards an organisation's quota — Forgejo's `QuotaUsedAttachment`.
  *
  * The identifier note on [[QuotaArtifact]] applies here too: [[apiUrl]] is a URL and not a handle any operation in
  * this library takes.
  *
  * @param name
  *   the attachment's filename
  * @param sizeBytes
  *   its size in bytes
  * @param apiUrl
  *   the API URL of the attachment itself
  * @param containedIn
  *   what it is attached to, absent when the instance sent no context object
  */
final case class QuotaAttachment(
    name: Option[String],
    sizeBytes: Option[Long],
    apiUrl: Option[String],
    containedIn: Option[QuotaAttachmentContext],
)

/** One package version counting towards an organisation's quota — Forgejo's `QuotaUsedPackage`.
  *
  * The identifier note on [[QuotaArtifact]] applies here too.
  *
  * @param name
  *   the package's name
  * @param version
  *   the version this entry measures. A package with ten versions produces ten entries
  * @param packageType
  *   the registry the package lives in — `container`, `maven`, `npm` and so on. A plain string: the spec declares no
  *   `enum` for it, Forgejo adds registries between releases, and an enum here would be incomplete on the day it
  *   shipped. Named `packageType` rather than `type`, which is a Scala keyword
  * @param sizeBytes
  *   the version's size in bytes
  * @param htmlUrl
  *   a browser link to the package version
  */
final case class QuotaPackage(
    name: Option[String],
    version: Option[String],
    packageType: Option[String],
    sizeBytes: Option[Long],
    htmlUrl: Option[String],
)
