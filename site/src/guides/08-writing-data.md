# Writing data

For anyone about to change something on a forge rather than read it: how the
write paths are shaped, which operations this library repeats after a failure
and which it refuses to repeat, and how to write a file without silently
discarding somebody else's edit.

## Commands, not long parameter lists

Every write takes a *command* value rather than a row of optional parameters.
`POST /repos/{owner}/{repo}/issues` accepts eight fields, seven of them
optional; a method with seven `Option`s in a fixed order is a defect waiting for
a rushed afternoon.

So you build a command by naming what should be set:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.CreateIssue
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.Future

def fileBug(client: CodebergClient, owner: Owner, name: RepoName): Either[ValidationError, Future[Issue]] =
  CreateIssue
    .of("Retry storm on 429")
    .map(_.withBody("Backoff ignores Retry-After when the header is a date."))
    .map(command => client.issues.create(owner, name, command))
```

Three properties fall out of that shape.

**Only what you set is sent.** An unset field contributes no JSON key at all, so
the instance applies its own default rather than this library's guess at one.
That matters more than it sounds: sending `state=""` is not the same request as
omitting `state`.

**Validation happens before the network.** `CreateIssue.of` trims the title and
rejects a blank one, so an empty title is a `ValidationError` on the line that
made it rather than a `422` a round trip later — and Forgejo's `422` bodies carry
a raw Go error string with no field name, so learning it from the server tells
you very little.

**The builders are methods rather than default arguments** because this project
bans default arguments, and because `command.withBody(…).dueBy(…)` reads at the
call site where a nine-argument `copy` does not.

The same shape appears everywhere: `EditIssue`, `CreateComment`, `CreateLabel`,
`CreateMilestone`, `CreatePullRequest`, `MergePullRequest`, `CreateRelease`,
`CreateHook`, `CreateAccessToken`, `CreateFile`, `UpdateFile`, `DeleteFile`,
`ChangeFiles`. Each starts from a constructor that takes only what Forgejo
insists on.

## A pull request, end to end

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.pulls.CreatePullRequest
import com.worxbend.codeberg4s.pulls.PullRequest
import com.worxbend.codeberg4s.pulls.PullRequestHead
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.Future

def openPullRequest(client: CodebergClient, owner: Owner, name: RepoName)(
    from: String,
    into: String,
): Either[ValidationError, Future[PullRequest]] =
  for
    head    <- BranchName.from(from).map(PullRequestHead.branch)
    base    <- BranchName.from(into)
    command <- CreatePullRequest.of("Stream pages instead of buffering", head, base)
  yield client.pulls.create(owner, name, command)
```

`PullRequestHead` is either a branch in this repository or the `owner:branch`
form that a fork needs. There is no way to pass one where the other was meant,
which is the whole reason it is a type.

## Merging, and the guard on it

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.pulls.MergePullRequest
import com.worxbend.codeberg4s.pulls.MergeStyle
import com.worxbend.codeberg4s.pulls.PullRequestNumber
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.Future

def squashMerge(client: CodebergClient, owner: Owner, name: RepoName)(
    number: Long,
    headYouReviewed: String,
): Either[ValidationError, Future[Unit]] =
  for
    pull <- PullRequestNumber.from(number)
    head <- CommitSha.from(headYouReviewed)
  yield client.pulls.merge(
    owner,
    name,
    pull,
    MergePullRequest.using(MergeStyle.Squash).expecting(head).deletingSourceBranch,
  )
```

`expecting(head)` sends Forgejo's `head_commit_id`, which it compares against the
branch's actual head and refuses the merge if it has moved. Use it whenever the
decision to merge was made from a state you read earlier: without it, a merge
sent after a review merges whatever the head has since become — including a
commit nobody looked at.

`merge` returns `Future[Unit]`. Forgejo answers `200` with no body, and inventing
a `PullRequest` to return would mean guessing at post-merge state the server did
not send.

One caveat worth knowing: `MergePullRequest.whenChecksSucceed` *schedules* the
merge instead of performing it, and Forgejo answers such a request with a
success. A `200` from that variant does **not** mean the pull request is merged.

## Which writes are repeated, and which are never repeated

The retry engine asks whether the operation is allowed to be repeated at all
before it asks anything else. The answer is stated by the operation, not by your
policy, and each method's Scaladoc names it.

**Never repeated.** Any operation that creates something, or that applies a
partial update against whatever the resource has become:

- `issues.create` — a repeat files a second issue. Forgejo has no idempotency
  keys, so there is no way to make the second call recognise the first.
- `issues.edit` — a repeat re-applies a patch to a resource that may have moved
  on between the two attempts.
- `pulls.merge` — a repeat merges whatever the head is now, which is exactly
  what `expecting` exists to prevent.
- `repos.admin.createFile`, `updateFile`, `deleteFile`, `changeFiles` — all four.
  See [the next section](#optimistic-concurrency-on-file-writes) for why the
  `sha` guard makes a repeat *safe* and still not *right*.
- `repos.admin.create`, `edit`, `delete`, `migrate`, `transfer`, `createBranch`,
  `deleteBranch`, `renameBranch`.

**Repeated even though they mutate.** Any operation whose request names one
resource and states its whole intended state, so that N attempts leave the
instance exactly as one attempt would:

- the three notification mark-read calls, which carry no body and set a state
  that is already the state they set;
- following, unfollowing, blocking, starring, watching, unwatching;
- replacing a repository's topic list, adding or removing one topic;
- deleting a row addressed by an id the instance never reuses — a comment, a
  label, a deploy key, a webhook.

This third category is much larger than the shape of the HTTP verb suggests, and
it is deliberate rather than incidental: each of those methods argues for its
choice in its own Scaladoc. Read that rather than inferring from `PUT` or
`DELETE`.

> If you are reading the sources: `NotificationApi`'s class-level Scaladoc claims
> its three mutating operations are "the only place in this library" using
> `AlwaysRetry`. That sentence is stale — many groups use it now. The per-method
> documentation is accurate.

### The rule behind the rule

`AlwaysRetry` is a claim that repeating the request is **observationally
harmless**, not that it is convenient. Two questions decide it:

1. Does the request name the resource, or does it ask the instance to invent one?
   Naming is safe; inventing creates duplicates.
2. Does the request state the whole intended state, or a delta? Whole state
   converges; a delta compounds.

Deleting a token *by name* fails both tests, incidentally, and this library
notices: `users.tokens.delete` retries when the token is addressed by id and does
not when it is addressed by name, because a name can be recreated and the retry
would delete a different, newer credential.

### What you can do about an unretried write that failed

Nothing automatic — and that is the honest answer. If a `POST` times out, you do
not know whether it landed. Your options are the ones the protocol leaves you:

- **Read before you retry.** List issues filtered by title, look for the comment
  you may have posted, check whether the branch exists. Then decide.
- **Make the write name its own outcome.** Where the API allows it, prefer the
  operation that states a whole state over the one that applies a delta.
- **Accept the duplicate.** For some workloads, two identical comments are
  cheaper than the machinery to prevent them.

## Optimistic concurrency on file writes

This is the part of the write surface with a real concurrency story, and it is
worth understanding before you use it.

`UpdateFile` and `DeleteFile` both **require** the blob id the file is expected
to have. Not optionally — the field is not an `Option`, on the wire or in this
library:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.admin.FileBytes
import com.worxbend.codeberg4s.repositories.admin.UpdateFile

def replacement(content: String, currentSha: CommitSha): UpdateFile =
  UpdateFile.of(FileBytes.ofText(content), currentSha)
```

Forgejo compares that id against what the file actually holds and answers
`409 Conflict` when it has moved on. `DeleteFile` carries the same guard, and a
mismatch there is a `400`.

Modelling the sha as optional would have been the single most dangerous
convenience this group could offer. Without it, a caller who reads a file,
thinks about it, and writes it back silently discards whatever landed in
between.

### The full read-modify-write

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.repositories.ContentEntry
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.RepositoryContent
import com.worxbend.codeberg4s.repositories.admin.CommitOptions
import com.worxbend.codeberg4s.repositories.admin.FileBytes
import com.worxbend.codeberg4s.repositories.admin.UpdateFile
import com.worxbend.codeberg4s.repositories.gitdata.FileChange

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Reads a text file, transforms it, and writes it back under the sha it was read at.
  *
  * Returns `None` when the path is not a file whose contents the instance sent —
  * a directory, a symlink, or an entry listed without its payload.
  */
def rewriteFile(client: CodebergClient, owner: Owner, name: RepoName, path: ContentPath)(
    transform: String => String
)(using ExecutionContext): Future[Option[FileChange]] =
  client.repos.getContents(owner, name, path).flatMap:
    case RepositoryContent.File(ContentEntry.File(meta, Some(content), _)) =>
      content.text match
        case None       => Future.successful(None)
        case Some(text) =>
          val command = UpdateFile
            .of(FileBytes.ofText(transform(text)), meta.sha)
            .committing(CommitOptions.Default.describedAs("Rewrite by automation"))

          client.repos.admin.updateFile(owner, name, path, command).map(Some.apply)

    case RepositoryContent.File(_)      => Future.successful(None)
    case RepositoryContent.Directory(_) => Future.successful(None)
```

The sha comes from `ContentMeta.sha` on the entry that was read. There is no
other correct source for it: a sha you carried over from an earlier run is a sha
that says "I believe the file is as I last saw it", which is precisely the claim
the guard is checking.

Note the shape of the read. `getContents` is the one union in this API — the same
path returns a file object or an array of directory entries, discriminated only
by the JSON kind of the top-level value — so it decodes to an ADT. And
`ContentEntry.File`'s `content` is an `Option` because Forgejo sends `null` for it
when the entry was listed as part of a directory rather than fetched by its own
path. Absent means "not sent", never "empty file".

### Why the guard makes the retry decision defensible, and still `Never`

`updateFile` is `RetryEligibility.Never`. After a lost success the file's sha has
already changed, so a repeat cannot overwrite anything — it fails with a `409`.
That is the right failure. It is still a failure reported for a call that in fact
succeeded, which is exactly the case the retry rules say not to repeat.

So the guard makes an accidental repeat *harmless*, and the eligibility makes it
*not happen*. Both, not either.

### Handling the conflict

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException

def isStale(failure: Throwable): Boolean =
  failure match
    case CodebergException(CodebergError.Api(_, 409, _)) => true
    case _                                              => false
```

A `409` here means "somebody else wrote first". The correct reaction is to read
the file again, re-apply your change to the *new* contents, and write again with
the *new* sha — not to strip the guard.

## Writing several files as one commit

Four separate `createFile` calls produce four commits and four chances to leave
the repository half-changed. `ChangeFiles` produces one commit that either lands
whole or does not land at all:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.admin.ChangeFiles
import com.worxbend.codeberg4s.repositories.admin.CommitOptions
import com.worxbend.codeberg4s.repositories.admin.FileBytes
import com.worxbend.codeberg4s.repositories.admin.FileChangeSet
import com.worxbend.codeberg4s.repositories.admin.FileOperation
import com.worxbend.codeberg4s.repositories.BranchName

import scala.concurrent.Future

def scaffold(client: CodebergClient, owner: Owner, name: RepoName): Either[ValidationError, Future[FileChangeSet]] =
  for
    readme  <- ContentPath.from("README.md")
    licence <- ContentPath.from("LICENSE")
    branch  <- BranchName.from("chore/scaffold")
  yield
    val batch = ChangeFiles
      .of(FileOperation.Create(readme, FileBytes.ofText("# new project\n")))
      .and(FileOperation.Create(licence, FileBytes.ofText("MIT\n")))
      .committing(
        CommitOptions.Default
          .describedAs("Add README and licence")
          .onNewBranch(branch)
      )

    client.repos.admin.changeFiles(owner, name, batch)
```

`FileOperation` is an enum rather than one case class with three `Option`s: a
delete has no content, a create has no sha, and a flat shape would let you build
both of those mistakes and find out from a `422`. The order of operations is
preserved and is Forgejo's application order, which matters when one operation
moves a file another then edits.

`ChangeFiles.of` demands the first operation, so an empty batch — which Forgejo
rejects — is unrepresentable.

## Commit options

Every contents write shares one settings type:

| Builder | Effect |
| --- | --- |
| `on(branch)` | base the change on `branch` rather than the default branch |
| `onNewBranch(target)` | create `target` from the base branch and commit onto it |
| `describedAs(text)` | the commit message; absent lets Forgejo compose one |
| `authoredBy(identity)` / `committedBy(identity)` | attribution; absent uses the token's account |
| `dated(dates)` | explicit author and committer dates; absent uses the time of the request |
| `signedOff` | add a `Signed-off-by` trailer |
| `overwritingNewBranch` | force-push when `onNewBranch`'s target already exists |

`onNewBranch` is how you open a change for review without touching the default
branch, and it is usually what an automation should do.

`overwritingNewBranch` force-pushes. It is off by default and should stay off
unless you have thought about it: a silent force-push is not something a library
should do on anyone's initiative.

## Testing your writes

The most valuable assertion about a write is not what came back — it is what went
out. [Testing your code](./07-testing-your-code.md) shows how to record the
request and assert on the body your command serialised to, which is the check
that catches a field you thought you were sending and were not.

## Next

- [Errors](./03-errors.md) — the `409`, `422` and `403` you will meet here.
- [Retries and rate limits](./05-retries-and-rate-limits.md) — the eligibility
  rules in full.
