package com.worxbend.codeberg4s.repositories

/** How often a tag's or a release's source archives have been downloaded.
  *
  * Counts the archives Forgejo generates from the tag, not the assets a maintainer uploaded — those carry their own
  * [[ReleaseAsset.downloadCount]].
  *
  * @param zip
  *   downloads of the `.zip` archive
  * @param tarGz
  *   downloads of the `.tar.gz` archive
  */
final case class ArchiveDownloadCount(zip: Long, tarGz: Long)
