# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog][kac], and this project adheres to
[Semantic Versioning][semver].

[kac]: https://keepachangelog.com/en/1.1.0/
[semver]: https://semver.org/spec/v2.0.0.html

## [Unreleased]

### Added

- **`PageWalk.attempt.all` / `.fold` / `.foreach`** — the page walk on the
  `.attempt` rail. They take an `.attempt` listing
  (`PageParams => Future[Either[CodebergError, Page[A]]]`) and answer
  `Future[Either[CodebergError, B]]`, stopping at the first `Left` and reporting
  a walk that hit `PageWalk.MaxPages` as `Left(WalkTruncated(...))`. Until now
  the one non-trivial helper in the library existed only on the exception rail,
  so an `.attempt` caller had to hand-write the loop the helper exists to state
  once. Both rails run that single loop — the plain `PageWalk.all` / `fold` /
  `foreach` are now the `.attempt` loop with a `Left` turned back into a failed
  `Future`, and their behaviour is unchanged.

### Changed

- **The Actions runners, secrets and variables moved to
  `client.repos.actions.config`.** `client.repos.actions` held 28 operations
  covering two different things: the record of what Actions has done — runs,
  jobs, tasks, artifacts — and the capacity and configuration it runs on. The
  fourteen operations of the second kind (`runners`, `runner`, `registerRunner`,
  `deleteRunner`, `runnerRegistrationToken`, `searchRunnerJobs`, `secrets`,
  `setSecret`, `deleteSecret`, `variables`, `variable`, `createVariable`,
  `updateVariable`, `deleteVariable`) are now on `RepositoryActionConfigApi`,
  reached as `client.repos.actions.config`, with their names, arguments,
  endpoints, operation ids and retry decisions unchanged. They are the same
  fourteen operations `client.organizations.actions` serves one scope higher.

- **`client.organizations` membership moved to `client.organizations.members`.**
  Thirteen operations — `members`, `publicMembers`, `isMember`,
  `isPublicMember`, `removeMember`, `publicizeMember`, `concealMember`,
  `blockedUsers`, `blockUser`, `unblockUser`, `userOrganizations`,
  `currentUserOrganizations`, `userPermissions` — are now on
  `OrganizationMemberApi`, reached as `client.organizations.members`, with their
  names, arguments, endpoints, operation ids and retry decisions unchanged.
  `client.organizations` keeps the organisation resource itself, its
  repositories, its teams and its activity feed. The new group answers one
  question from both ends: who is in this organisation, and which organisations
  is this account in.

- **`client.repos.admin` split into five groups.** It held 44 operations, more
  than any other group in the library, covering everything from creating a
  repository to reading its languages. Thirty of them moved to four nested
  groups, reached from the same place and with their names, arguments, endpoints
  and retry decisions unchanged:

  | Now on | Operations |
  | --- | --- |
  | `client.repos.admin.mirrors` | `syncMirror`, `pushMirrors`, `pushMirror`, `addPushMirror`, `deletePushMirror`, `syncPushMirrors`, `forkSyncInfo`, `branchForkSyncInfo`, `syncFork`, `syncForkBranch` |
  | `client.repos.admin.contents` | `contents`, `createFile`, `updateFile`, `deleteFile`, `changeFiles` |
  | `client.repos.admin.watchers` | `subscription`, `watch`, `unwatch`, `assignees`, `reviewers`, `stargazers`, `subscribers` |
  | `client.repos.admin.insights` | `activityFeed`, `languages`, `newPinAllowed`, `pinnedIssues`, `signingKey`, `trackedTimes`, `trackedTimesFor`, `searchTopics` |

  `client.repos.admin` keeps the repository's own lifecycle: `create`, `byId`,
  `edit`, `delete`, `migrate`, the three transfer calls, `convert`, the three
  branch calls and the two avatar calls. The `.attempt` rail moved with each
  group — `client.repos.admin.attempt.updateFile` is now
  `client.repos.admin.contents.attempt.updateFile` — and every operation id is
  unchanged, so alerts keyed on `repos.admin.contents.update` and the rest keep
  firing on exactly what they fired on before.

- **The pull-request reviews moved to a group of their own.** The thirteen
  review operations that used to sit on `client.pulls` are now on
  `client.pulls.reviews`, a `PullRequestReviewApi` reached from the same place
  every other nested group is reached from, and they lost the `Review` prefix
  their names carried only to keep them apart from the rest of the class:

  | Before | Now |
  | --- | --- |
  | `client.pulls.reviews(owner, name, number, params)` | `client.pulls.reviews.list(owner, name, number, params)` |
  | `client.pulls.requestReviews(...)` | `client.pulls.reviews.request(...)` |
  | `client.pulls.removeReviewRequests(...)` | `client.pulls.reviews.removeRequests(...)` |
  | `client.pulls.createReview(...)` | `client.pulls.reviews.create(...)` |
  | `client.pulls.getReview(...)` | `client.pulls.reviews.get(...)` |
  | `client.pulls.submitReview(...)` | `client.pulls.reviews.submit(...)` |
  | `client.pulls.deleteReview(...)` | `client.pulls.reviews.delete(...)` |
  | `client.pulls.dismissReview(...)` | `client.pulls.reviews.dismiss(...)` |
  | `client.pulls.undismissReview(...)` | `client.pulls.reviews.undismiss(...)` |
  | `client.pulls.reviewComments(...)` | `client.pulls.reviews.comments(...)` |
  | `client.pulls.createReviewComment(...)` | `client.pulls.reviews.createComment(...)` |
  | `client.pulls.getReviewComment(...)` | `client.pulls.reviews.getComment(...)` |
  | `client.pulls.deleteReviewComment(...)` | `client.pulls.reviews.deleteComment(...)` |

  The `.attempt` rail moved with them — `client.pulls.attempt.getReview` is now
  `client.pulls.reviews.attempt.get` — and the operation ids in every failure's
  `CallContext` are unchanged, so alerts keyed on `pulls.reviews.get` and the
  rest keep firing on exactly what they fired on before. The endpoints called,
  the retry eligibility of each, and the models returned are all unchanged: this
  is where the operations live, not what they do.

- **Organisation label listing takes a typed query.**
  `client.organizations.labels.list` used to take its ordering as a bare
  `Option[OrganizationLabelSort]` sitting between the organisation handle and
  the paging window. It now takes an `OrganizationLabelQuery`, the same shape of
  argument repository and account search take, so every filtered listing in the
  library is asked the same way and a parameter Forgejo adds to
  `GET /orgs/{org}/labels` later can go into the query rather than into the
  method signature.

  `OrganizationLabelQuery.Empty` sets nothing — the organisation's labels in
  whatever order the instance returns them — and `OrganizationLabelQuery.of`
  builds one around an ordering; `sortedBy` replaces it. An unset ordering still
  sends no `sort` parameter at all, because `sort=` is not a value the
  endpoint's `enum` contains.

  BREAKING CHANGE: `OrganizationLabelApi.list`, on both the exception rail and
  the `.attempt` rail, takes an `OrganizationLabelQuery` instead of an
  `Option[OrganizationLabelSort]`. Rewrite
  `client.organizations.labels.list(org, None, params)` as
  `client.organizations.labels.list(org, OrganizationLabelQuery.Empty, params)`,
  and `list(org, Some(OrganizationLabelSort.MostIssues), params)` as
  `list(org, OrganizationLabelQuery.of(OrganizationLabelSort.MostIssues), params)`.

- **Repository and account search take a typed query.** `client.repos.search`
  and `client.users.search` used to take the keyword as a bare `String`, which
  was the only thing about either search a caller could say. Between them the
  two endpoints declare eighteen parameters; sixteen of them were unreachable.
  Both operations now take a query object built the way every other request
  model in this library is built — a case class with a validating companion —
  and both render their query string in `modules/codec`, beside the other
  `*Queries` objects, so each wire spelling is written exactly once.

  `RepositorySearchQuery` carries the keyword plus the fifteen filters
  `GET /repos/search` declares: the topic and description switches, the four
  account and team ids, the three-valued `private` / `is_private` / `template` /
  `archived` filters, the repository `mode`, `exclusive`, and the `sort`
  attribute with its `order`. The last three are the new `RepositorySearchMode`,
  `RepositorySearchSort` and `SortDirection` enums, so a misspelled sort word is
  now a compile error instead of a `422`. `UserSearchQuery` carries the keyword,
  the account `uid` and the new `UserSearchSort` ordering.

  Both companions expose `Empty` — every filter unset — and `of(keyword)`, which
  trims the keyword, rejects a control character as a
  `ValidationError` on the `"text"` field, and treats an all-blank keyword as no
  keyword at all, since omitting `q` and sending `q=` ask these endpoints the
  same question.

  BREAKING CHANGE: `RepositoryApi.search` and `UserApi.search`, on both rails,
  take a query object instead of a `String`. Rewrite
  `client.repos.search("forgejo", params)` as
  `RepositorySearchQuery.of("forgejo").map(client.repos.search(_, params))`, or
  build the query once and pass it in, and the same for
  `client.users.search` with `UserSearchQuery.of`. A call that searched with an
  empty or blank keyword now sends no `q` at all rather than an empty one, which
  Forgejo answers identically. `UserApi.KeywordParameter` is gone: the `"q"`
  spelling now lives once, in `UserQueries`.

- **The round-trippable enums share one wire vocabulary.** Seven enums are both
  decoded from a JSON field and written back into a request — `TeamPermission`,
  `UserVisibility`, `ContentKind`, `CommitFileStatus`, `CollaboratorPermission`,
  `GitObjectKind` and `CommitStatusState`. Each of them used to spell its wire
  words out twice: once in a `parse` matching words to cases, once in a renderer
  matching cases back to words. Nothing made the compiler check that the two
  lists agreed, so an added case could render a word `parse` did not accept.
  They now extend a new `com.worxbend.codeberg4s.WireVocabulary`, which carries
  the word on the case itself (`case Directory extends ContentKind("dir")`) and
  supplies the shared `WireVocabulary.parse` lookup. Behaviour is unchanged:
  parsing still trims, still folds case with `Locale.ROOT`, and still answers
  `None` for a word this library does not know.

  **Breaking:** `CommitStatusState.wireValue` is now `CommitStatusState.wireName`,
  which is what the other six already called it. Rename the call; nothing else
  about the value changes. The enums this library only ever decodes are
  deliberately left alone — they have no second spelling to drift from, and a
  renderer for them would mean inventing words the API never asks for.

- **Every sub-resource listing is now named after its plural noun.** A previous
  release applied this rule to `RepositoryApi` only, which left the library
  saying the same thing two ways — `client.repositories.commits(...)` next to
  `client.pulls.listCommits(...)`. The rule is that `list` names the listing of
  an API group's *own* resource, and a listing of a sub-resource hanging off it
  is just the noun: the receiver already says which group is being asked, so the
  `list` prefix adds a word that carries no information. It is now written down
  in `SCALA_CODE_STYLE.md` and checked by `ListingNamingSuite`, a reflection
  test that fails on any public `list<Noun>` outside a recorded exemption.

  **Breaking:** drop the `list` prefix and lower-case the first letter of the
  noun, on both the convenience rail and the `.attempt` rail. `pulls`:
  `listReviews` → `reviews`, `listCommits` → `commits`, `listFiles` → `files`,
  `listPinned` → `pinned`, `listReviewComments` → `reviewComments`.
  `repositories.access`: `listBranchProtections` → `branchProtections`,
  `listTagProtections` → `tagProtections`, `listCollaborators` →
  `collaborators`, `listDeployKeys` → `deployKeys`, `listTeams` → `teams`.
  `repositories.actions`: `listArtifacts` → `artifacts`, `listRuns` → `runs`,
  `listRunners` → `runners`, `listSecrets` → `secrets`, `listVariables` →
  `variables`, `listTasks` → `tasks`, `listRunArtifacts` → `runArtifacts`,
  `listRunJobs` → `runJobs`. `organizations.actions` and `users.account.actions`
  rename their `listRunners`, `listSecrets` and `listVariables` the same way.
  `repositories.gitdata`: `listRefs` → `refs`, `listMatchingRefs` →
  `matchingRefs`, `listStatuses` → `statuses`, `listTree` → `tree`.
  `repositories.wiki`: `listPages` → `pages`. `repositories.hooks`:
  `listGitHooks` → `gitHooks`. `repositories.publishing`: `listAssets` →
  `assets`. `issues`: `listBlocks` → `blocks`, `listDependencies` →
  `dependencies`.

  Two families deliberately keep the prefix, and both are recorded in the
  suite's exemption list. The *scope-disambiguated* readers name the parent
  rather than the resource, because the noun alone would not say which parent is
  meant: `issues.attachments.listForIssue` / `listForComment`,
  `issues.comments.listForRepository`, `issues.labels.listOnIssue`,
  `issues.reactions.listOnIssue` / `listOnComment`, and
  `notifications.listRepository`. The other is a *collision*:
  `IssueApi.listComments`, `listLabels` and `listMilestones` cannot take their
  bare nouns, because `client.issues.comments`, `.labels` and `.milestones` are
  already the sub-API accessors. Each of those three now says so in its
  Scaladoc.

- **`client.users.account.quota.info()` is now `get()`.** `OrganizationQuotaApi`
  and `UserQuotaApi` are otherwise line-for-line mirrors of each other, and the
  organization side already called this operation `get`; one differing name
  between two twins is noise a reader has to stop and check.

  **Breaking:** call `quota.get()` instead of `quota.info()` on both rails. The
  operation id constant `UserQuotaApi.InfoOperation` is now
  `UserQuotaApi.GetOperation`, and the operation id it carries changes from
  `users.account.quota.info` to `users.account.quota.get`, so any alert matching
  on that string needs updating.

- **`Repository.id` is a `RepositoryId`, and `RepositoryId` moved to
  `com.worxbend.codeberg4s.repositories`.** `repos.admin.byId` already took a
  `RepositoryId` while the model handed back a bare `Long`, so addressing a
  repository by the id it had just reported meant a `RepositoryId.from` round
  trip. The type could not simply be used by the model where it was, under
  `repositories.admin`: a model every endpoint group returns cannot depend on a
  type declared inside one of them, so it now sits beside `Repository`.

  **Breaking:** import `com.worxbend.codeberg4s.repositories.RepositoryId`
  instead of `com.worxbend.codeberg4s.repositories.admin.RepositoryId`. Write
  `repository.id.value` for the number; pass `repository.id` straight to
  `repos.admin.byId`. An `id` of `0` or below — which is what an unset Go field
  looks like — is now a decoding failure at `$.id` rather than an identifier no
  endpoint would accept.

- **`Repository.defaultBranch` is an `Option[BranchName]` and
  `Repository.topics` a `Vector[Topic]`.** Both were bare strings, so reading a
  repository and then asking for that branch, or adding to that topic set,
  meant re-parsing values the library had already accepted. `RepositoryDto`
  validates both during conversion.

  **Breaking:** call `.value` where the text is wanted
  (`repository.defaultBranch.map(_.value)`). A `default_branch` that cannot be
  a `BranchName` is now a decoding failure at `$.default_branch`, and a topic
  that cannot be a `Topic` fails the repository at `$.topics[n]` rather than
  being dropped — the same first-failure rule every other array in the codec
  follows, so a shortened vector never gets mistaken for a repository with
  fewer topics. An absent `default_branch`, and an absent, `null` or empty
  `topics`, are unchanged.

- **`repos.topics` returns a `Page[Topic]`, not a `Page[String]`.** The write
  side of the same endpoint group already spoke `Topic` —
  `repos.publishing.replaceTopics` takes a `Vector[Topic]`, `addTopic` and
  `removeTopic` take one — so reading a repository's topics back as strings
  meant a caller that wanted to add to the set it had just read had to put
  every name through `Topic.from` again. `TopicNamesDto.toDomain` now validates
  each name where the envelope is read.

  **Breaking:** `client.repos.topics(...)` (and its `.attempt` mirror) now
  answers `Page[Topic]`. Call `.value` on an element for its text. A name that
  cannot be a `Topic` — blank, or carrying something that could not go back
  into a request path — is now a `DecodingFailed` naming the element's own
  position, `$.topics[2]`; the method could previously fail only on a body that
  was not a JSON object at all.

- **`User.login` is an `Owner`, not a `String`.** The handle is what every
  endpoint that addresses a user or a repository takes, and until now a caller
  who had just read a `User` had to push its `login` back through `Owner.from`
  and handle an `Either` that could not fail — the value came from the same
  payload the library had already accepted. `UserDto.toDomainAt` now validates
  `login` where the user is read, so the model carries an already-valid
  `Owner`. `RepositoryDto` no longer validates the owner's login a second time
  of its own; it builds the slug from `owner.login`.

  **Breaking:** `user.login` no longer is a `String`. Where you need the text —
  printing it, comparing it to a string — write `user.login.value`. Where you
  were passing it to an endpoint, drop the `Owner.from` round trip and pass it
  straight through. A payload whose `login` cannot be an `Owner` (blank, or
  containing a `/`) is now a `DecodingFailed` at `$.login` instead of decoding
  into a model nobody could use.

- **Wire DTOs share one conversion capability, `codec.WireModel[A]`.** Every DTO
  declared its own `toDomain` as `toDomainAt(JsonPath.Root)` — the same member,
  written out 75 times — and 44 of them also carried a companion `toDomainAll`
  whose body was the same one-line array fold. Both now live in one place:
  `WireModel[A]` asks a DTO only for `toDomainAt(at)` and supplies `toDomain`,
  and `WireModel.all(base, dtos)` is the fold. Conversion behaviour, including
  the JSON path each failure reports, is unchanged.

  **Breaking:** `FooDto.toDomainAll(base, dtos)` is gone from the DTO
  companions. Replace it with `WireModel.all(base, dtos)`, importing
  `com.worxbend.codeberg4s.codec.WireModel`. `FooDto.toDomain` and
  `fooDto.toDomainAt(at)` are unaffected. The three `QuotaUsed*Dto` companions,
  whose conversions cannot fail, keep their own `toDomainAll`.

- **Quota is modelled once, in `com.worxbend.codeberg4s.quota`.** Forgejo
  answers `GET /orgs/{org}/quota` and `GET /user/quota` with the same
  `QuotaInfo` payload, and the three usage listings under each of them with the
  same `QuotaUsedArtifact`, `QuotaUsedAttachment` and `QuotaUsedPackage`
  elements. Until now each of those was modelled twice — once under
  `organizations`, once under `users.account` — with the same wire spellings
  written out in two decoders and the field names drifting between the copies
  (`sizeBytes` against `size`, `QuotaAttachmentContext` against
  `AttachmentContainer`). Two public case classes both called `QuotaInfo` meant
  a program reading an organisation's quota and its own could not import both.
  There is now one model set, one DTO set, and one decode suite covering the
  traps both copies had to know about (the upper-case `git.LFS` key, and
  `assets.packages.all` being an object rather than a number).

  The surviving shapes are the more useful half of each pair: the nested `used`
  tree is flattened into one `QuotaUsedSize` with seven leaves and a
  `reportedTotal`, `QuotaRule.subjects` keeps the instance's own strings so a
  subject a newer Forgejo emits is never dropped, and `QuotaRule.isUnlimited`
  and `QuotaInfo.rules` are kept.

  BREAKING CHANGE: import the quota types from `com.worxbend.codeberg4s.quota`
  instead of from `com.worxbend.codeberg4s.organizations` or
  `com.worxbend.codeberg4s.users.account`. `QuotaSubject`, `QuotaInfo`,
  `QuotaGroup` and `QuotaRule` keep their names. The organisation listings now
  answer `QuotaUsedArtifact`, `QuotaUsedAttachment` and `QuotaUsedPackage`
  where they answered `QuotaArtifact`, `QuotaAttachment` and `QuotaPackage`,
  and `QuotaAttachmentContext` is now `AttachmentContainer`. On the account
  side, the three usage models report `sizeBytes` where they reported `size`.
  Reading an organisation's usage tree changes shape: `info.used.size.repos`
  and its siblings are gone, so `info.used.size.repositories.publicBytes`
  becomes `info.used.publicRepositories`, `info.used.size.git.lfsBytes`
  becomes `info.used.gitLfs`, and `QuotaUsage.Empty` becomes
  `QuotaUsedSize.Empty`. `QuotaRule.subjects` on the account side is now
  `Vector[String]` rather than `Vector[QuotaSubject]`; convert an entry with
  `QuotaSubject.from` where one is needed as a query argument.

- **The two ZIP downloads moved to `client.repos.actions`,** and the root-level
  `client.downloads` group is gone. They used to sit apart because they were
  the only operations that needed a byte-carrying transport; that transport no
  longer exists, so nothing kept them out of the Actions group they belong to.

  BREAKING CHANGE: replace `client.downloads.artifact(owner, name, id)` with
  `client.repos.actions.downloadArtifact(owner, name, id)`, and
  `client.downloads.runLogs(owner, name, run)` with
  `client.repos.actions.downloadRunLogs(owner, name, run)`. Both are on
  `client.repos.actions.attempt` under the same names. The operation ids —
  `repos.actions.artifacts.download` and `repos.actions.runs.logs.download` —
  are unchanged, so anything alerting on them still works.

- **`BinaryResponse` and `BinaryHttpPort` are removed.** `BinaryResponse` was a
  near-duplicate of `CodebergResponse` left over from when a response body was
  a `String` and a ZIP could not survive one. A body has been bytes plus a
  charset (`ResponseBody`) for a while, so one response type now serves every
  call, and one port serves every transport.

  BREAKING CHANGE: a download answers `core.CodebergResponse`. Read the archive
  as `response.body.bytes` where it was `response.bytes`, and
  `response.body.size` where it was `response.size`; the status and the headers
  are unchanged. Byte-structural equality is unchanged too — `ResponseBody`
  compares its bytes with `java.util.Arrays.equals`.

- **`HttpPort.send` takes the response-body bound as an argument.** Which bound
  applied used to depend on which port method was called, which put the choice
  in the adapter, the one place that cannot know whether it is fetching a JSON
  document or a CI artifact. The pipeline passes it now:
  `maxResponseBodyBytes` for every ordinary call, `maxDownloadBodyBytes` for
  the two archive downloads. Both settings stay on `CodebergConfig`; only where
  they are read changed.

  BREAKING CHANGE: an implementation of `core.HttpPort` gains a third
  parameter, `maxBodyBytes: Long`, and must apply it to the response body
  instead of reading a bound off `CodebergConfig` itself.

- **`ValidationError` is now the case `CodebergError.Validation`** rather than a
  standalone `final case class` wrapped by that case. `ValidationError` remains
  a usable name — it is a type alias for the case, with an `apply` and an
  `unapply` — so smart constructors still read as
  `Either[ValidationError, Owner]`.

  The point is composition. A pre-flight validation failure and a remote failure
  now travel on one channel, so a smart constructor and a client call sequence
  in a single `for`-comprehension: `Either`'s `flatMap` widens the left type to
  `CodebergError` by itself, and nothing has to be mapped from one error type to
  another in between.

  BREAKING CHANGE: `CodebergError.Validation` takes the two fields directly
  instead of wrapping a `ValidationError`. Rewrite
  `CodebergError.Validation(ValidationError(field, message))` as
  `ValidationError(field, message)`, and a match of the shape
  `case CodebergError.Validation(problem) => problem.field` as
  `case CodebergError.Validation(field, message) => field`. Reading `.field` and
  `.message` off a value already typed as the failure is unchanged, and so is
  every smart constructor's result type.

## [0.1.0] — unreleased

First release. `build.mill` publishes `0.1.0-SNAPSHOT` until the tag is cut;
this entry is the release note that tag will carry.

Nothing has been published to Maven Central yet, so "Changed" and "Fixed" below
are not a migration path from an earlier release — there is no earlier release.
They are there because the surface freezes at this tag: everything listed was
changed deliberately *before* the freeze, and anyone who built against a
`0.1.0-SNAPSHOT` jar in the meantime is the one audience that has to read them.

### Added

- **Public `Future` API.** `CodebergClient` exposes eight accessors — `repos`,
  `users`, `issues`, `pulls`, `organizations`, `notifications`, `misc` and
  `version` — which between them reach 38 API classes and 439
  REST operations against Codeberg, Forgejo or any Gitea-compatible instance.
  The base URI is configuration, not a constant. That is 439 of the 439
  in-scope operations, 100 % (`docs/API_INVENTORY.md` §0), and 86.8 % of the
  506 the pinned spec declares — the remaining 67 are `admin`, `activitypub`
  and `package`, which `PLAN.md` §0 puts out of scope for v1.
- **Two error rails over one code path.** Every operation exists twice: the
  convenience rail fails the `Future` with `CodebergException`, and
  `.attempt` returns `Future[Either[CodebergError, A]]` and never fails. Both
  are projections of the same `Exec[F]` pipeline, so they cannot drift.
- **A closed error ADT with call context.** `CodebergError` has six cases —
  `Transport`, `Api`, `DecodingFailed`, `Validation`, `RetriesExhausted`,
  `WalkTruncated`. Every remote case carries a `CallContext` (a stable operation
  id, the HTTP method, the redacted URI, the server's `x-request-id`, the
  attempt duration), so a caller can tell *which* call failed without
  correlating logs. Forgejo's error payloads are parsed into `ApiErrorBody`
  against captured samples.
- **Link-header pagination.** List operations return `Page[A]` with the items,
  the total count and the next page parsed from the RFC 8288 `Link` header
  rather than guessed from a page counter. `paging.PageWalk` provides the
  sequential `all`, `fold` and `foreach` drivers, so walking every page is
  opt-in and never materialises the whole collection by accident.
- **Retry that honours the server.** `RetryEngine` retries `429` and `5xx` on
  idempotent methods only, with jittered exponential backoff, and prefers the
  server's `Retry-After` over its own schedule when the policy allows it.
  `POST`, `PATCH` and `DELETE` are never retried automatically.
- **Tokens are redacted everywhere.** `ApiToken` renders as `***` in `toString`
  and in string interpolation, and no `CodebergError` — including the URI
  captured in `CallContext` — can carry one. There are tests that assert it.
- **A `Telemetry` port** for request/response visibility. The library has no
  logging dependency and writes nothing to stdout.
- **A bounded response body.** `CodebergConfig` carries
  `maxResponseBodyBytes` (16 MiB, every textual response) and
  `maxDownloadBodyBytes` (50 MiB, the two ZIP-fetching operations
  `client.repos.actions.downloadArtifact` and `downloadRunLogs`). Exceeding either is `TransportCause.ResponseTooLarge`,
  which is deliberately *not* retryable — a retryable oversize failure would
  have downloaded the same oversized body once per attempt.
- **Hexagonal module layout,** published as five artifacts under
  `com.worxbend`: `codeberg4s-domain` (no dependencies at all),
  `codeberg4s-core`, `codeberg4s-codec` (jsoniter-scala), `codeberg4s-transport`
  (sttp client4) and `codeberg4s-client`. Naming `codeberg4s-client` pulls in
  the other four transitively. The jars are Java 25 bytecode (class-file major
  version 69); an older JVM cannot load them.
- **Verification.** 3,658 unit tests, 54 golden fixtures captured from the live
  API, scoverage thresholds enforced by `scripts/coverage-gate.sc`, and a
  `verify.sh` gate that also enforces the architecture boundaries (no `sttp`,
  `upickle`, `ujson`, `Future`, `ExecutionContext`, `Await`, `Promise` or
  `blocking` imported below `client`; no `sttp` in `codec`; no bare
  exceptions). Measured on the commit this entry describes: `domain` 100.00 %
  statement and 100.00 % branch coverage, `core` 96.59 % / 92.48 %, `codec`
  95.20 % / 91.47 %, and the CRAP gate reports a worst method of 28.0 over
  2,217 methods against a limit of 30.
- **Compile-time constructors for the path identifiers.** Each identifier that
  is validated as a URI path segment — `Owner`, `RepoName`, `BranchName`,
  `TagName`, `Username`, `OrgName` and the rest — now takes a string literal
  directly. `Owner("forgejo")` is checked while the code compiles and *is* the
  `Owner`, with no `Either` to unwrap, and an invalid literal is a compile
  error naming the field: `not a valid owner: "forgejo/forgejo"`. `from` is
  unchanged and remains the way in for a value known only at run time; handing
  one to the literal constructor is itself a compile error. The check is
  `inline` and folds away, so it reaches no bytecode. One deliberate
  difference between the two: the literal form refuses surrounding whitespace
  where `from` trims it.
- **A single import for the everyday surface.** `Auth`, `Page`, `PageParams`
  and `PageSize` are re-exported from the package root, so
  `import com.worxbend.codeberg4s.*` covers building an `Auth`, constructing a
  client, calling an operation and paging through a listing. The quick start
  needed six import lines before.

### Changed

Every item here is a breaking change against the `0.1.0-SNAPSHOT` builds, taken
now because the tag is what freezes the surface.

- **Command and query types can no longer be built without validation.**
  Thirteen types paired a validating `of` with a public case-class
  constructor, so `apply` and `copy` bypassed the check the Scaladoc
  promised — `AddTrackedTime(1500.millis, …)` was accepted and then sent as
  one second, because the codec truncates on the stated assumption that the
  domain type refuses sub-second durations. Their constructors are now
  `private[codeberg4s]`, matching the 131 response models. Build them with
  `of` and adjust them with the `with…` builders. The types:
  `AddTrackedTime`, `CreateComment`, `CreateIssue`, `CreateMilestone`,
  `EditComment`, `CreatePullRequest`, `BranchProtectionSettings`,
  `CreateDeployKey`, `DeployKeyQuery`, `CreateWikiPage`, `EditWikiPage`,
  `ActivityFeedQuery`, `TrackedTimeWindow`.
- **`CodebergError.Api` carries a fourth field, `retryAfter`.** The pipeline
  already parsed the server's `Retry-After` header for the retry engine, but
  the value stopped there: a caller that handled a `429` itself — or read the
  `last` of a `RetriesExhausted` — had the status and nothing to schedule a
  backoff from. `Api` is now
  `Api(ctx, status, body, retryAfter: Option[FiniteDuration])`, and `describe`
  appends `; retry after 30s` when a delay is present. Pattern matches add one
  wildcard (`case CodebergError.Api(_, 404, _, _)`); constructions in test code
  pass `None`. `None` on a `429` means the instance sent no usable hint — the
  header was absent, blank, or in the HTTP-date form this library does not
  parse — not that an immediate retry is safe.
- **`organizations.BlockedUser` and `organizations.BlockId` are gone.** The
  organisation and account block lists return the same two-property Forgejo
  model, and it was declared twice. Import
  `com.worxbend.codeberg4s.users.social.BlockedUser` and `BlockId`; the type
  is identical. `OrganizationApi.blockedUsers` is unchanged.
- **`Owner` and `RepoName` moved to the package root.** They were in
  `com.worxbend.codeberg4s.repositories`, which meant the two types needed
  before *any* request could be made lived in a sub-package a caller had no
  other reason to know about. Import them from `com.worxbend.codeberg4s`, or
  use the single wildcard import above.
- **Every `PageParams` parameter is named `params`, not `pageParams`.** The old
  name restated the type instead of saying anything. Only call sites that pass
  the argument by name need an edit.
- **`RepositoryApi`'s sub-resource listings are bare nouns.** `listBranches`,
  `listTags`, `listCommits`, `listReleases`, `listTopics` and `listForks`
  became `branches`, `tags`, `commits`, `releases`, `topics` and `forks`: the
  `list` prefix repeated what `client.repos.` had already established. The
  `list…` names on `IssueApi`, `RepositoryAccessApi` and `PullRequestApi` are
  unchanged, because there the prefix still distinguishes the operation.
- **Response models cannot be constructed from outside the library.** All 131
  response types — `Repository`, `Issue`, `PullRequest`, `User`,
  `Organization`, `NotificationThread`, `ServerVersion`, `ApiErrorBody` and the
  rest — have a `private[codeberg4s]` constructor, so `apply` and `copy` are
  unavailable to callers. Reading fields and pattern matching are unaffected.
  This is the deliberate price of *not* owing a major version every time
  Forgejo adds a field to a response. A test fixture that used to build one
  directly now has to obtain it from a client call or decode a recorded
  payload.
- **`CodebergError` has a sixth case, `WalkTruncated(pagesVisited,
  resumeFrom)`,** and `PageWalk.all` / `fold` / `foreach` now fail with it when
  they hit the page cap. They previously returned the pages gathered so far,
  which a caller could not tell apart from a genuinely short collection. An
  exhaustive `match` on `CodebergError` needs the new clause.
- **`core.Pagination` is removed,** along with its `listAll` and `foldPages`
  methods. `com.worxbend.codeberg4s.paging.PageWalk` replaced it:
  `PageWalk.all(start)(fetch)` and `PageWalk.fold(start, zero)(fetch)(step)`.
- **A response body is bytes, not a `String`.** `CodebergResponse.body` is a
  `ResponseBody` and `Decode[A].apply` takes one. Ask it for `bytes`, `text` or
  `isBlank`. A test fake building a response writes
  `ResponseBody.utf8("[]")`, or `ResponseBody.Empty` for a `204`.
- **A JSON number is `JsonValue.Int64(Long)` or
  `JsonValue.Decimal(BigDecimal)`.** `JsonValue.Num` survives as an object
  holding the constructors and an extractor, so `Num(7)` and
  `case Num(value)` still compile, but it is no longer a type and no longer a
  case of the ADT.
- **`JsonFields` holds the parser's `Vector[(String, JsonValue)]`** rather than
  a `Map`. `fields.underlying` becomes `fields.entries` or `fields.toMap`. No
  accessor changed, so a DTO that only calls `text`, `number`, `nested` and
  friends needs no edit.
- **`UploadAsset` and `UploadAttachment` are built through `of` / `named` /
  `as`,** not through their constructors, and `as` validates the media type, so
  it answers `Either[ValidationError, …]`. Both also compare their content by
  its bytes now, as do `ResponseBody`, `RequestBody.Binary` and
  `RequestBody.Multipart`; code that relied on two byte-identical values
  staying distinct has to say `eq`.
- **`CodebergConfig` gains `maxResponseBodyBytes` and
  `maxDownloadBodyBytes`,** so a call to the full constructor needs two more
  arguments. `CodebergConfig.DefaultMaxResponseBodyBytes` and
  `DefaultMaxDownloadBodyBytes` reproduce what `CodebergConfig(auth)` uses.
- **`UserTokenApi.RedactedBody` is removed.** Every credential-bearing response
  is now redacted the same way by the pipeline; see Security below.
- **The jars require Java 25.** They were Java 17 bytecode before. A Java 17 or
  Java 21 JVM fails with `UnsupportedClassVersionError`.

### Security

- **A base URI carrying credentials is rejected.**
  `https://user:password@forge.example/api/v1` used to be stored verbatim and
  concatenated into the redacted URI that every `CallContext` carries, so the
  password reached every log line written about a failed call. `BaseUri.from`
  now rejects user information, a query string and a fragment — without
  echoing the offending value — and `Redaction.uri` strips the same three parts
  independently, because test fakes call it with a plain `String` it has not
  vetted. Embedded credentials were never sent as an `Authorization` header by
  the JDK HTTP client underneath, so code relying on them was making anonymous
  requests and leaking the password at the same time. Use
  `Auth.Basic(username, password)`.
- **Dot segments are rejected in every single-segment identifier.** `Owner`,
  `RepoName`, `Username`, `OrgName` and eleven more promised in their Scaladoc
  that an accepted value could not forge a path, but accepted the exact values
  `"."` and `".."`. No working traversal against a real deployment is claimed
  here; the narrower claim stands on its own. Git itself forbids both as path
  components, so no legitimate name is lost.
- **A credential cannot reach a decode-failure snippet.** `DecodingFailed`
  carries an excerpt of the body that did not match, which is what makes it
  diagnosable — except for the four responses whose success body *is* a live
  secret (a created access token, an OAuth2 client secret, and the Actions
  runner registration token in its several forms). Those now report
  `*** (N bytes withheld)`. The decision moved into `ApiPipeline` because
  `Telemetry.onError` fires while the attempt is being settled, before any
  endpoint could rewrite the failure.
- **A configured credential is applied after the caller's headers,** which is
  what its Scaladoc always claimed and the opposite of what the code did. No
  endpoint in this library sets an `Authorization` header today, so this was
  latent rather than live. `Authorization`, `Proxy-Authorization` and
  `User-Agent` are also dropped from caller headers first, so a request cannot
  carry two credentials under two spellings of one name.
- **A response body is bounded** — see `maxResponseBodyBytes` above. Before
  this, the only thing standing between the client and its heap was the read
  timeout multiplied by the peer's bandwidth.

### Fixed

- **The HTTP client the library created is now actually shut down.**
  `CodebergClient.close()` called `backend.close()`, and that call released
  nothing: sttp only ends a client it built itself if the `ExecutionContext` it
  was handed is *not* also a `java.util.concurrent.Executor`, and every
  ordinary `ExecutionContext` is one. An application building a client per
  instance leaked a connection pool and a selector thread per instance. The
  JDK `HttpClient` is now built here and ended with `shutdown()` — not
  `close()`, which blocks until in-flight requests finish, whereas `close()` on
  this library's client is documented to return promptly.
- **A retry waiting in backoff when the client closes now fails.** It used to
  be left with no outcome at all: not fulfilled, not failed, so an application
  shutting down cleanly waited on that `Future` for as long as the process
  lived. Such a call now fails with `java.util.concurrent.CancellationException`
  — not a `CodebergError`, because closing a client while it is in use is a
  defect in the calling program and must not be laundered into something a
  caller would retry.
- **`RetriesExhausted` is reported only when the policy actually gave up.** A
  call that met a retryable `503` and then a terminal `404` reported
  `RetriesExhausted(ctx, 2, Api(404))`, so the same `404` produced two
  different error shapes depending on what preceded it. It now reports the bare
  `Api(404)`.
- **A failing telemetry sink no longer fails the call it was observing.**
- **A duplicate JSON key has a settled meaning** — the first occurrence wins,
  at every object width, with tests at both sides of the width threshold.
- **A colour with a trailing `U+0085`, `U+2028` or `U+2029` is rejected.** Java
  regular expressions let `$` match before those line terminators and `trim`
  does not remove them, so `LabelColor` used to accept the control character
  and quietly discard it.

### Performance

Every figure below is from `scripts/alloc-bench.sh` on OpenJDK 64-Bit Server VM
25.0.4+7-LTS. `B/op` is heap bytes allocated per operation, the median of the
measured rounds. That harness is deliberately not part of `verify.sh`: it is a
measurement tool, not a gate. Read the header of `scripts/alloc-bench.sc`
before quoting any of this — in particular, wall-clock times on a working
machine vary by tens of percent between rounds and are a direction of travel,
not a figure.

- **Decoding a 170,251-byte page of 50 repositories allocates 1,133,632 B/op,
  down from 1,944,816 — 41.7 % less.** Two changes account for it. `JsonFields`
  used to copy the parser's `Vector[(String, JsonValue)]` into a `Map` once per
  object at every nesting level, which was 819,600 bytes of the old total; it
  now reads the vector directly, scanning names for a narrow object and probing
  a hash index for one of eight fields or more. (The index is not decoration: a
  plain scan was measured first and made assembling one `Repository` 24 %
  *slower* than the `Map` it replaced.) Separately, a whole JSON number is a
  `Long` rather than a `BigDecimal`, worth 32.0 bytes per number — 33.7 % off a
  thousand-element array of nine-digit identifiers.
- **The end-to-end response path allocates 963,360 B/op against 1,303,928 for
  the same decode done via a `String` — 26.1 % less.** A body used to be
  decoded from the socket's bytes into a `String` by sttp and then encoded
  straight back into a `byte[]` by the parser. The saving is 340,568 bytes and
  the page is 170,251 bytes, so it is those two copies and essentially nothing
  else. The harness still measures both paths side by side
  (`decode.page-50-bytes` against `decode.page-50-viastring`) so the claim can
  be re-checked.
- **A Forgejo timestamp parses in about 25.7 ns and 40 B/op, from about 680 ns
  and roughly 1.5 KB.** `OffsetDateTime.parse` is a general RFC-3339 reader;
  Forgejo emits exactly one layout. The fast path reads that layout by index
  and answers `None` for anything else, so the JDK stays the authority on what
  is valid. The two paths were checked against each other over a million
  generated inputs.
- **Smaller allocations removed from paths every call walks:** the redacted URI
  is built once per call rather than once per attempt, and with one
  `StringBuilder` rather than one `String` per percent-encoded octet; the
  `Link` header is parsed once per response rather than up to three times, and
  in linear rather than quadratic time; `Telemetry.noOp` no longer builds a
  varargs `Seq` per callback; `FutureExec.attempt` uses one `transform` rather
  than a `map` and a `recover`, which is one `Future` and one executor dispatch
  instead of two; four hand-written element-decoding folds became one
  tail-recursive helper that stops at the first failure instead of walking the
  rest of the array.

### Known limitations

- The mutation score is **unproven**. `scripts/mutate.sh` exists and the
  Stryker4s runner is proven against this build, but no run with the real test
  command has ever produced a score for this repository, so the ≥ 80 % target
  in `docs/ROADMAP.md` is a target and not a result.
- Duplication is tracked, not eliminated. `scripts/cpd.sh` reports **363
  duplication groups** at 40+ tokens (PMD 7.26.0), and `verify.sh --with-slow`
  passes because it fails on an *increase* over that recorded number rather
  than on the existence of duplication. `docs/LEDGER.md` § "Helpers awaiting
  promotion" names the ones with owners.
- Walking every page goes through `paging.PageWalk`, which takes the listing
  operation as an argument; there is no `listAll` convenience method on the
  client resource groups themselves.
- The library reads a whole response into memory and never streams, so a large
  artifact is bounded rather than chunked — see `maxDownloadBodyBytes` above.
- ScalaCheck property suites carry the `Property` tag and are excluded from the
  default gate, and one of the three areas `PLAN.md` §6.3 names still has none:
  the page walker has example-based tests only. `docs/ROADMAP.md` tracks the
  rest, and `docs/CONSTITUTION_MAPPING.md` is the authority on which quality
  runners are actually proven against Mill and Scala 3.
- No binary-compatibility baseline — 0.1.0 *is* that baseline. MIMA is wired
  in `build.mill` and covers all five artifacts, but it has nothing to compare
  against until 0.1.0 is on Maven Central, so it reports nothing until 0.1.1.
  `RELEASING.md` § "Binary compatibility is checked by MIMA" is the procedure,
  and measures what MIMA does and does not see through the response models'
  `private[codeberg4s]` constructors.
- Out of scope by design: OAuth2 token acquisition, ActivityPub federation,
  admin endpoints, attachment streaming above 50 MB, and Scala.js / Native.

[Unreleased]: https://codeberg.org/worxbend/codeberg4s/compare/v0.1.0...main
[0.1.0]: https://codeberg.org/worxbend/codeberg4s/releases/tag/v0.1.0
