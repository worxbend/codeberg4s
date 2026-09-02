# API groups

For anyone looking for where an endpoint lives. Nine accessors hang off
`CodebergClient`; five of them nest further groups, for **51 API classes** in
total.

Every class listed here has the same two-rail shape: the methods on the class
itself return `Future[A]` and fail with `CodebergException`, and the identical
set under `.attempt` returns `Future[Either[CodebergError, A]]` and never fails.
The counts below are of operations on the convenience rail; each is mirrored on
the typed one. See [Errors](../guides/03-errors.md).

The "Scaladoc" column names the fully qualified class. Every operation carries
its own Scaladoc saying what it calls, what it returns, which failures it can
produce, and **which retry eligibility it uses** — that last one is not
guessable from the HTTP method, so read it before assuming.

## The eight accessors

| Accessor | Class | Operations | Nested groups |
| --- | --- | ---: | ---: |
| `client.version` | `com.worxbend.codeberg4s.VersionApi` | 1 | — |
| `client.repos` | `com.worxbend.codeberg4s.repositories.RepositoryApi` | 11 | 9 |
| `client.users` | `com.worxbend.codeberg4s.users.UserApi` | 8 | 8 |
| `client.issues` | `com.worxbend.codeberg4s.issues.IssueApi` | 14 | 9 |
| `client.pulls` | `com.worxbend.codeberg4s.pulls.PullRequestApi` | 13 | 1 |
| `client.organizations` | `com.worxbend.codeberg4s.organizations.OrganizationApi` | 16 | 6 |
| `client.notifications` | `com.worxbend.codeberg4s.notifications.NotificationApi` | 7 | — |
| `client.misc` | `com.worxbend.codeberg4s.miscellaneous.MiscellaneousApi` | 17 | — |

---

## `client.version` — 1 operation

`GET /version`, and nothing else. The cheapest liveness probe there is, and the
one endpoint every Forgejo answers anonymously — so it is how you check that a
base URI really points at an API root.

`get()`

---

## `client.repos` — repositories

`RepositoryApi` itself holds the everyday reads. Anything that changes a
repository, or that reaches into a specialised corner of one, is in a nested
group.

**11 operations:** `get`, `search`, `branches`, `getBranch`, `tags`,
`commits`, `releases`, `getRelease`, `topics`, `getContents`,
`forks`

`getContents` is the one union in the API: the same path answers a file object
or an array of directory entries, so it decodes to an ADT rather than to a
record full of nullable fields.

### Nested groups

| Accessor | Class | Operations | Covers |
| --- | --- | ---: | --- |
| `client.repos.actions` | `repositories.actions.RepositoryActionApi` | 14 | a repository's Actions record: runs, jobs, tasks, artifacts, workflow dispatch, and the two ZIP downloads |
| `client.repos.actions.config` | `repositories.actions.RepositoryActionConfigApi` | 14 | what those runs run on and with: runners, secrets, variables |
| `client.repos.git` | `repositories.gitdata.RepositoryGitApi` | 13 | raw Git data and commit-level reads: blobs, trees, refs, notes, annotated tags, comparison, diffpatch |
| `client.repos.git.statuses` | `repositories.gitdata.CommitStatusApi` | 3 | what CI said about a commit, and which pull request brought it |
| `client.repos.git.files` | `repositories.gitdata.RepositoryFileApi` | 4 | a repository's bytes: raw file, media file, archive, editor config |
| `client.repos.publishing` | `repositories.publishing.RepositoryPublishingApi` | 14 | releases, tags, topics, forking, generating from a template |
| `client.repos.publishing.assets` | `repositories.publishing.ReleaseAssetApi` | 5 | the files attached to a release |
| `client.repos.hooks` | `repositories.hooks.RepositoryHookApi` | 10 | webhooks Forgejo delivers elsewhere, and the Git hooks it runs on its own machine |
| `client.repos.wiki` | `repositories.hooks.RepositoryWikiApi` | 6 | wiki pages, their content and their history |
| `client.repos.flags` | `repositories.hooks.RepositoryFlagApi` | 6 | a repository's administrative flags |
| `client.repos.issueConfig` | `repositories.hooks.RepositoryIssueConfigApi` | 3 | what a repository tells a contributor about to open an issue: its issue config and templates |
| `client.repos.access` | `repositories.access.RepositoryAccessApi` | 13 | who may reach a repository: collaborators, deploy keys, team access |
| `client.repos.access.protections` | `repositories.access.RepositoryProtectionApi` | 10 | what they may do to a ref: branch and tag protections |
| `client.repos.admin` | `repositories.admin.RepositoryAdminApi` | 14 | administering a repository: creating, editing, transferring, converting, branches, avatars |
| `client.repos.admin.mirrors` | `repositories.admin.RepositoryMirrorApi` | 10 | pull mirrors, push mirrors and fork sync |
| `client.repos.admin.contents` | `repositories.admin.RepositoryContentApi` | 5 | reading a repository's files and **writing files** into it |
| `client.repos.admin.watchers` | `repositories.admin.RepositoryWatcherApi` | 7 | watching, starring, and who may be assigned or asked to review |
| `client.repos.admin.insights` | `repositories.admin.RepositoryInsightApi` | 8 | activity, languages, pins, topics, tracked time |

`client.repos.actions` holds the only two operations in the library whose
success body is not text: `downloadArtifact` and `downloadRunLogs` answer a ZIP,
so they hand back the whole `CodebergResponse` and the archive is
`response.body.bytes`. Both hold the archive in memory — **this library does not
stream** — and both read under `CodebergConfig.maxDownloadBodyBytes` rather than
the smaller bound every other call uses. For a large artifact that is a fact to
plan around rather than a setting to change.

`client.repos.admin` was the largest single group in the library until its four
nested groups were split out of it; the file-write operations — `createFile`,
`updateFile`, `deleteFile`, `changeFiles` — are now on
`client.repos.admin.contents`. See [Writing data](../guides/08-writing-data.md).

Note that `repositories.hooks` is the package for four different groups (hooks,
wiki, flags, issue config); the package name is a historical grouping and not a
claim that a wiki is a hook.

---

## `client.users` — accounts

`UserApi` holds the reads that name an account, or that mean "whoever the
credentials are".

**8 operations:** `current`, `get`, `search`, `repositories`, `followers`,
`following`, `currentKeys`, `keys`

`/user/…` means "the configured credentials" and needs a token; `/users/{username}/…`
names an account. Do not assume the second family is anonymous — see
[Authentication](../guides/02-authentication.md).

### Nested groups

| Accessor | Class | Operations | Covers |
| --- | --- | ---: | --- |
| `client.users.account` | `users.account.UserAccountApi` | 10 | the authenticated account itself: settings, avatar, email addresses, its repositories and teams |
| `client.users.actions` | `users.account.UserActionApi` | 13 | the authenticated account's own Actions configuration: runners, secrets, variables |
| `client.users.applications` | `users.account.UserApplicationApi` | 5 | the OAuth2 applications the account has registered |
| `client.users.hooks` | `users.account.UserHookApi` | 5 | the webhooks the account owns |
| `client.users.quota` | `users.account.UserQuotaApi` | 5 | what the account may store, and what is taking up the room |
| `client.users.social` | `users.social.UserSocialApi` | 21 | the social graph: follows, stars, watches, blocks, stopwatches, tracked time, activity feeds, the contribution heatmap |
| `client.users.keys` | `users.social.UserKeyApi` | 10 | SSH keys, GPG keys, and the handshake that proves a GPG key |
| `client.users.tokens` | `users.social.UserTokenApi` | 3 | personal access tokens: listing, minting, revoking |

`client.users.tokens.create` is the only operation in the library whose success
carries a working credential. It is returned once, as an `ApiToken`, which
renders as `***` everywhere. See
[Authentication](../guides/02-authentication.md).

---

## `client.issues` — issues

**14 operations:** `list`, `get`, `create`, `edit`, `listComments`,
`createComment`, `listLabels`, `createLabel`, `listMilestones`, `getMilestone`,
`search`, `delete`, `setDeadline`, `timeline`

Filters are one `IssueQuery` value rather than eight optional parameters, and an
issue's `state` is a `LifecycleState` ADT whose `Closed` case carries the closing
timestamp — so "closed" and "when" cannot get out of step.

### Nested groups

| Accessor | Class | Operations | Covers |
| --- | --- | ---: | --- |
| `client.issues.comments` | `issues.IssueCommentApi` | 6 | reading, editing and deleting a comment once it exists; listing every comment in a repository |
| `client.issues.dependencies` | `issues.IssueDependencyApi` | 6 | what an issue depends on and what it blocks — one edge, read from both ends |
| `client.issues.pins` | `issues.IssuePinApi` | 3 | the repository's pinned-issue shortlist and its order |
| `client.issues.attachments` | `issues.IssueAttachmentApi` | 10 | files attached to an issue and files attached to a comment |
| `client.issues.reactions` | `issues.IssueReactionApi` | 6 | emoji reactions on an issue and on a comment |
| `client.issues.labels` | `issues.IssueLabelApi` | 8 | a repository's labels once they exist, and which of them are on an issue |
| `client.issues.milestones` | `issues.IssueMilestoneApi` | 3 | creating, editing and deleting milestones |
| `client.issues.times` | `issues.IssueTimeApi` | 7 | the stopwatch that measures work as it happens, and the log of what was worked |
| `client.issues.subscriptions` | `issues.IssueSubscriptionApi` | 4 | who is following an issue, and whether the authenticated account is one of them |

Creating a label and listing labels are on `IssueApi`; getting, editing and
deleting one are on `IssueLabelApi`. That split follows Forgejo's own paths
rather than a tidier scheme.

---

## `client.pulls` — pull requests

**13 operations:** `list`, `get`, `create`, `edit`, `merge`, `commits`,
`files`, `pinned`, `getByBaseHead`, `download`, `isMerged`,
`cancelScheduledMerge`, `updateBranch`

Commits and changed files live here because they describe the proposed change
itself. The verdicts on it are a nested group of their own.

### Nested groups

| Accessor | Class | Operations | Covers |
| --- | --- | ---: | --- |
| `client.pulls.reviews` | `pulls.PullRequestReviewApi` | 13 | reviews, their inline comments, and who has been asked to review |

`client.pulls.reviews.list` lists the reviews of one pull request;
`create`, `submit`, `dismiss` and `undismiss` move one through its lifecycle;
`comments`, `createComment`, `getComment` and `deleteComment` are the remarks
anchored to diff lines, which are *not* the pull request's conversation —
ordinary comments live on the issue endpoints, because Forgejo stores them
there. `request` and `removeRequests` ask people to review and take the
request back.

`PullRequestState` is `Open | Closed | Merged`, folded from Forgejo's `state`
string *and* its separate `merged` boolean — reading a merged pull request as
merely closed is the bug that shape prevents. `merge` returns `Future[Unit]`
because Forgejo answers `200` with no body.

---

## `client.organizations` — organisations and teams

**16 operations:** `get`, `list`, `create`, `edit`, `delete`, `rename`,
`updateAvatar`, `deleteAvatar`, `repositories`, `createRepository`,
`createRepositoryDeprecated`, `teams`, `getTeam`, `teamMembers`,
`teamRepositories`, `activities`

Teams are rooted at `/teams/{id}` rather than under the organisation, which is
why `getTeam` takes only an id.

### Nested groups

| Accessor | Class | Operations | Covers |
| --- | --- | ---: | --- |
| `client.organizations.hooks` | `organizations.OrganizationHookApi` | 5 | the organisation's webhooks |
| `client.organizations.labels` | `organizations.OrganizationLabelApi` | 5 | the shared label set its repositories may draw from |
| `client.organizations.members` | `organizations.OrganizationMemberApi` | 13 | who belongs, who says so publicly, who is blocked, and which organisations a given account is in |
| `client.organizations.teamAdmin` | `organizations.OrganizationTeamApi` | 11 | creating and changing teams, and deciding who and what they reach |
| `client.organizations.quota` | `organizations.OrganizationQuotaApi` | 5 | the organisation's storage limits, usage, and what is using it |
| `client.organizations.actions` | `organizations.actions.OrganizationActionApi` | 14 | the runners the organisation owns, and the secrets and variables its repositories inherit |

`teamAdmin` is named that way because `client.organizations.teams` is already an
operation — the listing — on `OrganizationApi`.

---

## `client.notifications` — the inbox

**7 operations:** `list`, `markAllRead`, `unreadCount`, `getThread`,
`markThreadRead`, `listRepository`, `markRepositoryRead`

All of them require a token; there is no anonymous inbox.
`NotificationQuery.Empty` is unread-only, matching the endpoint's own default. A
subject type this library has not seen decodes to
`NotificationSubjectType.Other(raw)` rather than failing the page, because
Forgejo adds subject types between releases.

---

## `client.misc` — the instance itself

**17 operations:** `apiSettings`, `repositorySettings`, `attachmentSettings`,
`signingKey`, `renderMarkdown`, `renderMarkdownRaw`, `uiSettings`,
`sshSigningKey`, `gitignoreTemplates`, `gitignoreTemplate`, `labelTemplates`,
`labelTemplate`, `licenseTemplates`, `licenseTemplate`, `renderMarkup`,
`nodeInfo`, `actionsRun`

`apiSettings()` is the endpoint that explains the pagination hazard the rest of
this library is built around: `maxResponseItems` is the ceiling Forgejo silently
clamps `limit` to. Read it once on a self-hosted instance. See
[Self-hosted instances](../guides/09-self-hosted.md).

`signingKey()` and `sshSigningKey()` return `Option`, because an instance that
does not sign commits is a legitimate answer and not a `404`.

---

## What is not here

Three tag groups are out of scope for v1 and have no accessor at all:
`admin` (instance administration), `activitypub` (federation) and `package` (the
package registry).
[`docs/API_INVENTORY.md`](../project/API_INVENTORY.md) has the endpoint-level
checklist and the honest percentage.

No group carries its own `listAll`. Walking every page of any listing is
`com.worxbend.codeberg4s.paging.PageWalk`, which takes the listing operation as
an argument — see [Pagination](../guides/04-pagination.md) for why the rule lives
in one place rather than thirty-eight.

## Reading the Scaladoc

Every class above documents, per operation:

- the HTTP method and path it calls;
- what it returns, and why that shape rather than another;
- every `CodebergError` case it can produce, including which statuses to expect;
- its `RetryEligibility`, with the argument for it — this is the one thing you
  cannot infer from the signature;
- whether the model was built from a captured response or read off the
  specification. That distinction is stated openly, because a model derived from
  the specification alone is less trustworthy: the specification declares no
  `required` fields on any response and no `nullable` anywhere.

Class-level Scaladoc additionally covers what the group's paths have in common,
what authentication they need, and any Forgejo behaviour worth warning about.
