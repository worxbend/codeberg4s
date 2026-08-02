package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.repositories.TagName

/** Everything `PATCH /repos/{owner}/{repo}/releases/{id}` may be told, as one value.
  *
  * Derived from `EditReleaseOption` in `spec/swagger.v1.json`, which declares the same seven properties as
  * `CreateReleaseOption` and marks '''none''' of them required. No golden capture of a release request exists.
  *
  * '''Every field is optional, and that is the whole semantics of the request.''' A `PATCH` leaves alone whatever the
  * body does not mention, so an unset field must not become a key — including the three booleans, which are
  * `Option[Boolean]` here and plain `Boolean` on [[CreateRelease]] for exactly that reason. `draft: false` published a
  * draft; not mentioning `draft` leaves it a draft. Those are different requests and the type has to be able to say
  * both.
  *
  * [[EditRelease.Empty]] renders as `{}`, which is a well-formed request that changes nothing.
  *
  * @param tagName
  *   move the release onto another tag
  * @param target
  *   the `target_commitish`; see [[CreateRelease.target]] for why it is a raw `String`
  * @param name
  *   a new release title
  * @param body
  *   new release notes, as Markdown source. Replaces the existing notes wholesale — there is no append
  * @param isDraft
  *   publish (`false`) or unpublish (`true`) the release
  * @param isPrerelease
  *   mark or unmark the release as not production-ready
  * @param hidesArchiveLinks
  *   show or hide the generated source archives
  */
final case class EditRelease(
    tagName: Option[TagName],
    target: Option[String],
    name: Option[String],
    body: Option[String],
    isDraft: Option[Boolean],
    isPrerelease: Option[Boolean],
    hidesArchiveLinks: Option[Boolean],
):

  /** Moves the release onto `tag`. */
  def retaggedTo(tag: TagName): EditRelease = copy(tagName = Some(tag))

  /** Sets the `target_commitish`. Forgejo only acts on this when it has to create the tag. */
  def onTarget(commitish: String): EditRelease = copy(target = Some(commitish))

  /** Replaces the release title. */
  def titled(title: String): EditRelease = copy(name = Some(title))

  /** Replaces the release notes wholesale. */
  def withNotes(markdown: String): EditRelease = copy(body = Some(markdown))

  /** States the draft flag. `draft(false)` publishes a draft; leaving it unset says nothing about it. */
  def draft(flag: Boolean): EditRelease = copy(isDraft = Some(flag))

  /** States the prerelease flag. */
  def prerelease(flag: Boolean): EditRelease = copy(isPrerelease = Some(flag))

  /** States whether the generated source archives are hidden. */
  def archiveLinksHidden(flag: Boolean): EditRelease = copy(hidesArchiveLinks = Some(flag))

  /** Whether this command would send an empty object, and therefore change nothing. */
  def isEmpty: Boolean =
    tagName.isEmpty && target.isEmpty && name.isEmpty && body.isEmpty && isDraft.isEmpty && isPrerelease.isEmpty
    && hidesArchiveLinks.isEmpty

object EditRelease:

  /** A command that mentions nothing. Every edit starts here. */
  val Empty: EditRelease =
    EditRelease(
      tagName           = None,
      target            = None,
      name              = None,
      body              = None,
      isDraft           = None,
      isPrerelease      = None,
      hidesArchiveLinks = None,
    )
