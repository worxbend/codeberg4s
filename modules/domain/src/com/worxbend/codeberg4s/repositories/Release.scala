package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** A release, as `GET /repos/{owner}/{repo}/releases` and `GET /repos/{owner}/{repo}/releases/{id}` report it.
  *
  * A release is an annotation on a tag: [[tagName]] is what it is attached to, and deleting the release leaves the tag.
  * [[isDraft]] and [[isPrerelease]] are independent, which is why they are two flags rather than a lifecycle enum —
  * Forgejo allows a prerelease draft, and a caller filtering "what should users see" must check both.
  *
  * @param id
  *   the instance-local identifier, and what `GET /releases/{id}` takes
  * @param tagName
  *   the tag this release annotates
  * @param targetCommitish
  *   the branch or commit the tag was created from, when Forgejo still records it
  * @param name
  *   the release title, absent when the maintainer left it empty
  * @param body
  *   the release notes, in Markdown, absent when empty. Routinely large — the `forgejo/forgejo` captures run to several
  *   kilobytes
  * @param url
  *   the API URL of the release
  * @param htmlUrl
  *   the browser URL of the release
  * @param tarballUrl
  *   the generated `.tar.gz` source archive
  * @param zipballUrl
  *   the generated `.zip` source archive
  * @param uploadUrl
  *   where an authorised caller would POST a new asset
  * @param isDraft
  *   whether the release is unpublished and visible only to maintainers
  * @param isPrerelease
  *   whether the release is marked as not production-ready
  * @param hidesArchiveLinks
  *   whether the instance hides the generated source archives on the release page
  * @param createdAt
  *   when the release was created; for a published release this is the tag's own time
  * @param publishedAt
  *   when it became visible, absent while it is a draft
  * @param author
  *   the account that published it, when the instance reports one
  * @param assets
  *   the uploaded attachments, empty when there are none
  * @param archiveDownloads
  *   how often the generated source archives have been downloaded, when the instance reports it
  */
final case class Release(
    id: ReleaseId,
    tagName: TagName,
    targetCommitish: Option[String],
    name: Option[String],
    body: Option[String],
    url: Option[String],
    htmlUrl: Option[String],
    tarballUrl: Option[String],
    zipballUrl: Option[String],
    uploadUrl: Option[String],
    isDraft: Boolean,
    isPrerelease: Boolean,
    hidesArchiveLinks: Boolean,
    createdAt: Option[Instant],
    publishedAt: Option[Instant],
    author: Option[User],
    assets: Vector[ReleaseAsset],
    archiveDownloads: Option[ArchiveDownloadCount],
)
