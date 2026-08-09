package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.ArchiveDownloadCount
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.CommitVerification
import com.worxbend.codeberg4s.repositories.GitIdentity
import com.worxbend.codeberg4s.repositories.TagName

/** A Git tag '''object''' — `GET /repos/{owner}/{repo}/git/tags/{sha}`.
  *
  * ==Not [[com.worxbend.codeberg4s.repositories.Tag]]==
  *
  * That model is what `GET /repos/{owner}/{repo}/tags` lists: a ref, with whatever the tag points at flattened into it,
  * and it covers lightweight and annotated tags alike. This model is the annotated tag object itself — a real object in
  * the Git database with its own [[sha]], its own [[tagger]] and its own signature. A lightweight tag has no such
  * object, so this endpoint answers `404` for one; that is the endpoint's contract and not a bug.
  *
  * The consequence worth stating: [[sha]] is the id of the '''tag object''' and [[target]]`.sha` is the id of the
  * commit. They are different values, and using the first where a commit id is wanted resolves to nothing.
  *
  * @param name
  *   the tag's short name, the `tag` key — `v1.2.0`, not `refs/tags/v1.2.0`
  * @param sha
  *   the object id of the tag object itself
  * @param target
  *   the object the tag points at, normally a commit; absent when the instance sent no usable `object`
  * @param message
  *   the annotation, which is the reason an annotated tag exists at all
  * @param tagger
  *   who created the tag, as Git records it
  * @param verification
  *   the instance's signature verdict for the tag object, when it reports one
  * @param archiveDownloads
  *   how often the tag's generated archives were downloaded, when the instance counts them
  * @param url
  *   the API URL of the tag object, when the endpoint reports one
  */
final case class AnnotatedTag private[codeberg4s] (
    name: TagName,
    sha: CommitSha,
    target: Option[GitObjectRef],
    message: Option[String],
    tagger: Option[GitIdentity],
    verification: Option[CommitVerification],
    archiveDownloads: Option[ArchiveDownloadCount],
    url: Option[String],
)
