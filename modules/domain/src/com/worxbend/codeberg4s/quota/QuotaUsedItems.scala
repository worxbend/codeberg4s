package com.worxbend.codeberg4s.quota

/** One Actions artifact counting towards a quota — Forgejo's `QuotaUsedArtifact`, an element of
  * `GET /orgs/{org}/quota/artifacts` and `GET /user/quota/artifacts`.
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
  * ==Not the same type as an Actions artifact==
  *
  * [[com.worxbend.codeberg4s.repositories.actions.ActionArtifact]] is what the Actions API returns: it has an id, a
  * run, an expiry and a download URL, and it is addressable. Widening that model to cover this one would give every
  * field of it an `Option` that is always empty on this endpoint, so the two stay separate.
  *
  * @param name
  *   the artifact's name as the workflow uploaded it. Not unique: two runs of one workflow produce two entries with the
  *   same name. Absent when the instance sent none
  * @param sizeBytes
  *   the compressed size, in bytes. The spec says "compressed", so this is smaller than what the workflow uploaded.
  *   Absent when the instance sent none, which is not the same as zero
  * @param htmlUrl
  *   a browser link to the action run containing the artifact, which is the closest thing this payload has to a way
  *   back to the object
  */
final case class QuotaUsedArtifact private[codeberg4s] (
    name: Option[String],
    sizeBytes: Option[Long],
    htmlUrl: Option[String],
)

/** The object an attachment belongs to, as the quota listing describes it — the `contained_in` object of Forgejo's
  * `QuotaUsedAttachment`.
  *
  * The spec declares `contained_in` inline — an anonymous object with two URL properties and no `$ref` — rather than as
  * a named definition, which is why it has no Forgejo type name of its own. It is modelled as a type anyway rather than
  * as two fields on [[QuotaUsedAttachment]], because the two URLs are absent or present together and a caller that has
  * neither has no container, which an `Option` of this type says and two independent `Option[String]` fields do not.
  * Both links point at the issue, comment or release the attachment belongs to, never at the attachment.
  *
  * @param apiUrl
  *   the API link to the containing object
  * @param htmlUrl
  *   the browser link to the containing object
  */
final case class AttachmentContainer private[codeberg4s] (apiUrl: Option[String], htmlUrl: Option[String])

/** One attachment counting towards a quota — Forgejo's `QuotaUsedAttachment`.
  *
  * The identifier note on [[QuotaUsedArtifact]] applies here too: [[apiUrl]] is a URL and not a handle any operation in
  * this library takes.
  *
  * @param name
  *   the attachment's filename. Absent when the instance sent none
  * @param sizeBytes
  *   its size in bytes. Absent when the instance sent none
  * @param apiUrl
  *   the API URL of the attachment itself
  * @param containedIn
  *   what it is attached to, absent when the payload named neither of the container's two links
  */
final case class QuotaUsedAttachment private[codeberg4s] (
    name: Option[String],
    sizeBytes: Option[Long],
    apiUrl: Option[String],
    containedIn: Option[AttachmentContainer],
)

/** One package version counting towards a quota — Forgejo's `QuotaUsedPackage`.
  *
  * The identifier note on [[QuotaUsedArtifact]] applies here too.
  *
  * @param name
  *   the package's name. Absent when the instance sent none
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
final case class QuotaUsedPackage private[codeberg4s] (
    name: Option[String],
    version: Option[String],
    packageType: Option[String],
    sizeBytes: Option[Long],
    htmlUrl: Option[String],
)
