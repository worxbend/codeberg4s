package com.worxbend.codeberg4s.repositories.gitdata

/** One entry of `GET /repos/{owner}/{repo}/git/refs` — a ref and the object it points at.
  *
  * Forgejo calls this `Reference`. The name it reports is always fully qualified, `refs/heads/main` rather than `main`,
  * which is why [[name]] is a [[RefName]]: the value is immediately usable as the argument of another call.
  *
  * ==A ref is not a commit==
  *
  * [[target]] carries the object the ref resolves to '''and its kind'''. For a branch that is a commit; for an
  * annotated tag it is a tag object, and the commit is one dereference further away — `GET /git/tags/{sha}` on
  * [[target]]'s sha yields an [[AnnotatedTag]] whose own target is the commit. Treating every ref's sha as a commit id
  * is the classic mistake here, and the [[GitObjectKind]] on [[target]] is what makes it avoidable.
  *
  * @param name
  *   the fully qualified ref name
  * @param url
  *   the API URL of the ref, when the endpoint reports one
  * @param target
  *   the object the ref points at, absent when the instance sent no usable `object`
  */
final case class GitReference(name: RefName, url: Option[String], target: Option[GitObjectRef])
