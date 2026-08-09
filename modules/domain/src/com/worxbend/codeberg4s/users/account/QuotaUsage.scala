package com.worxbend.codeberg4s.users.account

/** One Actions artifact counting towards the account's quota — an element of `GET /user/quota/artifacts`.
  *
  * '''Derived from `spec/swagger.v1.json`'s `QuotaUsedArtifact` definition, not from a captured response'''; see
  * [[OAuth2Application]].
  *
  * ==Not the same type as an Actions artifact==
  *
  * [[com.worxbend.codeberg4s.repositories.actions.ActionArtifact]] is what the Actions API returns: it has an id, a
  * run, an expiry and a download URL, and it is addressable. This one has three properties and no identifier at all —
  * it exists to answer "what is taking up my space", not "which artifact is this". Widening the Actions model to cover
  * it would give every field of it an `Option` that is always empty on this endpoint, so the two stay separate.
  *
  * @param name
  *   the artifact's name. Absent when the instance sent none
  * @param size
  *   the compressed size in bytes. Absent when the instance sent none, which is not the same as zero
  * @param htmlUrl
  *   a browser link to the run that produced the artifact, which is the closest thing this payload has to a way back to
  *   the object
  */
final case class QuotaUsedArtifact private[codeberg4s] (
    name: Option[String],
    size: Option[Long],
    htmlUrl: Option[String],
)

/** One attachment counting towards the account's quota — an element of `GET /user/quota/attachments`.
  *
  * '''Derived from `spec/swagger.v1.json`'s `QuotaUsedAttachment` definition, not from a captured response'''; see
  * [[OAuth2Application]].
  *
  * @param name
  *   the file name. Absent when the instance sent none
  * @param size
  *   the size in bytes. Absent when the instance sent none
  * @param apiUrl
  *   the API link to the attachment itself
  * @param containedIn
  *   where the attachment hangs — the issue, comment or release it belongs to. Absent when the payload carried no
  *   `contained_in` object at all
  */
final case class QuotaUsedAttachment private[codeberg4s] (
    name: Option[String],
    size: Option[Long],
    apiUrl: Option[String],
    containedIn: Option[AttachmentContainer],
)

/** The object an attachment belongs to, as the quota listing describes it.
  *
  * The spec declares `contained_in` inline — an anonymous object with two URL properties and no `$ref` — rather than as
  * a named definition. It is modelled as a type anyway rather than as two fields on [[QuotaUsedAttachment]], because
  * the two URLs are absent or present together and a caller that has neither has no container, which an `Option` of
  * this type says and two independent `Option[String]` fields do not.
  *
  * @param apiUrl
  *   the API link to the containing object
  * @param htmlUrl
  *   the browser link to the containing object
  */
final case class AttachmentContainer private[codeberg4s] (
    apiUrl: Option[String],
    htmlUrl: Option[String],
)

/** One published package version counting towards the account's quota — an element of `GET /user/quota/packages`.
  *
  * '''Derived from `spec/swagger.v1.json`'s `QuotaUsedPackage` definition, not from a captured response'''; see
  * [[OAuth2Application]].
  *
  * @param name
  *   the package's name. Absent when the instance sent none
  * @param version
  *   the version this entry measures. A package with several versions appears once per version
  * @param packageType
  *   the registry the package lives in — `container`, `maven`, `npm` and so on. A plain `String` because the set is
  *   instance configuration: a registry a Forgejo release adds must not be one this library drops
  * @param size
  *   the size in bytes. Absent when the instance sent none
  * @param htmlUrl
  *   a browser link to the package version
  */
final case class QuotaUsedPackage private[codeberg4s] (
    name: Option[String],
    version: Option[String],
    packageType: Option[String],
    size: Option[Long],
    htmlUrl: Option[String],
)
