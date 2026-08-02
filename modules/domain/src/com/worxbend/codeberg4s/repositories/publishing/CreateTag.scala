package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.repositories.TagName

/** Everything `POST /repos/{owner}/{repo}/tags` may be told, as one value.
  *
  * Derived from `CreateTagOption` in `spec/swagger.v1.json`: `tag_name` required, `message` and `target` optional. No
  * golden capture of a tag request exists; the '''response''' is a `Tag`, whose shape is captured in
  * `golden/repository/tags-list.json`.
  *
  * '''Whether the created tag is lightweight or annotated is decided by [[message]] alone.''' Forgejo creates an
  * annotated tag when a message is supplied and a lightweight one when it is not, and the listing endpoint reports the
  * difference the same way — [[com.worxbend.codeberg4s.repositories.Tag.message]] present or absent. There is no
  * separate flag to get wrong.
  *
  * @param tagName
  *   the tag to create; the one thing Forgejo requires
  * @param message
  *   the annotation. Supplying it makes the tag annotated; omitting it makes it lightweight
  * @param target
  *   what to tag: a branch name, another tag, or a commit id. Absent means the repository's default branch. A raw
  *   `String` for the reason [[CreateRelease.target]] gives
  */
final case class CreateTag(tagName: TagName, message: Option[String], target: Option[String]):

  /** Makes the tag annotated, carrying `text`. */
  def annotated(text: String): CreateTag = copy(message = Some(text))

  /** Tags `commitish` — a branch, a tag or a commit id — instead of the default branch. */
  def at(commitish: String): CreateTag = copy(target = Some(commitish))

object CreateTag:

  /** Starts a command from the only thing Forgejo insists on. Cannot fail; see [[CreateRelease.of]]. */
  def of(tag: TagName): CreateTag =
    CreateTag(tagName = tag, message = None, target = None)
