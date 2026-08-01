package com.worxbend.codeberg4s.notifications

/** What a [[NotificationThread]] is about.
  *
  * ==The URLs stay strings, on purpose==
  *
  * [[url]] and [[latestCommentUrl]] address an issue, a pull request or a commit depending on [[subjectType]], and
  * there is no captured payload to check that reading against. Parsing them into
  * [[com.worxbend.codeberg4s.issues.IssueNumber]] or a [[com.worxbend.codeberg4s.repositories.RepoSlug]] would mean
  * inventing a URL grammar this library has never seen an instance emit, and a self-hosted Forgejo behind a path prefix
  * would break it immediately. They are handed over exactly as received; a caller who knows their instance can parse
  * them, and this library does not pretend it can.
  *
  * ==[[state]] is a string for the same reason==
  *
  * The spec types it as `StateType`, another bare `{"type": "string"}` with no enum. Issues are `open`/`closed`, pull
  * requests add `merged`, and `golden/notification/list-synthetic.json` carries `"merged"` — which
  * [[com.worxbend.codeberg4s.issues.LifecycleState]] cannot represent and, being an issue lifecycle, should not have
  * to. Rather than force a third vocabulary on it, the raw value is preserved.
  *
  * ==Evidence==
  *
  * Every field here is derived from `definitions.NotificationSubject` in `spec/swagger.v1.json` and is '''unverified'''
  * against a live instance: notifications require a token, so the harvest could not reach them and
  * `golden/notification/list-synthetic.json` is hand-authored, as `golden/MANIFEST.md` records. This is the weakest
  * evidence in the library. Optionality in particular is a guess in the safe direction — `docs/HAZARDS.md` §1 measured
  * that no Forgejo response model declares anything required, so everything the domain can do without is optional here.
  *
  * @param subjectType
  *   the discriminator, and the only field this model insists on; see [[NotificationSubjectType]]
  * @param title
  *   the issue, pull-request or commit title, absent when the instance sent none
  * @param state
  *   the subject's own state as the instance spelled it — `open`, `closed`, `merged` — never normalised
  * @param url
  *   the API URL of the subject, verbatim
  * @param htmlUrl
  *   the browser URL of the subject, verbatim
  * @param latestCommentUrl
  *   the API URL of the most recent comment, verbatim. Forgejo sends `""` when there is none, which decodes as absent
  * @param latestCommentHtmlUrl
  *   the browser URL of the most recent comment, verbatim, with the same empty-string convention
  */
final case class NotificationSubject(
    subjectType: NotificationSubjectType,
    title: Option[String],
    state: Option[String],
    url: Option[String],
    htmlUrl: Option[String],
    latestCommentUrl: Option[String],
    latestCommentHtmlUrl: Option[String],
)
