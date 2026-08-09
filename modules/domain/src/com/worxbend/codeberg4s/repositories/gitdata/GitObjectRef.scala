package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.repositories.CommitSha

/** A Git object addressed by id, with the kind of thing it is.
  *
  * Forgejo's `GitObject`, embedded as the `object` of a [[GitReference]] and — under the name `AnnotatedTagObject`,
  * with an identical shape — as the `object` of an [[AnnotatedTag]]. One type covers both: the two definitions differ
  * in name only, and forging a second identical model is the duplication `docs/LEDGER.md` forbids.
  *
  * This is not [[com.worxbend.codeberg4s.repositories.CommitRef]], which is Forgejo's `CommitMeta` and carries a
  * timestamp instead of a kind. The two are genuinely different objects on the wire.
  *
  * @param sha
  *   the object id the ref resolves to
  * @param kind
  *   what that id points at, absent when the instance sent a spelling this library does not recognise; see
  *   [[GitObjectKind.parse]]
  * @param url
  *   the API URL of the object, when the endpoint reports one
  */
final case class GitObjectRef private[codeberg4s] (sha: CommitSha, kind: Option[GitObjectKind], url: Option[String])
