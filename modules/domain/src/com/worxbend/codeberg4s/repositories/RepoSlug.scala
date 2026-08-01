package com.worxbend.codeberg4s.repositories

/** The `owner/name` pair that identifies a repository.
  *
  * A composite identifier deserves a real type: two loose strings let a caller swap them silently, and every repository
  * endpoint takes both. Both halves are already validated as path segments, so [[value]] is safe to render.
  */
final case class RepoSlug(owner: Owner, name: RepoName):

  /** The canonical `owner/name` rendering, as Forgejo displays it and as the API paths spell it. */
  def value: String = s"${owner.value}/${name.value}"
