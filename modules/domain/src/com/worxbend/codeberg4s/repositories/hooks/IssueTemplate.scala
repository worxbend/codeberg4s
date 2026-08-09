package com.worxbend.codeberg4s.repositories.hooks

import java.util.Locale

/** One issue template a repository offers, as `GET /repos/{owner}/{repo}/issue_templates` lists them.
  *
  * Like [[IssueConfig]] this is a file in the repository rather than a database row: Forgejo reads the contents of
  * `.forgejo/ISSUE_TEMPLATE` off the default branch and reports what it found. A template is either '''Markdown''' — a
  * body a contributor edits, which arrives in [[content]] — or a '''form''' — a list of fields a contributor fills in,
  * which arrives in [[fields]]. The two are mutually exclusive in practice, and which one a template is can be told by
  * asking whether [[fields]] is empty.
  *
  * '''Derived from `spec/swagger.v1.json`'s `IssueTemplate` definition, not from a captured response'''; see
  * [[Webhook]] for why no fixture exists.
  *
  * @param fileName
  *   the template's file name within `ISSUE_TEMPLATE`, which is what identifies it. Required: a template that cannot be
  *   named is one nobody can select
  * @param name
  *   the template's display name
  * @param about
  *   the sentence shown under the name, explaining when to use this template
  * @param title
  *   the issue title this template pre-fills
  * @param content
  *   the Markdown body this template pre-fills, for a template that is not a form
  * @param labels
  *   the labels applied to an issue opened from this template, in file order
  * @param ref
  *   the Git reference an issue opened from this template targets, when the template names one
  * @param fields
  *   the form fields, in file order, for a template that is a form. Empty for a Markdown template
  */
final case class IssueTemplate private[codeberg4s] (
    fileName: String,
    name: Option[String],
    about: Option[String],
    title: Option[String],
    content: Option[String],
    labels: Vector[String],
    ref: Option[String],
    fields: Vector[IssueFormField],
):

  /** Whether this template is a form rather than a Markdown body. */
  def isForm: Boolean = fields.nonEmpty

/** One field of an issue form template.
  *
  * ==Why `attributes` and `validations` are maps of strings==
  *
  * `spec/swagger.v1.json` declares both as `type: object` with `additionalProperties: {}` — an object whose values may
  * be '''anything''', with no schema at all. There is nothing to model, and the `domain` module depends on nothing but
  * the standard library and so cannot hold a JSON tree. Each value is therefore kept as text under this rule, applied
  * by [[com.worxbend.codeberg4s.repositories.hooks.wire.IssueFormFieldDto]]:
  *
  *   - a value that is a JSON string is unwrapped, so `label` reads as `Bug report` and not as `"Bug report"`;
  *   - any other value keeps its compact JSON rendering, so `options` reads as `["v1","v2"]` and `required` as `true`.
  *
  * The one ambiguity that leaves is worth naming rather than hiding: a string whose own text is valid JSON reads the
  * same as the value it spells. Nothing is ever dropped, and a caller who needs the distinction has the field's
  * [[IssueFormField.fieldType]] to tell them which shape to expect.
  *
  * '''Derived from the spec, not from a captured response'''; see [[Webhook]].
  *
  * @param id
  *   the field's identifier, which is the key its answer appears under in the opened issue
  * @param fieldType
  *   what kind of control this is; see [[IssueFormFieldType]]
  * @param attributes
  *   the field's presentation — `label`, `description`, `placeholder`, `options` — read as described above
  * @param validations
  *   the field's constraints — `required`, `is_number`, `regex` — read the same way
  * @param visible
  *   where the field's answer is shown, as the instance spelled it. Kept as plain strings because the spec's
  *   `IssueFormFieldVisible` is a bare `type: string` that enumerates nothing, and inventing a vocabulary here would be
  *   exactly the guesswork `docs/HAZARDS.md` §1 warns against
  */
final case class IssueFormField private[codeberg4s] (
    id: Option[String],
    fieldType: Option[IssueFormFieldType],
    attributes: Map[String, String],
    validations: Map[String, String],
    visible: Vector[String],
)

/** What kind of control one [[IssueFormField]] is.
  *
  * The five named cases are the spec's own: `IssueFormFieldType` carries no `enum`, but its `title` reads
  * `IssueFormFieldType defines issue form field type, can be "markdown", "textarea", "input", "dropdown" or
  * "checkboxes"`. That is a documented vocabulary rather than a validated one, so [[IssueFormFieldType.Other]] exists
  * for the same reason it does on [[HookEvent]]: a type a later Forgejo release adds must cost the caller nothing.
  */
enum IssueFormFieldType:

  /** Static Markdown shown to the contributor, with nothing to fill in — `markdown`. */
  case Markdown

  /** A multi-line text box — `textarea`. */
  case Textarea

  /** A single-line text box — `input`. */
  case Input

  /** A list of choices — `dropdown`. */
  case Dropdown

  /** A list of independent tick boxes — `checkboxes`. */
  case Checkboxes

  /** A field type this library does not recognise, kept exactly as the instance spelled it. */
  case Other(name: String)

object IssueFormFieldType:

  /** Reads the wire spelling. Total, trimming and case-insensitive; see [[HookEvent.parse]] for the argument. */
  def parse(value: String): IssueFormFieldType =
    value.trim.toLowerCase(Locale.ROOT) match
      case "markdown"   => Markdown
      case "textarea"   => Textarea
      case "input"      => Input
      case "dropdown"   => Dropdown
      case "checkboxes" => Checkboxes
      case unrecognised => Other(unrecognised)

  extension (fieldType: IssueFormFieldType)

    /** The wire spelling. */
    def wireValue: String =
      fieldType match
        case Markdown    => "markdown"
        case Textarea    => "textarea"
        case Input       => "input"
        case Dropdown    => "dropdown"
        case Checkboxes  => "checkboxes"
        case Other(name) => name
