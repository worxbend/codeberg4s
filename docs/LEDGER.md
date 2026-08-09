# Deduplication ledger

Several Forgejo models appear in more than one endpoint group. `User` is
embedded in issues, pull requests, repositories, comments, releases and
notifications; `Label` and `Milestone` appear in issues and pulls; `Repository`
appears inside pull requests and notifications.

The rule from `PLAN.md` §7: **the first wave that needs a shared model owns it.**
Later waves import it and must not redefine, fork, or "temporarily" copy it.
A duplicated model is a review-blocking defect — and it is what PMD CPD catches.
The duplication gate is now real: `scripts/cpd.sh` runs PMD 7.26.0's Scala
tokenizer over the production sources and, at the 40-token threshold, reports
**378 duplication groups** as of 2026-08-09. Several of them are exactly the
helpers this file lists below. `./verify.sh --with-slow` does not fail on that
number by itself — it compares it against `CPD_BASELINE_GROUPS` in `verify.sh`,
which records today's count, and fails on any increase. Bringing the count down
is what closes these entries; the baseline is then lowered in the same commit so
the ground gained is held.

This file records who owns what. A wave updates it as part of its definition of
done, in the same commit that introduces the model.

## Ownership

| Model                | Domain package                          | Owned by | Consumed by                              |
| -------------------- | ---------------------------------------- | -------- | ---------------------------------------- |
| `User`               | `com.worxbend.codeberg4s.users`          | wave 1   | repos, issues, pulls, orgs, notifications |
| `Repository`         | `com.worxbend.codeberg4s.repositories`   | wave 2   | pulls, notifications, orgs                |
| `RepositoryPermissions` | `com.worxbend.codeberg4s.repositories` | wave 2   | — (see the correction below)               |
| `Branch`, `Tag`      | `com.worxbend.codeberg4s.repositories`   | wave 2   | pulls                                     |
| `Commit`             | `com.worxbend.codeberg4s.repositories`   | wave 2   | pulls                                     |
| `RepositoryContent`  | `com.worxbend.codeberg4s.repositories`   | wave 2   | —                                         |
| `Release`, `Asset`   | `com.worxbend.codeberg4s.repositories`   | wave 2   | —                                         |
| `Label`              | `com.worxbend.codeberg4s.issues`         | wave 3   | pulls                                     |
| `Milestone`          | `com.worxbend.codeberg4s.issues`         | wave 3   | pulls                                     |
| `Comment`            | `com.worxbend.codeberg4s.issues`         | wave 3   | pulls                                     |
| `Issue`              | `com.worxbend.codeberg4s.issues`         | wave 3   | notifications                             |
| `PullRequest`        | `com.worxbend.codeberg4s.pulls`          | wave 4   | — (see the correction below)               |
| `Review`             | `com.worxbend.codeberg4s.pulls`          | wave 4   | —                                         |
| `Organization`       | `com.worxbend.codeberg4s.organizations`  | wave 5   | —                                         |
| `Team`               | `com.worxbend.codeberg4s.organizations`  | wave 5   | —                                         |
| `NotificationThread` | `com.worxbend.codeberg4s.notifications`  | wave 6   | —                                         |

Two rows of that table were written before the waves ran and predicted a reuse
that did not happen. Both are corrected rather than deleted, because the
prediction is what a later reader would otherwise repeat:

- **`RepositoryPermissions` is not consumed by orgs.** Wave 5 needed a *team's*
  permission over a repository, which Forgejo models as the string enum
  `none|read|write|admin|owner`, not as the three booleans in
  `RepositoryPermissions`. It owns `organizations.TeamPermission` instead. These
  are different concepts with the same English name; collapsing them would have
  produced a type that answers `admin = true` for a team whose permission is
  `owner` and lose the ordering that `TeamPermission.allows` depends on.
- **`PullRequest` is not embedded in a notification.** Forgejo's notification
  subject is a flat `{type, title, state, url, …}` object with no pull-request
  body inside it, so wave 6 owns `NotificationSubject` and links out by URL. The
  notification group consumes `repositories.Repository` and nothing from
  `…pulls`.

Shared identifiers (`Owner`, `RepoName`, `RepoSlug`) and the shared value types
(`ApiToken`, `Page`, `PageParams`, `CodebergError`, `CallContext`) are foundation
types, owned by the domain module and predating every wave.

## Wire DTOs

Each domain model has exactly one wire DTO in `modules/codec`, in the matching
`…​.wire` package, with its `ReadWriter` beside it. The ownership rule applies
identically: `UserDto` is written once, by wave 1.

Where the API embeds a reduced form of a model — Forgejo sometimes returns a
`User` with only `id`, `login` and `avatar_url` inside an embedded object — do
**not** introduce a second DTO. Widen the existing DTO's optionality and record
the observed shape in `docs/HAZARDS.md`, so the golden fixture that proves it
stays discoverable.

## Additions made during implementation

Every wave introduced models the original table did not anticipate. They follow
the same ownership rule.

| Model                                                   | Package                                 | Owner   |
| ------------------------------------------------------- | --------------------------------------- | ------- |
| `Username`, `PublicKey`                                  | `…users`                                | wave 1  |
| `Branch`, `Tag`, `Commit`, `CommitDetails`, `CommitSummary` | `…repositories`                      | wave 2  |
| `Release`, `ReleaseAsset`, `RepositoryContent`, `ContentEntry` | `…repositories`                   | wave 2  |
| `BranchName`, `TagName`, `CommitSha`, `ContentPath`, `ReleaseId` | `…repositories`                 | wave 2  |
| `LifecycleState`, `IssueQuery`, `CreateIssue`, `EditIssue` | `…issues`                             | wave 3  |
| `ServerApiSettings`, `MarkdownRenderRequest`, `SigningKey` | `…miscellaneous`                      | wave 7  |

### Wave 4 — pulls

| Model                | Package   | Kind                | Notes                                                                                   |
| -------------------- | --------- | ------------------- | --------------------------------------------------------------------------------------- |
| `PullRequestBranch`  | `…pulls`  | model               | The `head`/`base` object. Its `repository` is a **full** `repositories.Repository`, decoded through `repositories.wire.RepositoryDto` — not the reduced meta object an issue carries. |
| `ChangedFile`        | `…pulls`  | model               | One entry of `GET …/pulls/{index}/files`. Reuses `repositories.CommitFileStatus` for `status` rather than adding a second status enum. |
| `PullRequestQuery`   | `…pulls`  | query               | Filter for `list`. Reuses `issues.StateFilter`, `issues.LabelId`, `issues.MilestoneId` and `repositories.BranchName`; adds only `PullRequestSort` and `PullRequestHead`. |
| `CreatePullRequest`  | `…pulls`  | command             | `of(title, head, base)` is the only constructor; the rest are `with…` copies.            |
| `EditPullRequest`    | `…pulls`  | command             | Reuses `issues.IssueStateChange` for open/close rather than defining a pulls-only one.   |
| `MergePullRequest`   | `…pulls`  | command             | `using(style)` plus `with…` copies. Carries `repositories.CommitSha` for the expected head. |
| `MergeStyle`         | `…pulls`  | enum                | `merge`, `rebase`, `rebase-merge`, `squash`, `fast-forward-only`, `manually-merged`.     |
| `ReviewState`        | `…pulls`  | enum                | `APPROVED`, `REQUEST_CHANGES`, `COMMENT`, `REQUEST_REVIEW`, `PENDING` — upper-case on the wire, unlike every other enum here. |
| `PullRequestSort`    | `…pulls`  | enum                | Forgejo's own spellings, `recentupdate` and friends, unhyphenated.                       |
| `PullRequestState`   | `…pulls`  | ADT                 | Open / Closed / Merged. Not a flat enum: `merged` in Forgejo is a boolean beside `state`, and folding the two into one type is what stops a caller reading a merged PR as merely closed. |
| `PullRequestHead`    | `…pulls`  | opaque `String`     | `branch(name)` or `crossRepository(owner, name)` — the `owner:branch` form the API wants. |
| `PullRequestNumber`, `ReviewId` | `…pulls` | opaque `Long` | Positive-only, via the package-private `PullIds`.                                        |
| `Review`             | `…pulls`  | model               | Reuses `users.User` and `repositories.CommitSha`.                                        |

### Wave 5 — organizations

| Model            | Package           | Kind            | Notes                                                                            |
| ---------------- | ----------------- | --------------- | --------------------------------------------------------------------------------- |
| `Organization`   | `…organizations`  | model           | **Reuses `users.UserVisibility` deliberately.** See the note below.                |
| `Team`           | `…organizations`  | model           | Carries `unitPermissions: Map[String, TeamPermission]` — Forgejo's per-unit map.   |
| `TeamPermission` | `…organizations`  | enum            | `none` / `read` / `write` / `admin` / `owner`, ordered by `rank`, compared with `allows`. |
| `OrgName`        | `…organizations`  | opaque `String` | Same shape as `users.Username`; kept separate for the reason `Username` is kept separate from `Owner`. |
| `TeamId`         | `…organizations`  | opaque `Long`   | Positive-only.                                                                     |

**`Organization.visibility` is `users.UserVisibility`, not a forked
`OrgVisibility`.** This is a deliberate reuse, not an oversight. Forgejo has one
visibility concept — the `public|limited|private` triple on `structs.User` — and
serves the identical string in the identical key for an organisation, because in
Gitea's data model an organisation *is* a user row. `golden/organization/` shows
the same three values as `golden/user/`. Wave 5 therefore consumes
`users.UserVisibility` and `users.wire`'s parsing of it. A second enum would have
had the same three cases, the same wire strings and the same parser, and would
have been exactly the duplication CPD exists to catch. The cost is a `…users`
import inside `…organizations`, which the ownership rule explicitly permits:
first wave to need a shared model owns it, later waves import it.

### Wave 6 — notifications

| Model                       | Package           | Kind            | Notes                                                                       |
| --------------------------- | ----------------- | --------------- | ----------------------------------------------------------------------------- |
| `NotificationThread`        | `…notifications`  | model           | Reuses `repositories.Repository` through `repositories.wire.RepositoryDto`.   |
| `NotificationSubject`       | `…notifications`  | model           | Flat: title, state and URLs. Deliberately does **not** embed `Issue` or `PullRequest` — Forgejo does not send them. |
| `NotificationSubjectType`   | `…notifications`  | enum + `Other`  | Open enum. An unrecognised type decodes to `Other(raw)` instead of failing the page, because Forgejo adds subject types between releases. |
| `NotificationStatus`        | `…notifications`  | enum            | `unread`, `read`, `pinned` — the `status-types` query parameter.              |
| `NotificationSubjectFilter` | `…notifications`  | enum            | `issue`, `pull`, `repository` — the `subject-type` query parameter. Distinct from `NotificationSubjectType`: the filter is a closed set the server accepts, the type is an open set it emits. |
| `NotificationQuery`         | `…notifications`  | query           | `Empty` is unread-only, matching the endpoint's own default.                 |
| `NotificationThreadId`      | `…notifications`  | opaque `Long`   | Positive-only.                                                                |
| `UnreadCount`               | `…notifications`  | opaque `Long`   | Zero-or-more, with `hasUnread`. Zero is a valid answer, so it is not an `Option`. |

`Username` deliberately does **not** reuse `Owner`. The two overlap in practice
but not in meaning: an `Owner` may be an organisation, a `Username` is always a
person. Collapsing them would let an organisation name reach an endpoint that
only accepts a user.

`RepositoryMetaDto` (in `…issues.wire`) is **not** a reduced `Repository` and
must not be decoded as one — Forgejo's `RepositoryMeta` carries `owner` as a
bare login string rather than a `User` object, so `RepositoryDto` fails on it.

An earlier revision of this file predicted that pull requests and notifications
would meet the same object and reuse this DTO. **They do not**, and the code is
the authority: `RepositoryMetaDto` has exactly one consumer, `IssueDto`.
`PullRequestBranchDto` and `NotificationThreadDto` both decode a full
`repositories.wire.RepositoryDto`, because the repository a PR branch points at
and the repository a notification belongs to are sent in full — including
`owner` as a `User` object and, in `golden/pull/single-open.json`, a populated
`parent` on a fork. Choosing the meta DTO there would fail every payload.

## Helpers awaiting promotion

Several lanes independently needed the same helpers and, being unable to edit a
package they did not own, wrote local copies. CPD now flags them, and they
should be promoted and the copies deleted. Waves 4–6 made this neither better
nor worse: they consumed the existing copies rather than adding new ones, except
for the paging pair, which grew by three.

CPD's own top findings confirm the list and add one the ledger had missed:
`issues.FilterToken.from` and `repositories.PathSegment.from` are the same
four-line validator with one character different, and `PathSegment` duplicates
itself internally. That is the third row below — the one about path-segment
validation — showing up as a measured 41-token clone rather than as a note.

| Helper                                | Currently                                        | Belongs in                       |
| ------------------------------------- | ------------------------------------------------ | -------------------------------- |
| element-wise `Vector[Dto]` conversion with per-index `JsonPath` | still three copies — `repositories.wire.Elements` (also used by orgs, notifications and pulls' commit list), `issues.wire.WireElements` (also used by `PullRequestDto`), `UserApi.each` | `codec.Wire` or `client.WireDecode` |
| the `page`/`limit` query pair          | six copies — `UserApi.pageQuery`, `RepositoryApi.window`, `OrganizationApi.window`, `IssueQueries.paging`, `PullRequestQueries.paging`, `NotificationQueries.paging` | `paging.PageParams`              |
| path-segment validation                | `repositories.PathSegment` is `private[repositories]`, so `Username.from` and `OrgName.from` each re-implement it | the domain module root |

## Pending claims

None outstanding. Waves 4, 5 and 6 each needed models from earlier waves —
`users.User`, `users.UserVisibility`, `repositories.Repository`,
`repositories.Commit`, `repositories.CommitSha`, `repositories.BranchName`,
`repositories.CommitFileStatus`, `issues.Label`, `issues.Milestone`,
`issues.LabelId`, `issues.MilestoneId`, `issues.StateFilter`,
`issues.IssueStateChange` — and every one was imported rather than redefined. No
wave had to add a model in an earlier wave's package, so no out-of-order
ownership was recorded.

A wave that discovers it needs a model an earlier wave should have owned adds a
row here, implements it in the earlier wave's package, and notes the
out-of-order ownership in the commit body.
