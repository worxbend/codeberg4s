package com.worxbend.codeberg4s.miscellaneous

import com.worxbend.codeberg4s.ValidationError

/** The name of one instance-level template — the `{name}` of `/gitignore/templates/{name}`, `/licenses/{name}` and
  * `/label/templates/{name}`.
  *
  * One type for all three because all three are the same thing: a file name inside the Forgejo distribution's
  * `options/` directory, echoed back as the identifier of a template. Giving each endpoint its own type would triple
  * the validation without separating anything a caller could confuse — a gitignore template and a license template are
  * never both plausible arguments to the same method.
  *
  * ==What is allowed, and why it is wider than every other path type here==
  *
  * [[com.worxbend.codeberg4s.Owner]] and [[com.worxbend.codeberg4s.organizations.OrgName]] name accounts, whose
  * spelling Forgejo restricts. This names a file the distribution ships, and those file names are prose:
  * `Academic Free License v3.0` is a license template, so a validator that rejected a space would refuse a name the
  * `/licenses` listing itself hands back. Spaces are therefore accepted and percent-encoded on the wire.
  *
  * '''A `/` is accepted too, and is sent as `%2F`.''' The name occupies exactly one path segment — the routes declare
  * one `{name}` parameter, not a wildcard — so a name carrying a slash is encoded rather than split, which is the only
  * encoding that keeps "one name" and "one parameter" the same thing. Whether a given instance's router then decodes it
  * back is the instance's business; `golden/misc/gitignore-templates.json` contains 297 template names and not one of
  * them carries a slash or a space, so this is a defensive allowance rather than a measured need.
  *
  * ==What is rejected==
  *
  * A blank value, a control character, and any `.` or `..` part. The last is the one that matters: `.` and `..` survive
  * percent-encoding untouched in some routers, so a name carrying one could walk out of the route it was meant for.
  * That is a security boundary and not a convenience, exactly as [[com.worxbend.codeberg4s.Owner]]'s slash rule is.
  */
opaque type TemplateName = String

object TemplateName:

  /** The field name a rejected value is reported under, stable enough for a caller to branch on. */
  private val Field: String = "templateName"

  /** Parses a template name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing a control character, and a value
    * with a `.` or `..` part. Spaces and slashes are accepted; see the type's note for what happens to them on the
    * wire.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"templateName"` field
    */
  def from(value: String): Either[ValidationError, TemplateName] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else if trimmed.split('/').exists(isTraversal) then
      Left(ValidationError(Field, "must not contain a '.' or '..' part"))
    else Right(trimmed)

  private def isTraversal(part: String): Boolean =
    part match
      case "." | ".." => true
      case _ => false

  extension (name: TemplateName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name
