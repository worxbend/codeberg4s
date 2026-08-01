package com.worxbend.codeberg4s.repositories

/** A Git tag, as `GET /repos/{owner}/{repo}/tags` reports it.
  *
  * Forgejo lists lightweight and annotated tags together and does not say which is which. [[message]] is the only
  * evidence: an annotated tag has one, a lightweight tag does not.
  *
  * @param name
  *   the tag name
  * @param message
  *   the annotation message, absent for a lightweight tag
  * @param commitSha
  *   the object the tag points at, from the wire's `id`. Equal to `commit.sha`; both are kept because the wire sends
  *   both and a caller that only needs the id should not have to reach through [[commit]]
  * @param commit
  *   a pointer to the tagged commit, when the endpoint reports one
  * @param zipballUrl
  *   where the generated `.zip` source archive can be downloaded
  * @param tarballUrl
  *   where the generated `.tar.gz` source archive can be downloaded
  * @param archiveDownloads
  *   how often those archives have been downloaded, when the instance reports it
  */
final case class Tag(
    name: TagName,
    message: Option[String],
    commitSha: CommitSha,
    commit: Option[CommitRef],
    zipballUrl: Option[String],
    tarballUrl: Option[String],
    archiveDownloads: Option[ArchiveDownloadCount],
)
