# Deduplication ledger

Several Forgejo models appear in more than one endpoint group. `User` is
embedded in issues, pull requests, repositories, comments, releases and
notifications; `Label` and `Milestone` appear in issues and pulls; `Repository`
appears inside pull requests and notifications.

The rule from `PLAN.md` §7: **the first wave that needs a shared model owns it.**
Later waves import it and must not redefine, fork, or "temporarily" copy it.
A duplicated model is a review-blocking defect — it is exactly what PMD CPD is
wired to catch.

This file records who owns what. A wave updates it as part of its definition of
done, in the same commit that introduces the model.

## Ownership

| Model                | Domain package                          | Owned by | Consumed by                              |
| -------------------- | ---------------------------------------- | -------- | ---------------------------------------- |
| `User`               | `com.worxbend.codeberg4s.users`          | wave 1   | repos, issues, pulls, orgs, notifications |
| `Repository`         | `com.worxbend.codeberg4s.repositories`   | wave 2   | pulls, notifications, orgs                |
| `Permission`         | `com.worxbend.codeberg4s.repositories`   | wave 2   | orgs                                      |
| `Branch`, `Tag`      | `com.worxbend.codeberg4s.repositories`   | wave 2   | pulls                                     |
| `Commit`             | `com.worxbend.codeberg4s.repositories`   | wave 2   | pulls                                     |
| `RepositoryContent`  | `com.worxbend.codeberg4s.repositories`   | wave 2   | —                                         |
| `Release`, `Asset`   | `com.worxbend.codeberg4s.repositories`   | wave 2   | —                                         |
| `Label`              | `com.worxbend.codeberg4s.issues`         | wave 3   | pulls                                     |
| `Milestone`          | `com.worxbend.codeberg4s.issues`         | wave 3   | pulls                                     |
| `Comment`            | `com.worxbend.codeberg4s.issues`         | wave 3   | pulls                                     |
| `Issue`              | `com.worxbend.codeberg4s.issues`         | wave 3   | notifications                             |
| `PullRequest`        | `com.worxbend.codeberg4s.pulls`          | wave 4   | notifications                             |
| `Review`             | `com.worxbend.codeberg4s.pulls`          | wave 4   | —                                         |
| `Organization`       | `com.worxbend.codeberg4s.organizations`  | wave 5   | —                                         |
| `Team`               | `com.worxbend.codeberg4s.organizations`  | wave 5   | —                                         |
| `NotificationThread` | `com.worxbend.codeberg4s.notifications`  | wave 6   | —                                         |

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

## Pending claims

None. A wave that discovers it needs a model an earlier wave should have owned
adds the row here, implements it in the earlier wave's package, and notes the
out-of-order ownership in the commit body.
