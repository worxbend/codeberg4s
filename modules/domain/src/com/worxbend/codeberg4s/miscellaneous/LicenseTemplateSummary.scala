package com.worxbend.codeberg4s.miscellaneous

/** One entry of the license catalogue — an element of what `GET /licenses` answers.
  *
  * A summary and not a [[LicenseTemplate]]: the listing carries no license text, because the full catalogue with bodies
  * would be a multi-megabyte response. [[LicenseTemplateSummary.name]] is what
  * [[com.worxbend.codeberg4s.miscellaneous.MiscellaneousApi.licenseTemplate]] is given to fetch the text.
  *
  * '''Derived from `spec/swagger.v1.json`'s `LicensesTemplateListEntry` definition, not from a captured response.'''
  * `golden/MANIFEST.md` records that `GET /licenses` was probed and deliberately '''not''' stored: it answered roughly
  * 80 KB with no `limit` support, and was dropped as fixture noise. So the field set below is the spec read literally.
  *
  * @param name
  *   what addresses the template — the `{name}` of `/licenses/{name}`. Required: an entry that names nothing is an
  *   entry nothing can be done with
  * @param key
  *   the spec's second spelling of the same identifier, kept because the two are separate properties on the wire and
  *   this library does not decide that they always agree
  * @param url
  *   the API URL of the template itself, as the instance rendered it
  */
final case class LicenseTemplateSummary private[codeberg4s] (
    name: TemplateName,
    key: Option[String],
    url: Option[String],
)
