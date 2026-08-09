package com.worxbend.codeberg4s.repositories.hooks

/** One Git hook of a repository — a script in the repository's `hooks` directory, not a webhook.
  *
  * Utterly distinct from [[Webhook]] despite sharing a route prefix: a webhook is an HTTP delivery Forgejo makes to
  * somewhere else, while a Git hook is a shell script the instance runs on its own machine when a push arrives. That
  * makes these endpoints administrative — Forgejo restricts them to site administrators and to instances that have not
  * disabled custom Git hooks, which arrives as a `403` and never as a [[com.worxbend.codeberg4s.ValidationError]].
  *
  * '''Derived from `spec/swagger.v1.json`'s `GitHook` definition, not from a captured response'''; see [[Webhook]] for
  * why no fixture exists.
  *
  * @param name
  *   the Git hook's name — `pre-receive`, `update`, `post-receive` — which is also how the routes address it
  * @param isActive
  *   whether the hook has content and will run. Absent means the instance did not say, which is not the same as `false`
  * @param content
  *   the script itself, verbatim. Absent for a hook that has none, which is what an inactive hook usually is. '''Not
  *   base64''' — the spec declares it a plain string, unlike a wiki page's content
  */
final case class GitHook private[codeberg4s] (
    name: GitHookName,
    isActive: Option[Boolean],
    content: Option[String],
)
