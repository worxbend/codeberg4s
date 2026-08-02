# API Inventory — Codeberg / Forgejo REST API v1

Generated from the **pinned** spec, never a live fetch.

| | |
| --- | --- |
| Spec file | `spec/swagger.v1.json` |
| Spec sha256 | `90c40aa5e69a387700d1f28f6e61ba3ed01837b96e21fdfd8795e944fddaf9d5` |
| Spec `info.version` | `16.0.0-dev-668-1bdb1938+gitea-1.22.0` |
| Swagger version | `2.0` |
| `basePath` | `/api/v1` |
| Paths | 326 |
| Operations | 506 |
| Definitions | 246 |

Provenance: [`SPEC_PROVENANCE.md`](SPEC_PROVENANCE.md). Verified spec/reality
divergences: [`HAZARDS.md`](HAZARDS.md).

This file is the roadmap later waves tick off. `operationId` and `Path` are
copied verbatim from the pinned spec and must not be edited by hand — regenerate
if the spec is re-pinned.

---

## 0. Implementation status

A box is ticked only when the operation is reachable from `CodebergClient` on
both rails. The ticked set below was derived from the `*Api` classes under
`modules/client/src/com/worxbend/codeberg4s/`, not from a hand-kept list — one
tick per request builder those classes actually construct.

| | |
| --- | --- |
| In-scope v1 rows in §3 | **439** (tag-weighted; 438 distinct — `createCurrentUserRepo` is listed under both `repository` and `user`) |
| Implemented | **61** |
| Coverage of the in-scope surface | **13.9 %** |
| Coverage of the whole spec (506 operations) | 12.1 % |

| Tag | Implemented | In scope | Done |
| --- | ---: | ---: | ---: |
| `notification` | 7 | 7 | **100 %** |
| `settings` | 3 | 4 | 75 % |
| `miscellaneous` | 4 | 14 | 29 % |
| `issue` | 10 | 67 | 15 % |
| `organization` | 10 | 69 | 15 % |
| `user` | 8 | 80 | 10 % |
| `repository` | 19 | 198 | 10 % |
| **total** | **61** | **439** | **13.9 %** |

`repository` carries 19 because pull requests live under `/repos/…` in the spec
and are therefore tagged `repository`: 11 of the 19 are wave 2 (`client.repos`)
and 8 are wave 4 (`client.pulls`).

The seven groups shipped so far are deliberately the *read-and-common-write*
core, not a uniform slice: the long tail of `repository` (actions, hooks, keys,
wikis, packages, attachments, teams-on-repos) and of `user` (settings, blocks,
stars, GPG keys, quotas) is untouched. `PLAN.md` §10 requires 100 % of the
in-scope surface for 0.1.0, so the number above is the honest distance to that
gate, not a shortfall in the waves that ran.

---

## 1. Tag summary

| Tag | Operations | GET | POST | PATCH | PUT | DELETE | Scope |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `repository` | 198 | 105 | 42 | 10 | 10 | 31 | **in-scope v1** |
| `user` | 80 | 47 | 12 | 3 | 6 | 12 | **in-scope v1** |
| `organization` | 69 | 36 | 10 | 4 | 7 | 12 | **in-scope v1** |
| `issue` | 67 | 23 | 16 | 8 | 2 | 18 | **in-scope v1** |
| `admin` | 51 | 22 | 13 | 3 | 2 | 11 | out-of-scope |
| `miscellaneous` | 14 | 11 | 3 | 0 | 0 | 0 | **in-scope v1** |
| `activitypub` | 11 | 6 | 5 | 0 | 0 | 0 | out-of-scope |
| `notification` | 7 | 4 | 0 | 1 | 2 | 0 | **in-scope v1** |
| `package` | 6 | 3 | 2 | 0 | 0 | 1 | deferred |
| `settings` | 4 | 4 | 0 | 0 | 0 | 0 | **in-scope v1** |
| **total (tag-weighted)** | **507** | | | | | | |

Tag counts sum to 507 against 506 distinct operations because
`POST /user/repos` (`createCurrentUserRepo`) is tagged both `repository` and
`user`; it is listed under both tables below and must only be implemented once.

In-scope v1 surface: **439 tag-weighted operations** (438 distinct).

---

## 2. Scope decisions (PLAN.md §0)

### In scope for v1

| Tag | Rationale |
| --- | --- |
| `user` | Current user, keys, follows, by-name lookups. PLAN.md §7 wave 1. |
| `repository` | The bulk of the surface: CRUD, contents, branches, tags, releases, topics, pulls. PLAN.md §7 waves 2 and 4. |
| `issue` | Issues, comments, labels, milestones, reactions, tracked time. PLAN.md §7 wave 3. |
| `organization` | Orgs, teams, membership. PLAN.md §7 wave 5. |
| `notification` | PLAN.md §7 wave 6. |
| `miscellaneous` | Markdown render, search, version, signing key. PLAN.md §7 wave 7. |
| `settings` | Instance capability discovery; cheap and useful for clients. PLAN.md §7 wave 7. |

### Deferred / out of scope

| Tag | Ops | Status | Reason |
| --- | ---: | --- | --- |
| `package` | 6 | deferred | Package registry. Not in the v1 value order (PLAN.md §7); revisit after G3-final. |
| `activitypub` | 11 | out-of-scope | Federation endpoints. Explicit non-goal, PLAN.md §0. |
| `admin` | 51 | out-of-scope | Instance administration. Explicit non-goal, PLAN.md §0. |

**OAuth2 token acquisition** is out of scope as a *flow*, per PLAN.md §0: the
library accepts a pre-obtained token only. The endpoints that manage OAuth2
applications are tagged `user` and are therefore listed in the `user` table
below — they are marked `out-of-scope` in the Notes column rather than removed,
so the inventory stays a faithful mirror of the spec.

---

## 3. In-scope endpoint checklists

Legend — **Auth**: `optional` = works anonymously against public data, a token
only widens visibility; `token` = 401 without credentials; `token (admin)` =
instance-admin token; `token?` = a mutation-shaped endpoint that performs no
mutation, so anonymous access is plausible but unverified.

The Auth column is **derived from path shape and HTTP method, not read from
the spec**: the spec declares one global `security` block covering all 506
operations with zero per-operation overrides, so it carries no per-endpoint
auth information whatsoever (HAZARDS.md §2). Two derivations are calibrated
against live probes — `GET /user` → 401 and
`GET /repos/forgejo/forgejo/issues` → 200 anonymous. The rest follow the rule
`/user…` current-user scope and any mutation need a token; `/users/…` public
lookups do not. Treat the column as a planning aid and confirm per endpoint
when implementing it.

**Paged**: query parameters the spec declares. `page+limit` is the honoured
pagination contract (verified live, HAZARDS.md §5). `—` on an array-returning
GET is an unbounded collection — flagged in the Notes column.

### 3.1 `user` — 80 operations

GET 47 · POST 12 · PATCH 3 · PUT 6 · DELETE 12

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [x] | GET | `/user` | `userGetCurrent` | token | — |  |
| [ ] | GET | `/user/actions/runners` | `getUserRunners` | token | page+limit |  |
| [ ] | POST | `/user/actions/runners` | `registerUserRunner` | token | — |  |
| [ ] | GET | `/user/actions/runners/jobs` | `userSearchRunJobs` | token | — | array response, no paging params |
| [ ] | GET | `/user/actions/runners/registration-token` | `userGetRunnerRegistrationToken` | token | — |  |
| [ ] | DELETE | `/user/actions/runners/{runner_id}` | `deleteUserRunner` | token | — |  |
| [ ] | GET | `/user/actions/runners/{runner_id}` | `getUserRunner` | token | — |  |
| [ ] | DELETE | `/user/actions/secrets/{secretname}` | `deleteUserSecret` | token | — |  |
| [ ] | PUT | `/user/actions/secrets/{secretname}` | `updateUserSecret` | token | — |  |
| [ ] | GET | `/user/actions/variables` | `getUserVariablesList` | token | page+limit |  |
| [ ] | DELETE | `/user/actions/variables/{variablename}` | `deleteUserVariable` | token | — |  |
| [ ] | GET | `/user/actions/variables/{variablename}` | `getUserVariable` | token | — |  |
| [ ] | POST | `/user/actions/variables/{variablename}` | `createUserVariable` | token | — |  |
| [ ] | PUT | `/user/actions/variables/{variablename}` | `updateUserVariable` | token | — |  |
| [ ] | POST | `/user/activitypub/follow` | `userCurrentActivityPubFollow` | token | — |  |
| [ ] | GET | `/user/applications/oauth2` | `userGetOAuth2Applications` | token | page+limit | out-of-scope (OAuth2 app mgmt) |
| [ ] | POST | `/user/applications/oauth2` | `userCreateOAuth2Application` | token | — | out-of-scope (OAuth2 app mgmt) |
| [ ] | DELETE | `/user/applications/oauth2/{id}` | `userDeleteOAuth2Application` | token | — | out-of-scope (OAuth2 app mgmt) |
| [ ] | GET | `/user/applications/oauth2/{id}` | `userGetOAuth2Application` | token | — | out-of-scope (OAuth2 app mgmt) |
| [ ] | PATCH | `/user/applications/oauth2/{id}` | `userUpdateOAuth2Application` | token | — | out-of-scope (OAuth2 app mgmt) |
| [ ] | DELETE | `/user/avatar` | `userDeleteAvatar` | token | — |  |
| [ ] | POST | `/user/avatar` | `userUpdateAvatar` | token | — |  |
| [ ] | PUT | `/user/block/{username}` | `userBlockUser` | token | — |  |
| [ ] | DELETE | `/user/emails` | `userDeleteEmail` | token | — |  |
| [ ] | GET | `/user/emails` | `userListEmails` | token | — | array response, no paging params |
| [ ] | POST | `/user/emails` | `userAddEmail` | token | — | array response, no paging params |
| [ ] | GET | `/user/followers` | `userCurrentListFollowers` | token | page+limit |  |
| [ ] | GET | `/user/following` | `userCurrentListFollowing` | token | page+limit |  |
| [ ] | DELETE | `/user/following/{username}` | `userCurrentDeleteFollow` | token | — |  |
| [ ] | GET | `/user/following/{username}` | `userCurrentCheckFollowing` | token | — |  |
| [ ] | PUT | `/user/following/{username}` | `userCurrentPutFollow` | token | — |  |
| [ ] | GET | `/user/gpg_key_token` | `getVerificationToken` | token | — |  |
| [ ] | POST | `/user/gpg_key_verify` | `userVerifyGPGKey` | token | — |  |
| [ ] | GET | `/user/gpg_keys` | `userCurrentListGPGKeys` | token | page+limit |  |
| [ ] | POST | `/user/gpg_keys` | `userCurrentPostGPGKey` | token | — |  |
| [ ] | DELETE | `/user/gpg_keys/{id}` | `userCurrentDeleteGPGKey` | token | — |  |
| [ ] | GET | `/user/gpg_keys/{id}` | `userCurrentGetGPGKey` | token | — |  |
| [ ] | GET | `/user/hooks` | `userListHooks` | token | page+limit |  |
| [ ] | POST | `/user/hooks` | `userCreateHook` | token | — |  |
| [ ] | DELETE | `/user/hooks/{id}` | `userDeleteHook` | token | — |  |
| [ ] | GET | `/user/hooks/{id}` | `userGetHook` | token | — |  |
| [ ] | PATCH | `/user/hooks/{id}` | `userEditHook` | token | — |  |
| [x] | GET | `/user/keys` | `userCurrentListKeys` | token | page+limit |  |
| [ ] | POST | `/user/keys` | `userCurrentPostKey` | token | — |  |
| [ ] | DELETE | `/user/keys/{id}` | `userCurrentDeleteKey` | token | — |  |
| [ ] | GET | `/user/keys/{id}` | `userCurrentGetKey` | token | — |  |
| [ ] | GET | `/user/list_blocked` | `userListBlockedUsers` | token | page+limit |  |
| [ ] | GET | `/user/quota` | `userGetQuota` | token | — |  |
| [ ] | GET | `/user/quota/artifacts` | `userListQuotaArtifacts` | token | page+limit |  |
| [ ] | GET | `/user/quota/attachments` | `userListQuotaAttachments` | token | page+limit |  |
| [ ] | GET | `/user/quota/check` | `userCheckQuota` | token | — |  |
| [ ] | GET | `/user/quota/packages` | `userListQuotaPackages` | token | page+limit |  |
| [ ] | GET | `/user/repos` | `userCurrentListRepos` | token | page+limit |  |
| [ ] | POST | `/user/repos` | `createCurrentUserRepo` | token | — | dual-tagged `repository`+`user`; implement once |
| [ ] | GET | `/user/settings` | `getUserSettings` | token | — |  |
| [ ] | PATCH | `/user/settings` | `updateUserSettings` | token | — |  |
| [ ] | GET | `/user/starred` | `userCurrentListStarred` | token | page+limit |  |
| [ ] | DELETE | `/user/starred/{owner}/{repo}` | `userCurrentDeleteStar` | token | — |  |
| [ ] | GET | `/user/starred/{owner}/{repo}` | `userCurrentCheckStarring` | token | — |  |
| [ ] | PUT | `/user/starred/{owner}/{repo}` | `userCurrentPutStar` | token | — |  |
| [ ] | GET | `/user/stopwatches` | `userGetStopWatches` | token | page+limit |  |
| [ ] | GET | `/user/subscriptions` | `userCurrentListSubscriptions` | token | page+limit |  |
| [ ] | GET | `/user/teams` | `userListTeams` | token | page+limit |  |
| [ ] | GET | `/user/times` | `userCurrentTrackedTimes` | token | page+limit |  |
| [ ] | PUT | `/user/unblock/{username}` | `userUnblockUser` | token | — |  |
| [x] | GET | `/users/search` | `userSearch` | optional | page+limit |  |
| [x] | GET | `/users/{username}` | `userGet` | optional | — |  |
| [ ] | GET | `/users/{username}/activities/feeds` | `userListActivityFeeds` | optional | page+limit |  |
| [x] | GET | `/users/{username}/followers` | `userListFollowers` | optional | page+limit |  |
| [x] | GET | `/users/{username}/following` | `userListFollowing` | optional | page+limit |  |
| [ ] | GET | `/users/{username}/following/{target}` | `userCheckFollowing` | optional | — |  |
| [ ] | GET | `/users/{username}/gpg_keys` | `userListGPGKeys` | optional | page+limit |  |
| [ ] | GET | `/users/{username}/heatmap` | `userGetHeatmapData` | optional | — | array response, no paging params |
| [x] | GET | `/users/{username}/keys` | `userListKeys` | optional | page+limit |  |
| [x] | GET | `/users/{username}/repos` | `userListRepos` | optional | page+limit |  |
| [ ] | GET | `/users/{username}/starred` | `userListStarred` | optional | page+limit |  |
| [ ] | GET | `/users/{username}/subscriptions` | `userListSubscriptions` | optional | page+limit |  |
| [ ] | GET | `/users/{username}/tokens` | `userGetTokens` | optional | page+limit |  |
| [ ] | POST | `/users/{username}/tokens` | `userCreateToken` | token | — |  |
| [ ] | DELETE | `/users/{username}/tokens/{token}` | `userDeleteAccessToken` | token | — |  |

### 3.2 `repository` — 198 operations

GET 105 · POST 42 · PATCH 10 · PUT 10 · DELETE 31

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [ ] | POST | `/repos/migrate` | `repoMigrate` | token | — |  |
| [x] | GET | `/repos/search` | `repoSearch` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}` | `repoDelete` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}` | `repoGet` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}` | `repoEdit` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/artifacts` | `ListActionArtifacts` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/actions/artifacts/{artifact_id}` | `DeleteActionArtifact` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/artifacts/{artifact_id}` | `GetActionArtifact` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip` | `DownloadActionArtifact` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/jobs/{job_id}/logs` | `repoGetActionJobLogs` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runners` | `getRepoRunners` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/actions/runners` | `registerRepoRunner` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runners/jobs` | `repoSearchRunJobs` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runners/registration-token` | `repoGetRunnerRegistrationToken` | optional | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/actions/runners/{runner_id}` | `deleteRepoRunner` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runners/{runner_id}` | `getRepoRunner` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runs` | `ListActionRuns` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/actions/runs/{run_id}` | `DeleteActionRun` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runs/{run_id}` | `ActionRun` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runs/{run_id}/artifacts` | `ListActionRunArtifacts` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/actions/runs/{run_id}/cancel` | `CancelActionRun` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runs/{run_id}/jobs` | `ListActionRunJobs` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/actions/runs/{run_id}/logs` | `repoGetActionRunLogs` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/secrets` | `repoListActionsSecrets` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/actions/secrets/{secretname}` | `deleteRepoSecret` | token | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/actions/secrets/{secretname}` | `updateRepoSecret` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/tasks` | `ListActionTasks` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/variables` | `getRepoVariablesList` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/actions/variables/{variablename}` | `deleteRepoVariable` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/actions/variables/{variablename}` | `getRepoVariable` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/actions/variables/{variablename}` | `createRepoVariable` | token | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/actions/variables/{variablename}` | `updateRepoVariable` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/actions/workflows/{workflowfilename}/dispatches` | `DispatchWorkflow` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/activities/feeds` | `repoListActivityFeeds` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/archive/{archive}` | `repoGetArchive` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/assignees` | `repoGetAssignees` | optional | — | array response, no paging params |
| [ ] | DELETE | `/repos/{owner}/{repo}/avatar` | `repoDeleteAvatar` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/avatar` | `repoUpdateAvatar` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/branch_protections` | `repoListBranchProtection` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/branch_protections` | `repoCreateBranchProtection` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/branch_protections/{name}` | `repoDeleteBranchProtection` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/branch_protections/{name}` | `repoGetBranchProtection` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/branch_protections/{name}` | `repoEditBranchProtection` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/branches` | `repoListBranches` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/branches` | `repoCreateBranch` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/branches/{branch}` | `repoDeleteBranch` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/branches/{branch}` | `repoGetBranch` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/branches/{branch}` | `repoUpdateBranch` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/collaborators` | `repoListCollaborators` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/collaborators/{collaborator}` | `repoDeleteCollaborator` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/collaborators/{collaborator}` | `repoCheckCollaborator` | optional | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/collaborators/{collaborator}` | `repoAddCollaborator` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/collaborators/{collaborator}/permission` | `repoGetRepoPermissions` | optional | — |  |
| [x] | GET | `/repos/{owner}/{repo}/commits` | `repoGetAllCommits` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/commits/{ref}/status` | `repoGetCombinedStatusByRef` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/commits/{ref}/statuses` | `repoListStatusesByRef` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/commits/{sha}/pull` | `repoGetCommitPullRequest` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/compare/{basehead}` | `repoCompareDiff` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/contents` | `repoGetContentsList` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/contents` | `repoChangeFiles` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/contents/{filepath}` | `repoDeleteFile` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/contents/{filepath}` | `repoGetContents` | optional | — | **union response** — see HAZARDS.md §3 |
| [ ] | POST | `/repos/{owner}/{repo}/contents/{filepath}` | `repoCreateFile` | token | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/contents/{filepath}` | `repoUpdateFile` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/convert` | `repoConvert` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/diffpatch` | `repoApplyDiffPatch` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/editorconfig/{filepath}` | `repoGetEditorConfig` | optional | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/flags` | `repoDeleteAllFlags` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/flags` | `repoListFlags` | optional | — | array response, no paging params |
| [ ] | PUT | `/repos/{owner}/{repo}/flags` | `repoReplaceAllFlags` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/flags/{flag}` | `repoDeleteFlag` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/flags/{flag}` | `repoCheckFlag` | optional | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/flags/{flag}` | `repoAddFlag` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/forks` | `listForks` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/forks` | `createFork` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/git/blobs` | `GetBlobs` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/git/blobs/{sha}` | `GetBlob` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/git/commits/{sha}` | `repoGetSingleCommit` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/git/commits/{sha}.{diffType}` | `repoDownloadCommitDiffOrPatch` | optional | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/git/notes/{sha}` | `repoRemoveNote` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/git/notes/{sha}` | `repoGetNote` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/git/notes/{sha}` | `repoSetNote` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/git/refs` | `repoListAllGitRefs` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/git/refs/{ref}` | `repoListGitRefs` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/git/tags/{sha}` | `GetAnnotatedTag` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/git/trees/{sha}` | `GetTree` | optional | page only |  |
| [ ] | GET | `/repos/{owner}/{repo}/hooks` | `repoListHooks` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/hooks` | `repoCreateHook` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/hooks/git` | `repoListGitHooks` | optional | — | array response, no paging params |
| [ ] | DELETE | `/repos/{owner}/{repo}/hooks/git/{id}` | `repoDeleteGitHook` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/hooks/git/{id}` | `repoGetGitHook` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/hooks/git/{id}` | `repoEditGitHook` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/hooks/{id}` | `repoDeleteHook` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/hooks/{id}` | `repoGetHook` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/hooks/{id}` | `repoEditHook` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/hooks/{id}/tests` | `repoTestHook` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issue_config` | `repoGetIssueConfig` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issue_config/validate` | `repoValidateIssueConfig` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issue_templates` | `repoGetIssueTemplates` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/issues/pinned` | `repoListPinnedIssues` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/keys` | `repoListKeys` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/keys` | `repoCreateKey` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/keys/{id}` | `repoDeleteKey` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/keys/{id}` | `repoGetKey` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/languages` | `repoGetLanguages` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/media/{filepath}` | `repoGetRawFileOrLFS` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/mirror-sync` | `repoMirrorSync` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/new_pin_allowed` | `repoNewPinAllowed` | optional | — |  |
| [x] | GET | `/repos/{owner}/{repo}/pulls` | `repoListPullRequests` | optional | page+limit |  |
| [x] | POST | `/repos/{owner}/{repo}/pulls` | `repoCreatePullRequest` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/pinned` | `repoListPinnedPullRequests` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/{base}/{head}` | `repoGetPullRequestByBaseHead` | optional | — |  |
| [x] | GET | `/repos/{owner}/{repo}/pulls/{index}` | `repoGetPullRequest` | optional | — |  |
| [x] | PATCH | `/repos/{owner}/{repo}/pulls/{index}` | `repoEditPullRequest` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/{index}.{diffType}` | `repoDownloadPullDiffOrPatch` | optional | — |  |
| [x] | GET | `/repos/{owner}/{repo}/pulls/{index}/commits` | `repoGetPullRequestCommits` | optional | page+limit |  |
| [x] | GET | `/repos/{owner}/{repo}/pulls/{index}/files` | `repoGetPullRequestFiles` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/pulls/{index}/merge` | `repoCancelScheduledAutoMerge` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/{index}/merge` | `repoPullRequestIsMerged` | optional | — |  |
| [x] | POST | `/repos/{owner}/{repo}/pulls/{index}/merge` | `repoMergePullRequest` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/pulls/{index}/requested_reviewers` | `repoDeletePullReviewRequests` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/requested_reviewers` | `repoCreatePullReviewRequests` | token | — | array response, no paging params |
| [x] | GET | `/repos/{owner}/{repo}/pulls/{index}/reviews` | `repoListPullReviews` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/reviews` | `repoCreatePullReview` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}` | `repoDeletePullReview` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}` | `repoGetPullReview` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}` | `repoSubmitPullReview` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments` | `repoGetPullReviewComments` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments` | `repoCreatePullReviewComment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}` | `repoDeletePullReviewComment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}` | `repoGetPullReviewComment` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/dismissals` | `repoDismissPullReview` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/undismissals` | `repoUnDismissPullReview` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/pulls/{index}/update` | `repoUpdatePullRequest` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/push_mirrors` | `repoListPushMirrors` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/push_mirrors` | `repoAddPushMirror` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/push_mirrors-sync` | `repoPushMirrorSync` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/push_mirrors/{name}` | `repoDeletePushMirror` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/push_mirrors/{name}` | `repoGetPushMirrorByRemoteName` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/raw/{filepath}` | `repoGetRawFile` | optional | — |  |
| [x] | GET | `/repos/{owner}/{repo}/releases` | `repoListReleases` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/releases` | `repoCreateRelease` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/releases/latest` | `repoGetLatestRelease` | optional | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/releases/tags/{tag}` | `repoDeleteReleaseByTag` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/releases/tags/{tag}` | `repoGetReleaseByTag` | optional | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/releases/{id}` | `repoDeleteRelease` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/releases/{id}` | `repoGetRelease` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/releases/{id}` | `repoEditRelease` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/releases/{id}/assets` | `repoListReleaseAttachments` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/releases/{id}/assets` | `repoCreateReleaseAttachment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}` | `repoDeleteReleaseAttachment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}` | `repoGetReleaseAttachment` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/releases/{id}/assets/{attachment_id}` | `repoEditReleaseAttachment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/reviewers` | `repoGetReviewers` | optional | — | array response, no paging params |
| [ ] | GET | `/repos/{owner}/{repo}/signing-key.gpg` | `repoSigningKey` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/stargazers` | `repoListStargazers` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/statuses/{sha}` | `repoListStatuses` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/statuses/{sha}` | `repoCreateStatus` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/subscribers` | `repoListSubscribers` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/subscription` | `userCurrentDeleteSubscription` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/subscription` | `userCurrentCheckSubscription` | optional | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/subscription` | `userCurrentPutSubscription` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/sync_fork` | `repoSyncForkDefaultInfo` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/sync_fork` | `repoSyncForkDefault` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/sync_fork/{branch}` | `repoSyncForkBranchInfo` | optional | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/sync_fork/{branch}` | `repoSyncForkBranch` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/tag_protections` | `repoListTagProtection` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/tag_protections` | `repoCreateTagProtection` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/tag_protections/{id}` | `repoDeleteTagProtection` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/tag_protections/{id}` | `repoGetTagProtection` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/tag_protections/{id}` | `repoEditTagProtection` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/tags` | `repoListTags` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/tags` | `repoCreateTag` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/tags/{tag}` | `repoDeleteTag` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/tags/{tag}` | `repoGetTag` | optional | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/teams` | `repoListTeams` | optional | — | array response, no paging params |
| [ ] | DELETE | `/repos/{owner}/{repo}/teams/{team}` | `repoDeleteTeam` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/teams/{team}` | `repoCheckTeam` | optional | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/teams/{team}` | `repoAddTeam` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/times` | `repoTrackedTimes` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/times/{user}` | `userTrackedTimes` | optional | — | array response, no paging params |
| [x] | GET | `/repos/{owner}/{repo}/topics` | `repoListTopics` | optional | page+limit |  |
| [ ] | PUT | `/repos/{owner}/{repo}/topics` | `repoUpdateTopics` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/topics/{topic}` | `repoDeleteTopic` | token | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/topics/{topic}` | `repoAddTopic` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/transfer` | `repoTransfer` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/transfer/accept` | `acceptRepoTransfer` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/transfer/reject` | `rejectRepoTransfer` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/wiki/new` | `repoCreateWikiPage` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/wiki/page/{pageName}` | `repoDeleteWikiPage` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/wiki/page/{pageName}` | `repoGetWikiPage` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/wiki/page/{pageName}` | `repoEditWikiPage` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/wiki/pages` | `repoGetWikiPages` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/wiki/revisions/{pageName}` | `repoGetWikiPageRevisions` | optional | page only |  |
| [ ] | POST | `/repos/{template_owner}/{template_repo}/generate` | `generateRepo` | token | — |  |
| [ ] | GET | `/repositories/{id}` | `repoGetByID` | optional | — |  |
| [ ] | GET | `/topics/search` | `topicSearch` | optional | page+limit |  |
| [ ] | POST | `/user/repos` | `createCurrentUserRepo` | token | — | dual-tagged `repository`+`user`; implement once |

### 3.3 `issue` — 67 operations

GET 23 · POST 16 · PATCH 8 · PUT 2 · DELETE 18

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [ ] | GET | `/repos/issues/search` | `issueSearchIssues` | optional | page+limit |  |
| [x] | GET | `/repos/{owner}/{repo}/issues` | `issueListIssues` | optional | page+limit |  |
| [x] | POST | `/repos/{owner}/{repo}/issues` | `issueCreateIssue` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/comments` | `issueGetRepoComments` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/comments/{id}` | `issueDeleteComment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/comments/{id}` | `issueGetComment` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/issues/comments/{id}` | `issueEditComment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/comments/{id}/assets` | `issueListIssueCommentAttachments` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/issues/comments/{id}/assets` | `issueCreateIssueCommentAttachment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/comments/{id}/assets/{attachment_id}` | `issueDeleteIssueCommentAttachment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/comments/{id}/assets/{attachment_id}` | `issueGetIssueCommentAttachment` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/issues/comments/{id}/assets/{attachment_id}` | `issueEditIssueCommentAttachment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/comments/{id}/reactions` | `issueDeleteCommentReaction` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/comments/{id}/reactions` | `issueGetCommentReactions` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/issues/comments/{id}/reactions` | `issuePostCommentReaction` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}` | `issueDelete` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/issues/{index}` | `issueGetIssue` | optional | — |  |
| [x] | PATCH | `/repos/{owner}/{repo}/issues/{index}` | `issueEditIssue` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/assets` | `issueListIssueAttachments` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/assets` | `issueCreateIssueAttachment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/assets/{attachment_id}` | `issueDeleteIssueAttachment` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/assets/{attachment_id}` | `issueGetIssueAttachment` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/issues/{index}/assets/{attachment_id}` | `issueEditIssueAttachment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/blocks` | `issueRemoveIssueBlocking` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/blocks` | `issueListBlocks` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/blocks` | `issueCreateIssueBlocking` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/issues/{index}/comments` | `issueGetComments` | optional | — | array response, no paging params |
| [x] | POST | `/repos/{owner}/{repo}/issues/{index}/comments` | `issueCreateComment` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/comments/{id}` | `issueDeleteCommentDeprecated` | token | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/issues/{index}/comments/{id}` | `issueEditCommentDeprecated` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/deadline` | `issueEditIssueDeadline` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/dependencies` | `issueRemoveIssueDependencies` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/dependencies` | `issueListIssueDependencies` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/dependencies` | `issueCreateIssueDependencies` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/labels` | `issueClearLabels` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/labels` | `issueGetLabels` | optional | — | array response, no paging params |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/labels` | `issueAddLabel` | token | — | array response, no paging params |
| [ ] | PUT | `/repos/{owner}/{repo}/issues/{index}/labels` | `issueReplaceLabels` | token | — | array response, no paging params |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/labels/{identifier}` | `issueRemoveLabel` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/pin` | `unpinIssue` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/pin` | `pinIssue` | token | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/issues/{index}/pin/{position}` | `moveIssuePin` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/reactions` | `issueDeleteIssueReaction` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/reactions` | `issueGetIssueReactions` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/reactions` | `issuePostIssueReaction` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/stopwatch/delete` | `issueDeleteStopWatch` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/stopwatch/start` | `issueStartStopWatch` | token | — |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/stopwatch/stop` | `issueStopStopWatch` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/subscriptions` | `issueSubscriptions` | optional | page+limit |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/subscriptions/check` | `issueCheckSubscription` | optional | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/subscriptions/{user}` | `issueDeleteSubscription` | token | — |  |
| [ ] | PUT | `/repos/{owner}/{repo}/issues/{index}/subscriptions/{user}` | `issueAddSubscription` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/timeline` | `issueGetCommentsAndTimeline` | optional | page+limit |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/times` | `issueResetTime` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/issues/{index}/times` | `issueTrackedTimes` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/issues/{index}/times` | `issueAddTime` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/issues/{index}/times/{id}` | `issueDeleteTime` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/labels` | `issueListLabels` | optional | page+limit |  |
| [x] | POST | `/repos/{owner}/{repo}/labels` | `issueCreateLabel` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/labels/{id}` | `issueDeleteLabel` | token | — |  |
| [ ] | GET | `/repos/{owner}/{repo}/labels/{id}` | `issueGetLabel` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/labels/{id}` | `issueEditLabel` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/milestones` | `issueGetMilestonesList` | optional | page+limit |  |
| [ ] | POST | `/repos/{owner}/{repo}/milestones` | `issueCreateMilestone` | token | — |  |
| [ ] | DELETE | `/repos/{owner}/{repo}/milestones/{id}` | `issueDeleteMilestone` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/milestones/{id}` | `issueGetMilestone` | optional | — |  |
| [ ] | PATCH | `/repos/{owner}/{repo}/milestones/{id}` | `issueEditMilestone` | token | — |  |

### 3.4 `organization` — 69 operations

GET 36 · POST 10 · PATCH 4 · PUT 7 · DELETE 12

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [ ] | POST | `/org/{org}/repos` | `createOrgRepoDeprecated` | token | — |  |
| [x] | GET | `/orgs` | `orgGetAll` | optional | page+limit |  |
| [ ] | POST | `/orgs` | `orgCreate` | token | — |  |
| [ ] | DELETE | `/orgs/{org}` | `orgDelete` | token | — |  |
| [x] | GET | `/orgs/{org}` | `orgGet` | optional | — |  |
| [ ] | PATCH | `/orgs/{org}` | `orgEdit` | token | — |  |
| [ ] | GET | `/orgs/{org}/actions/runners` | `getOrgRunners` | optional | page+limit |  |
| [ ] | POST | `/orgs/{org}/actions/runners` | `registerOrgRunner` | token | — |  |
| [ ] | GET | `/orgs/{org}/actions/runners/jobs` | `orgSearchRunJobs` | optional | — | array response, no paging params |
| [ ] | GET | `/orgs/{org}/actions/runners/registration-token` | `orgGetRunnerRegistrationToken` | optional | — |  |
| [ ] | DELETE | `/orgs/{org}/actions/runners/{runner_id}` | `deleteOrgRunner` | token | — |  |
| [ ] | GET | `/orgs/{org}/actions/runners/{runner_id}` | `getOrgRunner` | optional | — |  |
| [ ] | GET | `/orgs/{org}/actions/secrets` | `orgListActionsSecrets` | optional | page+limit |  |
| [ ] | DELETE | `/orgs/{org}/actions/secrets/{secretname}` | `deleteOrgSecret` | token | — |  |
| [ ] | PUT | `/orgs/{org}/actions/secrets/{secretname}` | `updateOrgSecret` | token | — |  |
| [ ] | GET | `/orgs/{org}/actions/variables` | `getOrgVariablesList` | optional | page+limit |  |
| [ ] | DELETE | `/orgs/{org}/actions/variables/{variablename}` | `deleteOrgVariable` | token | — |  |
| [ ] | GET | `/orgs/{org}/actions/variables/{variablename}` | `getOrgVariable` | optional | — |  |
| [ ] | POST | `/orgs/{org}/actions/variables/{variablename}` | `createOrgVariable` | token | — |  |
| [ ] | PUT | `/orgs/{org}/actions/variables/{variablename}` | `updateOrgVariable` | token | — |  |
| [ ] | GET | `/orgs/{org}/activities/feeds` | `orgListActivityFeeds` | optional | page+limit |  |
| [ ] | DELETE | `/orgs/{org}/avatar` | `orgDeleteAvatar` | token | — |  |
| [ ] | POST | `/orgs/{org}/avatar` | `orgUpdateAvatar` | token | — |  |
| [ ] | PUT | `/orgs/{org}/block/{username}` | `orgBlockUser` | token | — |  |
| [ ] | GET | `/orgs/{org}/hooks` | `orgListHooks` | optional | page+limit |  |
| [ ] | POST | `/orgs/{org}/hooks` | `orgCreateHook` | token | — |  |
| [ ] | DELETE | `/orgs/{org}/hooks/{id}` | `orgDeleteHook` | token | — |  |
| [ ] | GET | `/orgs/{org}/hooks/{id}` | `orgGetHook` | optional | — |  |
| [ ] | PATCH | `/orgs/{org}/hooks/{id}` | `orgEditHook` | token | — |  |
| [ ] | GET | `/orgs/{org}/labels` | `orgListLabels` | optional | page+limit |  |
| [ ] | POST | `/orgs/{org}/labels` | `orgCreateLabel` | token | — |  |
| [ ] | DELETE | `/orgs/{org}/labels/{id}` | `orgDeleteLabel` | token | — |  |
| [ ] | GET | `/orgs/{org}/labels/{id}` | `orgGetLabel` | optional | — |  |
| [ ] | PATCH | `/orgs/{org}/labels/{id}` | `orgEditLabel` | token | — |  |
| [ ] | GET | `/orgs/{org}/list_blocked` | `orgListBlockedUsers` | optional | page+limit |  |
| [x] | GET | `/orgs/{org}/members` | `orgListMembers` | optional | page+limit |  |
| [ ] | DELETE | `/orgs/{org}/members/{username}` | `orgDeleteMember` | token | — |  |
| [ ] | GET | `/orgs/{org}/members/{username}` | `orgIsMember` | optional | — |  |
| [x] | GET | `/orgs/{org}/public_members` | `orgListPublicMembers` | optional | page+limit |  |
| [ ] | DELETE | `/orgs/{org}/public_members/{username}` | `orgConcealMember` | token | — |  |
| [ ] | GET | `/orgs/{org}/public_members/{username}` | `orgIsPublicMember` | optional | — |  |
| [ ] | PUT | `/orgs/{org}/public_members/{username}` | `orgPublicizeMember` | token | — |  |
| [ ] | GET | `/orgs/{org}/quota` | `orgGetQuota` | optional | — |  |
| [ ] | GET | `/orgs/{org}/quota/artifacts` | `orgListQuotaArtifacts` | optional | page+limit |  |
| [ ] | GET | `/orgs/{org}/quota/attachments` | `orgListQuotaAttachments` | optional | page+limit |  |
| [ ] | GET | `/orgs/{org}/quota/check` | `orgCheckQuota` | optional | — |  |
| [ ] | GET | `/orgs/{org}/quota/packages` | `orgListQuotaPackages` | optional | page+limit |  |
| [ ] | POST | `/orgs/{org}/rename` | `renameOrg` | token | — |  |
| [x] | GET | `/orgs/{org}/repos` | `orgListRepos` | optional | page+limit |  |
| [ ] | POST | `/orgs/{org}/repos` | `createOrgRepo` | token | — |  |
| [x] | GET | `/orgs/{org}/teams` | `orgListTeams` | optional | page+limit |  |
| [ ] | POST | `/orgs/{org}/teams` | `orgCreateTeam` | token | — |  |
| [ ] | GET | `/orgs/{org}/teams/search` | `teamSearch` | optional | page+limit |  |
| [ ] | PUT | `/orgs/{org}/unblock/{username}` | `orgUnblockUser` | token | — |  |
| [ ] | DELETE | `/teams/{id}` | `orgDeleteTeam` | token | — |  |
| [x] | GET | `/teams/{id}` | `orgGetTeam` | optional | — |  |
| [ ] | PATCH | `/teams/{id}` | `orgEditTeam` | token | — |  |
| [ ] | GET | `/teams/{id}/activities/feeds` | `orgListTeamActivityFeeds` | optional | page+limit |  |
| [x] | GET | `/teams/{id}/members` | `orgListTeamMembers` | optional | page+limit |  |
| [ ] | DELETE | `/teams/{id}/members/{username}` | `orgRemoveTeamMember` | token | — |  |
| [ ] | GET | `/teams/{id}/members/{username}` | `orgListTeamMember` | optional | — |  |
| [ ] | PUT | `/teams/{id}/members/{username}` | `orgAddTeamMember` | token | — |  |
| [x] | GET | `/teams/{id}/repos` | `orgListTeamRepos` | optional | page+limit |  |
| [ ] | DELETE | `/teams/{id}/repos/{org}/{repo}` | `orgRemoveTeamRepository` | token | — |  |
| [ ] | GET | `/teams/{id}/repos/{org}/{repo}` | `orgListTeamRepo` | optional | — |  |
| [ ] | PUT | `/teams/{id}/repos/{org}/{repo}` | `orgAddTeamRepository` | token | — |  |
| [ ] | GET | `/user/orgs` | `orgListCurrentUserOrgs` | token | page+limit |  |
| [x] | GET | `/users/{username}/orgs` | `orgListUserOrgs` | optional | page+limit |  |
| [ ] | GET | `/users/{username}/orgs/{org}/permissions` | `orgGetUserPermissions` | optional | — |  |

### 3.5 `notification` — 7 operations

GET 4 · POST 0 · PATCH 1 · PUT 2 · DELETE 0

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [x] | GET | `/notifications` | `notifyGetList` | token | page+limit |  |
| [x] | PUT | `/notifications` | `notifyReadList` | token | — |  |
| [x] | GET | `/notifications/new` | `notifyNewAvailable` | token | — |  |
| [x] | GET | `/notifications/threads/{id}` | `notifyGetThread` | token | — |  |
| [x] | PATCH | `/notifications/threads/{id}` | `notifyReadThread` | token | — |  |
| [x] | GET | `/repos/{owner}/{repo}/notifications` | `notifyGetRepoList` | token | page+limit |  |
| [x] | PUT | `/repos/{owner}/{repo}/notifications` | `notifyReadRepoList` | token | — |  |

### 3.6 `miscellaneous` — 14 operations

GET 11 · POST 3 · PATCH 0 · PUT 0 · DELETE 0

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [ ] | GET | `/actions/run` | `getActionsRun` | optional | — |  |
| [ ] | GET | `/gitignore/templates` | `listGitignoresTemplates` | optional | — | array response, no paging params |
| [ ] | GET | `/gitignore/templates/{name}` | `getGitignoreTemplateInfo` | optional | — |  |
| [ ] | GET | `/label/templates` | `listLabelTemplates` | optional | — | array response, no paging params |
| [ ] | GET | `/label/templates/{name}` | `getLabelTemplateInfo` | optional | — | array response, no paging params |
| [ ] | GET | `/licenses` | `listLicenseTemplates` | optional | — | array response, no paging params |
| [ ] | GET | `/licenses/{name}` | `getLicenseTemplateInfo` | optional | — |  |
| [x] | POST | `/markdown` | `renderMarkdown` | token? | — | pure render, no side effect — anonymous access unverified |
| [x] | POST | `/markdown/raw` | `renderMarkdownRaw` | token? | — | pure render, no side effect — anonymous access unverified |
| [ ] | POST | `/markup` | `renderMarkup` | token? | — | pure render, no side effect — anonymous access unverified |
| [ ] | GET | `/nodeinfo` | `getNodeInfo` | optional | — |  |
| [x] | GET | `/signing-key.gpg` | `getSigningKey` | optional | — |  |
| [ ] | GET | `/signing-key.ssh` | `getSSHSigningKey` | optional | — |  |
| [x] | GET | `/version` | `getVersion` | optional | — |  |

### 3.7 `settings` — 4 operations

GET 4 · POST 0 · PATCH 0 · PUT 0 · DELETE 0

| ✔ | Method | Path | operationId | Auth | Paged | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| [x] | GET | `/settings/api` | `getGeneralAPISettings` | optional | — |  |
| [x] | GET | `/settings/attachment` | `getGeneralAttachmentSettings` | optional | — |  |
| [x] | GET | `/settings/repository` | `getGeneralRepositorySettings` | optional | — |  |
| [ ] | GET | `/settings/ui` | `getGeneralUISettings` | optional | — |  |

---

## 4. Deferred endpoint listing (not to be implemented in v1)

### `package` — 6 operations (deferred)

| Method | Path | operationId |
| --- | --- | --- |
| GET | `/packages/{owner}` | `listPackages` |
| POST | `/packages/{owner}/{type}/{name}/-/link/{repo_name}` | `linkPackage` |
| POST | `/packages/{owner}/{type}/{name}/-/unlink` | `unlinkPackage` |
| DELETE | `/packages/{owner}/{type}/{name}/{version}` | `deletePackage` |
| GET | `/packages/{owner}/{type}/{name}/{version}` | `getPackage` |
| GET | `/packages/{owner}/{type}/{name}/{version}/files` | `listPackageFiles` |

### `activitypub` — 11 operations (out-of-scope)

| Method | Path | operationId |
| --- | --- | --- |
| GET | `/activitypub/actor` | `activitypubInstanceActor` |
| POST | `/activitypub/actor/inbox` | `activitypubInstanceActorInbox` |
| POST | `/activitypub/actor/outbox` | `activitypubInstanceActorOutbox` |
| GET | `/activitypub/repository-id/{repository-id}` | `activitypubRepository` |
| POST | `/activitypub/repository-id/{repository-id}/inbox` | `activitypubRepositoryInbox` |
| POST | `/activitypub/repository-id/{repository-id}/outbox` | `activitypubRepositoryOutbox` |
| GET | `/activitypub/user-id/{user-id}` | `activitypubPerson` |
| GET | `/activitypub/user-id/{user-id}/activities/{activity-id}` | `activitypubPersonActivityNote` |
| GET | `/activitypub/user-id/{user-id}/activities/{activity-id}/activity` | `activitypubPersonActivity` |
| POST | `/activitypub/user-id/{user-id}/inbox` | `activitypubPersonInbox` |
| GET | `/activitypub/user-id/{user-id}/outbox` | `activitypubPersonFeed` |

### `admin` — 51 operations (out-of-scope)

| Method | Path | operationId |
| --- | --- | --- |
| GET | `/admin/actions/runners` | `getAdminRunners` |
| POST | `/admin/actions/runners` | `registerAdminRunner` |
| GET | `/admin/actions/runners/jobs` | `adminGetActionRunJobs` |
| GET | `/admin/actions/runners/registration-token` | `adminGetRunnerRegistrationToken` |
| DELETE | `/admin/actions/runners/{runner_id}` | `deleteAdminRunner` |
| GET | `/admin/actions/runners/{runner_id}` | `getAdminRunner` |
| GET | `/admin/cron` | `adminCronList` |
| POST | `/admin/cron/{task}` | `adminCronRun` |
| GET | `/admin/emails` | `adminGetAllEmails` |
| GET | `/admin/emails/search` | `adminSearchEmails` |
| GET | `/admin/hooks` | `adminListHooks` |
| POST | `/admin/hooks` | `adminCreateHook` |
| DELETE | `/admin/hooks/{id}` | `adminDeleteHook` |
| GET | `/admin/hooks/{id}` | `adminGetHook` |
| PATCH | `/admin/hooks/{id}` | `adminEditHook` |
| GET | `/admin/orgs` | `adminGetAllOrgs` |
| GET | `/admin/quota/groups` | `adminListQuotaGroups` |
| POST | `/admin/quota/groups` | `adminCreateQuotaGroup` |
| DELETE | `/admin/quota/groups/{quotagroup}` | `adminDeleteQuotaGroup` |
| GET | `/admin/quota/groups/{quotagroup}` | `adminGetQuotaGroup` |
| DELETE | `/admin/quota/groups/{quotagroup}/rules/{quotarule}` | `adminRemoveRuleFromQuotaGroup` |
| PUT | `/admin/quota/groups/{quotagroup}/rules/{quotarule}` | `adminAddRuleToQuotaGroup` |
| GET | `/admin/quota/groups/{quotagroup}/users` | `adminListUsersInQuotaGroup` |
| DELETE | `/admin/quota/groups/{quotagroup}/users/{username}` | `adminRemoveUserFromQuotaGroup` |
| PUT | `/admin/quota/groups/{quotagroup}/users/{username}` | `adminAddUserToQuotaGroup` |
| GET | `/admin/quota/rules` | `adminListQuotaRules` |
| POST | `/admin/quota/rules` | `adminCreateQuotaRule` |
| DELETE | `/admin/quota/rules/{quotarule}` | `adminDeleteQuotaRule` |
| GET | `/admin/quota/rules/{quotarule}` | `adminGetQuotaRule` |
| PATCH | `/admin/quota/rules/{quotarule}` | `adminEditQuotaRule` |
| GET | `/admin/runners/jobs` | `adminSearchRunJobs` |
| GET | `/admin/runners/registration-token` | `adminGetRegistrationToken` |
| GET | `/admin/unadopted` | `adminUnadoptedList` |
| DELETE | `/admin/unadopted/{owner}/{repo}` | `adminDeleteUnadoptedRepository` |
| POST | `/admin/unadopted/{owner}/{repo}` | `adminAdoptRepository` |
| GET | `/admin/users` | `adminSearchUsers` |
| POST | `/admin/users` | `adminCreateUser` |
| DELETE | `/admin/users/{username}` | `adminDeleteUser` |
| PATCH | `/admin/users/{username}` | `adminEditUser` |
| DELETE | `/admin/users/{username}/emails` | `adminDeleteUserEmails` |
| GET | `/admin/users/{username}/emails` | `adminListUserEmails` |
| POST | `/admin/users/{username}/keys` | `adminCreatePublicKey` |
| DELETE | `/admin/users/{username}/keys/{id}` | `adminDeleteUserPublicKey` |
| POST | `/admin/users/{username}/orgs` | `adminCreateOrg` |
| GET | `/admin/users/{username}/quota` | `adminGetUserQuota` |
| POST | `/admin/users/{username}/quota/groups` | `adminSetUserQuotaGroups` |
| POST | `/admin/users/{username}/rename` | `adminRenameUser` |
| POST | `/admin/users/{username}/repos` | `adminCreateRepo` |
| GET | `/admin/users/{username}/tokens` | `adminListUserAccessTokens` |
| POST | `/admin/users/{username}/tokens` | `adminCreateUserAccessToken` |
| DELETE | `/admin/users/{username}/tokens/{token}` | `adminDeleteUserAccessToken` |

---

## 5. Pagination parameter census

| Declared query params | Operations |
| --- | ---: |
| `page+limit` | 103 |
| `page only` | 2 |
| `—` | 401 |

The two `page only` operations are `GetTree`
(`GET /repos/{owner}/{repo}/git/trees/{sha}`) and `repoGetWikiPageRevisions`
(`GET /repos/{owner}/{repo}/wiki/revisions/{pageName}`). `GetTree` returns a
`GitTreeResponse` envelope carrying its own `page`, `total_count` and
`truncated` fields rather than a bare array, so it needs its own paging shape.

### Array-returning GETs with no `page`/`limit` (unbounded collections)

These violate the SCALA_CODE_STYLE.md rule *"never fetch an unbounded API
collection eagerly"* by construction — the server offers no way to page them.
Each must either be documented as bounded-by-nature (labels on one issue) or
returned as a `Flow` that the caller can stop consuming.

| Tag | operationId | Path |
| --- | --- | --- |
| `admin` *(deferred tag)* | `adminGetActionRunJobs` | `/admin/actions/runners/jobs` |
| `admin` *(deferred tag)* | `adminListQuotaRules` | `/admin/quota/rules` |
| `admin` *(deferred tag)* | `adminListUserEmails` | `/admin/users/{username}/emails` |
| `admin` *(deferred tag)* | `adminListUsersInQuotaGroup` | `/admin/quota/groups/{quotagroup}/users` |
| `admin` *(deferred tag)* | `adminSearchRunJobs` | `/admin/runners/jobs` |
| `issue` | `issueGetCommentReactions` | `/repos/{owner}/{repo}/issues/comments/{id}/reactions` |
| `issue` | `issueGetComments` | `/repos/{owner}/{repo}/issues/{index}/comments` |
| `issue` | `issueGetLabels` | `/repos/{owner}/{repo}/issues/{index}/labels` |
| `issue` | `issueListIssueAttachments` | `/repos/{owner}/{repo}/issues/{index}/assets` |
| `issue` | `issueListIssueCommentAttachments` | `/repos/{owner}/{repo}/issues/comments/{id}/assets` |
| `miscellaneous` | `getLabelTemplateInfo` | `/label/templates/{name}` |
| `miscellaneous` | `listGitignoresTemplates` | `/gitignore/templates` |
| `miscellaneous` | `listLabelTemplates` | `/label/templates` |
| `miscellaneous` | `listLicenseTemplates` | `/licenses` |
| `organization` | `orgSearchRunJobs` | `/orgs/{org}/actions/runners/jobs` |
| `package` *(deferred tag)* | `listPackageFiles` | `/packages/{owner}/{type}/{name}/{version}/files` |
| `repository` | `GetBlobs` | `/repos/{owner}/{repo}/git/blobs` |
| `repository` | `ListActionRunJobs` | `/repos/{owner}/{repo}/actions/runs/{run_id}/jobs` |
| `repository` | `repoGetAssignees` | `/repos/{owner}/{repo}/assignees` |
| `repository` | `repoGetContentsList` | `/repos/{owner}/{repo}/contents` |
| `repository` | `repoGetIssueTemplates` | `/repos/{owner}/{repo}/issue_templates` |
| `repository` | `repoGetPullReviewComments` | `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments` |
| `repository` | `repoGetReviewers` | `/repos/{owner}/{repo}/reviewers` |
| `repository` | `repoListAllGitRefs` | `/repos/{owner}/{repo}/git/refs` |
| `repository` | `repoListBranchProtection` | `/repos/{owner}/{repo}/branch_protections` |
| `repository` | `repoListFlags` | `/repos/{owner}/{repo}/flags` |
| `repository` | `repoListGitHooks` | `/repos/{owner}/{repo}/hooks/git` |
| `repository` | `repoListGitRefs` | `/repos/{owner}/{repo}/git/refs/{ref}` |
| `repository` | `repoListPinnedIssues` | `/repos/{owner}/{repo}/issues/pinned` |
| `repository` | `repoListPinnedPullRequests` | `/repos/{owner}/{repo}/pulls/pinned` |
| `repository` | `repoListReleaseAttachments` | `/repos/{owner}/{repo}/releases/{id}/assets` |
| `repository` | `repoListTagProtection` | `/repos/{owner}/{repo}/tag_protections` |
| `repository` | `repoListTeams` | `/repos/{owner}/{repo}/teams` |
| `repository` | `repoSearchRunJobs` | `/repos/{owner}/{repo}/actions/runners/jobs` |
| `repository` | `userTrackedTimes` | `/repos/{owner}/{repo}/times/{user}` |
| `user` | `userGetHeatmapData` | `/users/{username}/heatmap` |
| `user` | `userListEmails` | `/user/emails` |
| `user` | `userSearchRunJobs` | `/user/actions/runners/jobs` |

