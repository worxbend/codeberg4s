package com.worxbend.codeberg4s

/** The `/repos` path prefixes every repository-scoped API in the library builds on, so that
  * `/repos/{owner}/{repo}` is spelled once rather than in every package that hangs a route off it.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]]. That companion is deliberately free of endpoint vocabulary — it
  * takes a `List[String]` and knows nothing about repositories — which is why the naming of the segments lives here
  * instead.
  *
  * Segments are unencoded by contract: [[com.worxbend.codeberg4s.core.CodebergRequest.path]] is percent-encoded at the
  * transport boundary, so reading `.value` off an already-validated [[Owner]] or [[RepoName]] is the correct thing to
  * hand over.
  *
  * Internal to the library.
  */
private[codeberg4s] object RepositoryRequests:

  /** The collection segment `/repos`, which is both a path of its own and the prefix of every repository path. */
  val ReposSegment: String = "repos"

  /** The `/repos` collection root, for the operations that address no single repository — search and migrate. */
  def reposPath: List[String] =
    List(ReposSegment)

  /** The `/repos/{owner}/{repo}` prefix. */
  def repositoryPath(owner: Owner, name: RepoName): List[String] =
    List(ReposSegment, owner.value, name.value)
