package com.worxbend.codeberg4s.repositories.hooks

/** A repository's issue configuration, as `GET /repos/{owner}/{repo}/issue_config` reports it.
  *
  * This is not a setting stored in the database: it is the `.forgejo/ISSUE_TEMPLATE/config.yaml` file (or one of its
  * several accepted spellings) read out of the repository's default branch. That is why the companion route
  * [[RepositoryIssueConfigApi.validate]] exists at all — a file a human wrote can be malformed, and the API offers a
  * way to ask whether it parsed.
  *
  * '''Derived from `spec/swagger.v1.json`'s `IssueConfig` definition, not from a captured response'''; see [[Webhook]]
  * for why no fixture exists.
  *
  * @param blankIssuesEnabled
  *   whether a contributor may open an issue without choosing a template. Absent means the instance did not say, which
  *   is not the same as `false` — a repository with no config at all answers with the instance's defaults
  * @param contactLinks
  *   the alternatives offered alongside the templates, in the order the file lists them. Empty when the payload carried
  *   no `contact_links` key, `null`, or an empty array
  */
final case class IssueConfig(
    blankIssuesEnabled: Option[Boolean],
    contactLinks: Vector[IssueContactLink],
)

/** One entry of an issue config's `contact_links` — somewhere other than the issue tracker to take a question.
  *
  * '''Derived from the spec, not from a captured response'''; see [[Webhook]].
  *
  * @param name
  *   the label shown to a contributor, required: a link with no label cannot be rendered by anyone
  * @param url
  *   where the link goes, required for the same reason
  * @param about
  *   the sentence explaining when to use this link instead of an issue
  */
final case class IssueContactLink(
    name: String,
    url: String,
    about: Option[String],
)

/** The verdict of `GET /repos/{owner}/{repo}/issue_config/validate`.
  *
  * '''A `200` here does not mean the config is valid.''' The route answers `200` with `{"valid": false, "message":
  * "..."}` for a config it could not parse — invalidity is data, not a failure — so a caller must read [[isValid]]
  * rather than treat a successful call as a pass. Only a missing repository is a `404`.
  *
  * '''Derived from the spec, not from a captured response'''; see [[Webhook]].
  *
  * @param isValid
  *   whether Forgejo could parse the repository's issue config. Required: a validation result with no verdict is not a
  *   validation result, so a payload without it is a decoding failure rather than a silent `false`
  * @param message
  *   what was wrong, when something was. Absent for a valid config
  */
final case class IssueConfigValidation(
    isValid: Boolean,
    message: Option[String],
)
