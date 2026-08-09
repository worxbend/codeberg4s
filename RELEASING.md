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
- **A qualified-private constructor is still public in the bytecode.** MIMA,
  once wired, will keep reporting the synthetic members. Those reports are
  filters to write, not releases to renumber, because no external caller can
  have compiled against them — but somebody has to write the filter, and the
  reasoning belongs in the commit that adds it.
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

### Binary compatibility is not currently enforced

**MIMA is not wired, because there is no baseline to check against.** No
version of this library has ever been published, so there is nothing for a
compatibility checker to compare a build to; adding it now would be a plugin
that runs and reports on the empty set.

**This is the first task after `0.1.0` ships.** The work is:

```scala
//| mvnDeps:
//| - com.github.lolgab::mill-mima::0.2.2

import com.github.lolgab.mill.mima.Mima

trait Codeberg4sPublishModule extends Codeberg4sModule with PublishModule with Mima:
  def mimaPreviousVersions = Seq("0.1.0")
```

on the shared publish trait, so all five artifacts are covered by one
declaration, plus a `./mill modules.__.mimaReportBinaryIssues` step in
`verify.sh`. `com.github.lolgab::mill-mima::0.2.2` is published for Mill 1.x
(`mill-mima_mill1_3`), which is the Mill in `.mill-version`; the trait is
`com.github.lolgab.mill.mima.Mima` and the task is `mimaPreviousVersions`.
That coordinate and those names were read off the published artifact, not
recalled — but the plugin has not been run against this build, so treat the
snippet as the starting point of that task and not as a verified
configuration.

Until then, the policy above is enforced by review alone. Say so in the pull
request when a change touches a public signature.

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

5. **Prove the artifacts assemble** before asking a public repository to
   accept them:

   ```bash
   ./mill modules.__.publishLocal
   ```

   Then, from a scratch project, depend on `com.worxbend::codeberg4s-client:0.1.0`
   and compile something against it. `modules/examples` is exactly the code to
   try. This catches a broken POM, a missing transitive dependency and a
   `docJar` that failed, none of which the test suite can see.

6. **Commit, on a branch, with the version bump as its own change.**

   ```
   chore(build): release 0.1.0
   ```

7. **Tag the merge commit** and push the tag.

   ```bash
   git tag -a v0.1.0 -m "codeberg4s 0.1.0"
   git push origin v0.1.0
   ```

   The tag convention is `v` followed by the exact `Publish.version` string.
   The workflow refuses to publish when the two disagree, and refuses a tag
   whose version still ends in `-SNAPSHOT`.

8. **Watch the workflow.** It re-runs `./verify.sh` from a clean checkout
   before publishing anything. A release is not exempt from the gate; it is
   the build that most needs it.

9. **Verify it landed** — see below — and only then announce it.

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
published.

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
- [ ] `./mill modules.__.publishLocal`, then something compiled against it
- [ ] Public signature changes reviewed against the versioning policy above
- [ ] Version bump committed on its own, `chore(build): release X.Y.Z`
- [ ] Tag `vX.Y.Z` pushed, matching `Publish.version` exactly
- [ ] Release workflow green
- [ ] All four verification steps done
- [ ] `main` moved on to the next `-SNAPSHOT`
