package com.worxbend.codeberg4s.repositories

import java.time.Instant

/** A pointer to a commit or a tree, without the commit itself.
  *
  * Forgejo's `CommitMeta`, which is what it embeds wherever a full commit would be wasteful: a tag's target, a commit's
  * parents, a commit's tree. The only thing guaranteed to be there is the [[sha]], which is also the only thing needed
  * to fetch the rest.
  *
  * @param sha
  *   the object id
  * @param url
  *   the API URL of the referenced object, when the endpoint reports one
  * @param created
  *   the commit time, when the endpoint reports one. Forgejo sends its zero-time sentinel here on trees, and
  *   [[com.worxbend.codeberg4s.codec.Timestamps]] folds that into `None`
  */
final case class CommitRef(sha: CommitSha, url: Option[String], created: Option[Instant])
