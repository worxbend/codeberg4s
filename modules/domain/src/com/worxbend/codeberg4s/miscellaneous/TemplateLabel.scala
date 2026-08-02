package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.issues.LabelColor

/** One label inside a label template — an element of what `GET /label/templates/{name}` answers.
  *
  * '''Forgejo calls this model `LabelTemplate`, and that name is wrong in a way worth not copying.''' A label
  * '''template''' is the named set — `Default`, `Advanced` — that `GET /label/templates` lists; the by-name endpoint's
  * own summary is "returns all labels in a template", and its body is an '''array''' of these. So one of these is a
  * label, not a template, and it is named for what it is. The wire model's name is recorded here so a reader with the
  * spec open can find it.
  *
  * '''This is not [[com.worxbend.codeberg4s.issues.Label]], and cannot be.''' A `Label` has an
  * [[com.worxbend.codeberg4s.issues.LabelId]] and a URL because it exists in a repository. A template label exists only
  * in a file the distribution ships: it has never been created anywhere, so it has no identifier to carry. Sharing one
  * type would mean an `Option[LabelId]` that is always empty on one of the two paths — the quiet lie
  * [[com.worxbend.codeberg4s.repositories.actions.ActionSecret]] refuses for the same reason.
  *
  * The colour is a [[com.worxbend.codeberg4s.issues.LabelColor]], which is the one thing the two models genuinely
  * share: the hexadecimal triplet a template seeds a repository's labels with is the same value the label endpoints
  * read back.
  *
  * '''Derived from `spec/swagger.v1.json`'s `LabelTemplate` definition, not from a captured response.''' No golden
  * fixture exists for either label-template endpoint.
  *
  * @param name
  *   the label's text. Required: a template entry with no name seeds nothing
  * @param color
  *   the background colour, absent when the instance sent something that is not a hexadecimal triplet — the same
  *   leniency [[com.worxbend.codeberg4s.issues.Label.color]] applies, and for the same reason
  * @param description
  *   the free-text explanation shown beside the label
  * @param isExclusive
  *   whether the seeded label would be exclusive within its `scope/` prefix
  */
final case class TemplateLabel(
    name: String,
    color: Option[LabelColor],
    description: Option[String],
    isExclusive: Boolean,
)
