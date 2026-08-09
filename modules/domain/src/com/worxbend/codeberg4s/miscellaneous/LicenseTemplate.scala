package com.worxbend.codeberg4s.miscellaneous

/** One license the instance ships, text and all — what `GET /licenses/{name}` answers.
  *
  * '''Derived from `spec/swagger.v1.json`'s `LicenseTemplateInfo` definition, not from a captured response'''; see
  * [[LicenseTemplateSummary]] for why no fixture exists.
  *
  * '''This is a template, not a legal opinion.''' [[body]] is the file Forgejo would copy into a new repository, with
  * its placeholders — `[year]`, `[fullname]` — left exactly as they are. Substituting them is the caller's job, and
  * this library deliberately does not guess at the substitution rules, which differ per license family.
  *
  * @param body
  *   the license text, verbatim. This is the answer to the question the call asked, so a payload without it does not
  *   decode
  * @param name
  *   the license's own name, as the instance echoed it. Absent when it echoed none; the caller already knows which
  *   license they asked for
  * @param key
  *   the instance's second spelling of the identifier; see [[LicenseTemplateSummary.key]]
  * @param implementation
  *   Forgejo's free-text note on how the license is meant to be applied, which is prose and not a machine value
  * @param url
  *   the API URL of the template itself
  */
final case class LicenseTemplate private[codeberg4s] (
    body: String,
    name: Option[String],
    key: Option[String],
    implementation: Option[String],
    url: Option[String],
)
