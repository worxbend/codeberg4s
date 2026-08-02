package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.PathSegment

/** The instance-wide identifier of one webhook — the `{id}` of `/repos/{owner}/{repo}/hooks/{id}`.
  *
  * `spec/swagger.v1.json` declares it `type: integer, format: int64` on the `GET`, `PATCH`, `DELETE` and test routes,
  * and the `Hook` model reports the same value as `id`. It is a database row id, so it is instance-wide and not
  * per-repository: a hook id from one repository addresses nothing in another, and the response to that mistake is a
  * `404` that reads like a missing repository.
  *
  * That the id is never reused is what lets [[RepositoryHookApi.edit]] and [[RepositoryHookApi.delete]] be retried; see
  * their Scaladoc for the argument.
  */
opaque type HookId = Long

object HookId:

  private val MinValue: Long = 1L

  /** Parses a webhook identifier.
    *
    * Rejects anything below `1`. A number cannot forge a path, so this is a confusion guard rather than an escaping
    * one: a hook id, a repository id and an issue index are all `Long` and all plausible values for one another.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"hookId"` field
    */
  def from(value: Long): Either[ValidationError, HookId] =
    if value < MinValue then Left(ValidationError("hookId", s"must be at least $MinValue"))
    else Right(value)

  extension (id: HookId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id

/** The name of a Git hook — the `{id}` of `/repos/{owner}/{repo}/hooks/git/{id}`.
  *
  * '''A string, not a number, and that is the spec's own choice.''' The Git-hook routes declare `id` as `type: string`
  * while the webhook routes declare it as `int64`, because a Git hook is not a row in a table: it is a file in the
  * repository's `hooks` directory, addressed by the name Git itself gives it — `pre-receive`, `update`, `post-receive`.
  * The set of names is fixed by Git, not by Forgejo, which is why this type validates the value as a path segment
  * rather than enumerating it: a Git version that adds a hook must not be a version this library refuses to address.
  *
  * Distinct from [[HookId]] on purpose. Passing one where the other belongs cannot compile.
  */
opaque type GitHookName = String

object GitHookName:

  /** Parses a Git hook name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, and a value containing a
    * control character — see [[com.worxbend.codeberg4s.repositories.PathSegment]] for why that is a security boundary
    * and not a convenience.
    *
    * @return
    *   the name, or a [[ValidationError]] on the `"gitHookName"` field
    */
  def from(value: String): Either[ValidationError, GitHookName] =
    PathSegment.from("gitHookName", value)

  extension (name: GitHookName)

    /** The name as a string, ready to be used as one path segment. */
    def value: String = name
