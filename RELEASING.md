# Releasing codeberg4s

Everything needed to cut a release, verify it landed, and deal with one that
should not have. The tag-driven half is automated in
[`.github/workflows/release.yml`](.github/workflows/release.yml); this document
is that workflow explained, plus the parts a machine cannot do.

Nothing has been published yet. `Publish.version` in `build.mill` reads
`0.1.0-SNAPSHOT`, and the Maven Central coordinates in `README.md` resolve to
nothing until the first `v0.1.0` tag is pushed.

---

## What a release consists of

Five artifacts, one version, one tag.

| Module | Artifact id on Central | Depends on |
| --- | --- | --- |
| `modules.domain` | `com.worxbend:codeberg4s-domain_3` | nothing |
| `modules.core` | `com.worxbend:codeberg4s-core_3` | domain |
| `modules.codec` | `com.worxbend:codeberg4s-codec_3` | domain, core |
| `modules.transport` | `com.worxbend:codeberg4s-transport_3` | core, codec |
| `modules.client` | `com.worxbend:codeberg4s-client_3` | core, codec, transport |

The `_3` suffix is Mill's Scala binary-version suffix; the `artifactName`
values in `build.mill` are the part without it, written out per module rather
than derived from the module path, because an artifact id is a permanent
promise.

`modules.it` and `modules.examples` are **not** `PublishModule`s and therefore
cannot be released. Mill requires every `moduleDeps` entry of a
`PublishModule` to be one too, so that exclusion is enforced by the module
graph rather than by a list someone has to remember to update.

Each of the five pushes four files — the POM, the main jar, `-sources.jar` and
`-javadoc.jar` — plus a PGP signature and checksums for every one of them.
That is what Maven Central requires and what `publishAll` produces.

---

## Versioning policy

`build.mill` declares `versionScheme = Some(VersionScheme.EarlySemVer)`, and
that is the promise consumers get. Under Early SemVer, while the major version
is `0`, **the minor position carries breaking changes**:

- `0.1.0` → `0.1.1` — compatible. Safe to bump without reading anything.
- `0.1.0` → `0.2.0` — may break. Read the changelog.
- After `1.0.0`, the usual rule: breaking changes bump the major, compatible
  additions bump the minor, fixes bump the patch.

The public API is five artifacts of opaque types, `enum`s, `final case class`
models and `Future`-returning methods. That shape makes several changes
breaking that do not look it, so the rules below are written in terms of what
is actually in the jar.

### A patch release may

- Fix a bug without changing any signature.
- Fix a codec so a payload that previously produced `DecodingFailed` now
  decodes — the shape of the model is unchanged, only its reachability.
- Change retry timing, jitter or `Retry-After` handling, within the documented
  policy.
- Improve Scaladoc, examples, guides or error message text. Message text is
  explicitly not part of the API: `CodebergError.describe` is for humans, and
  matching on its string is a bug in the caller.
- Refactor anything not visible in a `public` or `protected` signature.

### A minor release may (before `1.0.0`, so may a `0.x.0`)

- Add operations, `*Api` classes and accessors on `CodebergClient`.
- Add methods to an existing `*Api` class, on both rails.
- Add a new module — but see below: a *new* artifact is additive, and a
  *renamed* one is a removal.
- Widen what a smart constructor accepts, or add a new smart constructor
  alongside the existing one.
- Add a new value to an *open* enum such as `NotificationSubjectType`, which
  already carries an `Other(raw)` case precisely so that upstream additions are
  not breaking.
- **Add a field to a response model** — a model decoded from a Forgejo payload
  and never built by a caller, such as `Repository`, `Issue`, `PullRequest`,
  `User` or `ServerApiSettings`. Their constructors are `private[codeberg4s]`,
  so no caller outside this library can be calling `apply` or `copy`, and a
  new field cannot break source compatibility for anyone. See the note below
  on why this exemption exists and what it does not cover.

### These are breaking, and are never a patch

- **Adding, removing or reordering a field on a *command* `final case class`.**
  The generated `apply`, `copy` and `unapply` change signature, so previously
  compiled callers fail to link. A command model — `CreateIssue`,
  `EditRepository`, `MergePullRequest`, every `*Query` — has a public
  constructor because a caller has to build one to make a request, so its
  shape is part of the API. Adding a field with a default does not help:
  default arguments are banned here anyway, and they would not make it binary
  compatible.

#### Why response models are exempt

Forgejo adds fields to its response payloads routinely. When every model was a
public case class, each of those additions changed a generated `<init>`,
`apply`, `copy` and `copy$default$N` in this library, so tracking upstream
meant a breaking release — a major version, once `0.1.0` is the baseline. That
would have been a major version of this library for a field nobody asked for.

The constructors of response models are therefore `private[codeberg4s]`. Every
source tree in this repository lives under `com.worxbend.codeberg4s`, so each
DTO's `toDomain` and every test fixture still builds them; only code outside
the library loses the constructor. Reading fields and pattern matching are
untouched, so a caller can still destructure a `Repository` in a `match`.

Two things this exemption does **not** cover:

- **Removing, renaming or retyping an existing field is still breaking**, on
  both source and binary compatibility. The exemption is for growth only.
- **A qualified-private constructor is still public in the bytecode**, so a
  binary-compatibility checker can still complain about one. It complains far
  less than expected, and only about one member — see
  ["What MIMA reports for a response model"](#what-mima-reports-for-a-response-model)
  below, which measures it. The short version: put a new field **last**, and
  the whole cost is one filter line for that model. Those filters are lines to
  write, not releases to renumber, because no external caller can have
  compiled against the member being filtered.
- **Adding a case to a closed `enum`.** `CodebergError` has exactly five
  cases — `Transport`, `Api`, `DecodingFailed`, `Validation`,
  `RetriesExhausted` — and every consumer that matches on it exhaustively
  stops compiling when a sixth appears. There is deliberately no `NotFound`
  and no `RateLimited`; a `404` is `Api(ctx, 404, body)` and a `429` is
  `Api(ctx, 429, body)` or a `RetriesExhausted` wrapping one. Keeping it that
  way is a compatibility decision, not only a modelling one.
- **Changing the underlying representation of an opaque type.** `Owner`,
  `RepoName`, `Username`, `ApiToken`, `BaseUri` and friends erase to their
  representation type in the bytecode. Swapping a `String` representation for
  anything else is invisible in the source and fatal at link time.
- **Adding an abstract member to a public trait.** `Telemetry[F]` declares
  three — `onRequest`, `onResponse`, `onError` — and every application that
  implements it breaks when a fourth appears. A new *concrete* member is
  compatible for callers, but can still collide with a name an implementor
  already chose, so treat it as minor at best.
- **Changing a method's parameters, result type or arity**, including turning
  a `Future[A]` into a `Future[Page[A]]`.
- **Moving a type between packages or modules**, or renaming an artifact.
- **Tightening a smart constructor** so a value that used to be accepted is
  now a `ValidationError`. Compatible at link time, breaking at run time,
  which is worse.

### Binary compatibility is checked by MIMA

MIMA — the Migration Manager — is the tool that turns the policy above from a
promise into a check. It reads the class files this build produces, reads the
class files of an already-released version, and reports every difference that
would stop a program compiled against the old jar from linking against the new
one. It works on bytecode, so it catches the changes that are invisible from
the source side: a `copy` overload that quietly changed arity, an opaque type
whose representation moved.

It is wired. `build.mill`'s header declares the plugin

```
//| - com.github.lolgab::mill-mima::0.2.2
```

and `Codeberg4sPublishModule` mixes in `com.github.lolgab.mill.mima.Mima`.
The two colons before the version are Mill's "add the Mill platform suffix"
spelling: Mill resolves that coordinate to `mill-mima_mill1_3`, the build for
the Mill 1.x line that `.mill-version` pins at `1.1.7`. `0.2.2` was the latest
stable version in
`repo1.maven.org/maven2/com/github/lolgab/mill-mima_mill1_3/maven-metadata.xml`
when this was written.

Mixing it into the shared trait is what makes one declaration cover all five
artifacts. The plugin builds each coordinate to download out of
`pomSettings().organization`, `artifactId()` and `mimaPreviousVersions`, so
`modules.codec` is checked against `com.worxbend:codeberg4s-codec_3` without
that string appearing anywhere.

Run it across all five, or one module at a time:

```bash
./mill modules.__.mimaReportBinaryIssues
./mill modules.domain.mimaReportBinaryIssues
```

#### It cannot pass yet, and that is the intended state

Nothing has been published, so there is no jar to compare against.
`Publish.binaryCompatibleWith` in `build.mill` is therefore `Seq.empty`, and
the command stops with the plugin's own message:

```
[error] modules.domain.mimaPreviousArtifacts No previous artifacts configured.
Please override mimaPreviousVersions or mimaPreviousArtifacts.
```

That is the honest answer to "is this build compatible with nothing?", and it
costs nothing, because no other task depends on that command. `compile`,
`test` and `verify.sh` do not reach it, so the empty list cannot fail the
gate.

**It is deliberately not in `verify.sh`.** The check downloads the previous
artifacts from Maven Central, and the fast gate has to run offline and in
seconds. It belongs in the release procedure instead, which is where the
checklist at the bottom of this document puts it.

#### Turning it on, after `0.1.0` is published

In the follow-up commit that moves `main` on to the next `-SNAPSHOT`, change
one line in `build.mill`:

```scala
val binaryCompatibleWith: Seq[String] = Seq("0.1.0")
```

From then on the list holds every release inside the current compatibility
window. Under Early SemVer that is every `0.1.x` while the minor is still
`1`; when a deliberate break bumps the minor to `0.2.0`, the list resets to
just `0.2.0` and grows again from there.

One trap is worth naming before somebody hits it. Because
`mimaPreviousVersions` lives on the shared trait, **every module that will
ever extend that trait inherits the claim that each listed version of it
exists on Central**. A sixth artifact first published in, say, `0.3.0` would
send MIMA looking for a `codeberg4s-newthing_3:0.1.0` that was never uploaded,
and the run would fail on a download error that says nothing at all about
compatibility. Such a module overrides the list with the releases that really
exist for it; the scaladoc on `Codeberg4sPublishModule.mimaPreviousVersions`
carries the snippet.

#### What MIMA reports for a response model

The policy above lets a `0.x.0` add a field to a response model, on the
grounds that its constructor is `private[codeberg4s]` and no outside caller
can be calling it. Scala erases qualified private to plain `public` bytecode,
so the obvious worry is that MIMA sees `<init>`, `apply` and `copy` on all
131 of those classes and objects to a field being added to any of them.

**MEASURED, NOT RECALLED.** On 2026-08-09, against mill-mima `0.2.2` and
Scala `3.8.4`, the worry turns out to be mostly unfounded, and the part that
survives is one line per model:

| Change | Problems reported |
| --- | --- |
| Field appended to `HeatmapEntry` (2 fields, `private[codeberg4s]`) | 1 |
| Field appended to `User` (21 fields, `private[codeberg4s]`) | 1 |
| Field inserted at position 1 of `User` | 6 |
| Field inserted at position 1 of `CreateIssue` (public constructor) | 14 |

The single problem in the first two rows is always the same shape:

```
* static method apply(...)com.worxbend.codeberg4s.users.User
  in class com.worxbend.codeberg4s.users.User
  does not have a correspondent in current version
  filter with: ProblemFilter.exclude[DirectMissingMethodProblem](
    "com.worxbend.codeberg4s.users.User.apply")
```

Read the words `static method … in class`. That is not the companion object's
`apply`; it is the **static forwarder** Scala 3 emits on the class so Java
callers can reach the companion's method. MIMA does read Scala 3's own
signature and does honour `private[codeberg4s]` — the constructor (`this`),
`copy`, every `copy$default$N` and the companion object's real `apply` are all
correctly treated as inaccessible and never reported. The forwarder is the one
member that carries no Scala-side access information, so it leaks, and it
leaks exactly once per model.

Two consequences, both practical:

- **Append new fields; never insert them.** The `_1`, `_2`, … accessors that
  `Product` requires are genuinely public and genuinely change result type
  when a field is inserted ahead of them. Row three above is the same
  one-field change as row two, moved to the front, and it costs five extra
  reports that are not synthetic noise. Appending keeps the bill at one line.
- **The exemption really is about response models.** Row four is the same
  insertion into a command model, whose constructor is public: `this`, `copy`,
  every `copy$default$N`, both `apply`s and the `_N` accessors are all
  reported. That is the check doing its job, and it is why the filter written
  below names one class at a time.

##### The filter, and how to write it

`mill-mima` accepts filters through `mimaBinaryIssueFilters`. The name is
matched against the fully qualified member name and `*` is a wildcard that
spans package dots — both spellings below were confirmed to clear the report
in the measurement above. **Use the exact one.** A wildcard such as
`"com.worxbend.codeberg4s.users.*"` also works, and that is the problem: it
would silence a genuinely breaking change to a command model in the same
package just as effectively.

So the filter is written **when a field is actually added**, one line for the
model that gained it, in the same commit — not as a standing 131-line blanket
that hides nothing today and something real tomorrow. There is no
`mimaBinaryIssueFilters` in `build.mill` right now for exactly that reason.

When the day comes, widen the import in `build.mill` and add the override to
`Codeberg4sPublishModule`:

```scala
import com.github.lolgab.mill.mima.{DirectMissingMethodProblem, Mima, ProblemFilter}

// …inside trait Codeberg4sPublishModule…

  /** One line per response model that gained a field since the versions in
    * `Publish.binaryCompatibleWith`. Each entry filters the static `apply`
    * forwarder Scala 3 emits for a `private[codeberg4s]` constructor, which no
    * caller outside this library can have compiled against.
    */
  def mimaBinaryIssueFilters = Task {
    Seq(
      // 0.2.0: Forgejo added `pronouns` to the user payload.
      ProblemFilter.exclude[DirectMissingMethodProblem]("com.worxbend.codeberg4s.users.User.apply")
    )
  }
```

Note it is `ProblemFilter.exclude`, singular — sbt-mima spells the same thing
`ProblemFilters.exclude`, and the plural does not compile here.

Every entry carries a comment naming the release and the field, so the list
can be pruned when the compatibility window resets at the next minor bump.
Anything MIMA reports that is *not* that one forwarder shape is a real
finding: read it against the policy above and renumber the release rather than
filtering it.

##### Reproducing the measurement

The numbers above are worth re-taking whenever Scala or mill-mima moves,
because they are a fact about a compiler's code generation, not about this
library. The procedure, which touches nothing outside the worktree except a
local Ivy directory it then deletes:

```bash
# 1. Give MIMA something to compare against. `publishLocal` writes to
#    ~/.ivy2/local, which Coursier searches by default, so the plugin can
#    resolve it with no network and no Central involved.
sed -i 's/0.1.0-SNAPSHOT/0.1.0/' build.mill      # temporarily
./mill modules.domain.publishLocal

# 2. Point the check at it, and make the change being measured.
#    Set `binaryCompatibleWith` to Seq("0.1.0") and edit a model.
./mill modules.domain.mimaReportBinaryIssues

# 3. Put everything back. Leaving a fake 0.1.0 in the local Ivy cache would
#    make a later run check against a jar nobody released.
git checkout -- build.mill modules/domain/src
rm -rf ~/.ivy2/local/com.worxbend/codeberg4s-domain_3
```

Until `0.1.0` exists on Central, the policy in this section is enforced by
review. Say so in the pull request when a change touches a public signature.

---

## One-time prerequisites

### A verified Central namespace

Publishing under `com.worxbend` requires that namespace to be verified in the
Sonatype Central portal, which for a `com.` group id means proving control of
`worxbend.com` (typically a DNS TXT record). **If that domain is not
available, the group id has to change before the first release, not after** —
a published group id cannot be moved, and every consumer's build file names
it. The alternative is a code-hosting namespace such as `io.codeberg.worxbend`
or `io.github.<account>`, verified by ownership of the account. Changing it
means editing `Publish.group` in `build.mill` and every coordinate in
`README.md`.

This is the one prerequisite that can block a release for days, so settle it
first.

### A signing key

Maven Central requires every artifact to be PGP-signed by a key published to a
public keyserver.

```bash
# Create one, if there is not already a release key.
gpg --quick-generate-key "worxbend <you@example.org>" rsa4096 sign 2y

# The key id — everything below needs it.
gpg --list-secret-keys --keyid-format=long

# Publish the public half. Central checks it can find the key.
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>

# Export the secret half for CI, base64 with no line wrapping.
gpg --armor --export-secret-keys <KEYID> | base64 -w0
```

Keep the passphrase. Losing it means generating a new key and re-verifying it,
which is survivable; leaking the secret half means revoking a key that has
signed released artifacts, which is not.

### Repository secrets

`.github/workflows/release.yml` reads exactly four secrets and maps them onto
the four environment variables Mill's publisher looks for. These names were
read out of Mill 1.1.7's own sources
(`mill.javalib.publish.SonatypeHelpers` and
`mill.javalib.internal.PublishModule`), not recalled.

| Repository secret | Environment variable Mill reads | What it is |
| --- | --- | --- |
| `SONATYPE_USERNAME` | `MILL_SONATYPE_USERNAME` | the *token* username generated in the Central portal, not the account login |
| `SONATYPE_PASSWORD` | `MILL_SONATYPE_PASSWORD` | the matching token password |
| `PGP_SECRET_BASE64` | `MILL_PGP_SECRET_BASE64` | output of the `gpg --armor --export-secret-keys …` piped through `base64 -w0`, above |
| `PGP_PASSPHRASE` | `MILL_PGP_PASSPHRASE` | the key's passphrase; may be empty if the key has none |

They are passed as environment variables rather than as command-line
arguments, so they never reach the process table or Mill's own logs. The
workflow fails early, before signing anything, if the first three are empty.

The workflow also runs in a `maven-central` GitHub environment. Configure that
environment with a required reviewer: it is the last human checkpoint before
an artifact becomes immutable.

---

## Cutting a release

1. **Land everything.** `main` is green and contains the whole release.

2. **Write the changelog entry.** Move the `Unreleased` section into a version
   heading in `CHANGELOG.md`, with the date. Anything from the "breaking"
   list above goes at the top of the entry, in the imperative: what a consumer
   must change.

3. **Set the version.** In `build.mill`:

   ```scala
   object Publish:
     val version = "0.1.0"
   ```

   Drop the `-SNAPSHOT`. This is the only place a version is written; the tag
   does not set it, it confirms it.

4. **Run the gate.**

   ```bash
   ./verify.sh
   ./verify.sh --with-slow      # duplication against its baseline, and CRAP
   ```

5. **Check binary compatibility**, which the gate deliberately leaves out
   because it needs the network:

   ```bash
   ./mill modules.__.mimaReportBinaryIssues
   ```

   Skip this one for `0.1.0` only — `Publish.binaryCompatibleWith` is empty
   until `0.1.0` is on Central, so there is nothing to compare against and the
   command says so. From `0.1.1` onwards it must be green, or every report it
   makes must be either a filter written per
   ["The filter, and how to write it"](#the-filter-and-how-to-write-it) or a
   reason to renumber the release.

6. **Prove the artifacts assemble** before asking a public repository to
   accept them:

   ```bash
   ./mill modules.__.publishLocal
   ```

   Then, from a scratch project, depend on `com.worxbend::codeberg4s-client:0.1.0`
   and compile something against it. `modules/examples` is exactly the code to
   try. This catches a broken POM, a missing transitive dependency and a
   `docJar` that failed, none of which the test suite can see.

7. **Commit, on a branch, with the version bump as its own change.**

   ```
   chore(build): release 0.1.0
   ```

8. **Tag the merge commit** and push the tag.

   ```bash
   git tag -a v0.1.0 -m "codeberg4s 0.1.0"
   git push origin v0.1.0
   ```

   The tag convention is `v` followed by the exact `Publish.version` string.
   The workflow refuses to publish when the two disagree, and refuses a tag
   whose version still ends in `-SNAPSHOT`.

9. **Watch the workflow.** It re-runs `./verify.sh` from a clean checkout
   before publishing anything. A release is not exempt from the gate; it is
   the build that most needs it.

10. **Verify it landed** — see below — and only then announce it.

### What `publishAll` actually does

```
./mill mill.javalib.SonatypeCentralPublishModule/publishAll
```

- Resolves `__:PublishModule.publishArtifacts`, which is every `PublishModule`
  in the build: the five library modules and nothing else.
- Imports the PGP secret from `MILL_PGP_SECRET_BASE64` and signs every file.
  Mill 1.x signs in-process; a `gpg` binary on the runner is not required.
- Uploads one bundle to the Sonatype Central portal, authenticating with the
  token pair.
- Defaults to `shouldRelease = true`, which means the bundle is released
  automatically once validation passes. To stage a bundle and inspect it in
  the portal before it becomes public, run
  `./mill mill.javalib.SonatypeCentralPublishModule/publishAll --shouldRelease false`
  and press the button by hand. That is worth doing for `0.1.0`.

### Snapshots

`publishAll` recognises a version ending in `-SNAPSHOT` and sends it to the
snapshot repository instead. Snapshots are useful for letting someone test a
fix before a release, and they are not releases: they are mutable, they are
not indexed, and nothing in this document about tags or changelogs applies to
them.

---

## Verifying the release landed

Publication to Central is asynchronous. Do all four:

1. **The files are there.** For each artifact:

   ```
   https://repo1.maven.org/maven2/com/worxbend/codeberg4s-client_3/0.1.0/
   ```

   Expect the POM, the jar, `-sources.jar`, `-javadoc.jar`, an `.asc` for each
   and the checksums. A missing `-javadoc.jar` is the classic failure — it is
   why `Codeberg4sPublishModule` strips `-Werror` from `scalaDocOptions`,
   since one unresolved `[[link]]` would otherwise fail `docJar` and Central
   rejects a bundle without a Javadoc jar.

2. **A stranger can resolve it.** From an empty directory, with a cold cache:

   ```bash
   COURSIER_CACHE=$(mktemp -d) \
     scala-cli -e 'println(1)' --dep com.worxbend::codeberg4s-client:0.1.0
   ```

   Resolution failing here means the POM is wrong even though the files exist.

3. **The signature verifies.**

   ```bash
   gpg --verify codeberg4s-client_3-0.1.0.jar.asc codeberg4s-client_3-0.1.0.jar
   ```

4. **The README is true.** Copy the dependency line out of `README.md` — the
   one a new user will copy — and build with it.

Then push a follow-up commit setting `Publish.version` to the next
`-SNAPSHOT`, so `main` is never sitting on a version that has already been
published. The same commit adds the version just released to
`Publish.binaryCompatibleWith`, which is what arms MIMA for the next release —
the artifacts are on Central by now, so there is finally something to compare
against.

---

## When a release is bad

**A released version cannot be changed, and it cannot be deleted.** Maven
Central is immutable by design; that is the property everything else depends
on. There is no yank.

What can be done, in order of preference:

1. **Supersede it.** Fix the problem and release the next version
   immediately. For a broken `0.1.0`, that is `0.1.1` if the fix is
   compatible and `0.2.0` if it is not.

2. **Mark it in the changelog.** Add a note under the bad version saying what
   is wrong with it and which version replaces it. That entry is what someone
   pinned to it will find.

3. **Deprecate, do not delete.** If a type or method has to go, mark it
   `@deprecated` with the replacement named, keep it for at least one minor
   cycle, and remove it in a version whose number says it is breaking.

4. **In a genuine emergency** — a released artifact contains a credential, or
   is not the code in the tag — contact Sonatype Central support. Removal
   after publication is exceptional and at their discretion; assume it will
   not happen and supersede as well.

If the bad artifact leaked a secret, the secret is compromised whatever
happens to the artifact. Rotate it first, then worry about the jar. See
[`SECURITY.md`](SECURITY.md).

---

## Checklist

Copy this into the release pull request.

- [ ] `CHANGELOG.md` entry written, breaking changes first
- [ ] `Publish.version` set, `-SNAPSHOT` dropped
- [ ] `./verify.sh` and `./verify.sh --with-slow` green
- [ ] `./mill modules.__.mimaReportBinaryIssues` green, or every report
      filtered with a reason (not applicable to `0.1.0`)
- [ ] `./mill modules.__.publishLocal`, then something compiled against it
- [ ] Public signature changes reviewed against the versioning policy above
- [ ] Version bump committed on its own, `chore(build): release X.Y.Z`
- [ ] Tag `vX.Y.Z` pushed, matching `Publish.version` exactly
- [ ] Release workflow green
- [ ] All four verification steps done
- [ ] `main` moved on to the next `-SNAPSHOT`
- [ ] `Publish.binaryCompatibleWith` extended with the version just released,
      in that same follow-up commit
