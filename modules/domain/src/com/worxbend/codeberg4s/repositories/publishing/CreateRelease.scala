package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.repositories.TagName

/** Everything `POST /repos/{owner}/{repo}/releases` may be told, as one value.
  *
  * Derived from `CreateReleaseOption` in `spec/swagger.v1.json`, which declares exactly one required property —
  * `tag_name` — and six optional ones. No golden capture of a release '''request''' exists, because the harvest was
  * anonymous and could only read; the '''response''' shape is captured, and that is
  * [[com.worxbend.codeberg4s.repositories.Release]].
  *
  * A command type rather than a seven-parameter method: five of the seven are optional and three of those are booleans,
  * so a call site passing `(tag, None, None, Some(notes), false, true, false)` says nothing a reader can check. Built
  * by naming what should be set:
  *
  * {{{
  * val command = CreateRelease.of(tag).titled("v16.0.2").withNotes(changelog).asPrerelease
  * }}}
  *
  * '''Only what is set is sent.''' An unset field contributes no JSON key, so the instance applies its own default
  * rather than this library's idea of one; the three flags contribute a key only when `true`, since `false` is what
  * Forgejo assumes for all three.
  *
  * '''Creating a release also creates the tag''' when [[tagName]] does not yet exist and [[target]] says where to put
  * it. That is Forgejo behaviour, not something this command controls, and it is the reason a repeat of this call is
  * not merely a duplicate release — see `RepositoryPublishingApi.createRelease`, which never retries.
  *
  * @param tagName
  *   the tag the release annotates; the one thing Forgejo requires
  * @param target
  *   the `target_commitish`: a branch name, tag name or commit id the tag should be created from, used only when the
  *   tag does not already exist. A raw `String` rather than a [[com.worxbend.codeberg4s.repositories.BranchName]] or
  *   [[com.worxbend.codeberg4s.repositories.CommitSha]] because it is genuinely any of the three and it never reaches a
  *   request path — narrowing it would be a lie in the other direction
  * @param name
  *   the release title. Absent leaves Forgejo to fall back on the tag name
  * @param body
  *   the release notes, as Markdown source
  * @param isDraft
  *   whether the release is created unpublished, visible only to maintainers
  * @param isPrerelease
  *   whether the release is marked as not production-ready. Independent of [[isDraft]]; Forgejo allows both at once
  * @param hidesArchiveLinks
  *   whether the generated `.zip` and `.tar.gz` source archives are hidden on the release page
  */
final case class CreateRelease(
    tagName: TagName,
    target: Option[String],
    name: Option[String],
    body: Option[String],
    isDraft: Boolean,
    isPrerelease: Boolean,
    hidesArchiveLinks: Boolean,
):

  /** Creates the tag from `commitish` — a branch, a tag or a commit id — when it does not already exist. */
  def onTarget(commitish: String): CreateRelease = copy(target = Some(commitish))

  /** Gives the release a title distinct from its tag name. */
  def titled(title: String): CreateRelease = copy(name = Some(title))

  /** Sets the release notes, as Markdown source. */
  def withNotes(markdown: String): CreateRelease = copy(body = Some(markdown))

  /** Creates the release unpublished. Combines with [[asPrerelease]]. */
  def asDraft: CreateRelease = copy(isDraft = true)

  /** Marks the release as not production-ready. Combines with [[asDraft]]. */
  def asPrerelease: CreateRelease = copy(isPrerelease = true)

  /** Hides the generated source archives on the release page. */
  def hidingArchiveLinks: CreateRelease = copy(hidesArchiveLinks = true)

object CreateRelease:

  /** Starts a command from the only thing Forgejo insists on.
    *
    * Cannot fail: [[com.worxbend.codeberg4s.repositories.TagName]] has already rejected everything this command could
    * check, so there is no second validation to repeat here.
    */
  def of(tag: TagName): CreateRelease =
    CreateRelease(
      tagName           = tag,
      target            = None,
      name              = None,
      body              = None,
      isDraft           = false,
      isPrerelease      = false,
      hidesArchiveLinks = false,
    )
