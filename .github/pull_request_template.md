<!--
Thank you for this. The checklist below is not ceremony: every line of it is
something CI or a reviewer will check anyway, so ticking it honestly is the
fastest route through review. CONTRIBUTING.md explains each one.

Leave a box unticked rather than ticking it hopefully. "I could not run
verify.sh because I have no JDK 21" is a useful sentence; a tick that turns
out to be false costs a review round-trip.
-->

## What this changes

<!-- One paragraph. What behaviour is different after this than before it? -->

## Why

<!--
The reasoning, not the diff. If it fixes an issue, "Refs: #123" here and in
the commit footer.
-->

## How it was verified

<!--
Which suites, which commands, and anything you tested by hand against a live
instance. If you exercised modules/it, say which suite and against what.
-->

---

## Checklist

**The gate**

- [ ] `./verify.sh` passes locally — format, lint, zero-warning compile, the
      unit suite, the architecture-boundary check and coverage.
- [ ] `./mill mill.scalalib.scalafmt/` and `./mill modules.__.fix` have been
      run, so the diff carries no reformatting noise. `.scalafmt.conf` uses
      vertical alignment; an unformatted commit produces a realignment diff in
      somebody else's pull request later.
- [ ] New Scala files were `git add`-ed **before** the format check. Scalafmt
      is configured with `project.git = true` and silently skips untracked
      files; `verify.sh` warns about this, and the warning is worth reading.

**The commits**

- [ ] Conventional Commit subject: `<type>(<scope>): <subject>`, imperative,
      lowercase, no trailing period, at most 72 characters.
- [ ] One logical change per commit. A rename, a behaviour change and a
      dependency bump are three commits. Formatting-only churn is its own
      `style:` commit.
- [ ] Every commit builds. No knowingly broken intermediate state.
- [ ] **No AI or tooling trailers.** No `Co-Authored-By:` for an assistant, no
      session metadata, no "Generated with" footer. The repository owner is the
      only author and committer. A commit message ends at its last content
      line.
- [ ] A breaking change is marked `!` in the subject **and** carries a
      `BREAKING CHANGE:` footer — see RELEASING.md for what counts as breaking
      across five artifacts of opaque types.

**The code**

- [ ] House style holds: Scala 3 indentation syntax, explicit result types on
      public and protected members, no `null`, `return`, `throw`, `var`,
      `asInstanceOf`, `isInstanceOf`, `Option`'s unsafe accessor, `println`,
      default arguments or universal equality. Scalafix enforces all of these;
      if you had to work around a rule, say why in the description.
- [ ] Architecture boundaries hold — `domain` depends on nothing but the
      standard library, `core` knows nothing of sttp, upickle or `Future`,
      `codec` knows nothing of the transport, and no production code uses
      `Await` or bare exceptions. `verify.sh` greps for these.
- [ ] Recoverable failures are `Either[CodebergError, A]` on the typed rail and
      a failed `Future` carrying `CodebergException` on the other. No new error
      case was invented outside the five the ADT declares.
- [ ] Nothing new can carry a credential into a message, a `toString`, an error
      or a log line.

**New public API**

- [ ] Scaladoc on every new public and protected member, stating the error
      contract — which `CodebergError` cases the operation can produce, and
      what a `404` means for it specifically.
- [ ] Listings return `Future[Page[A]]` and take `PageParams`. No operation
      returns an unbounded collection, and no code decides "last page" from
      `items.size`; the RFC 5988 `Link` header is the only end-of-pages signal.
- [ ] Identifiers are opaque types with `Either`-returning smart constructors,
      not bare `String`.
- [ ] Both rails: the direct method and its `attempt` counterpart, sharing one
      implementation.

**Tests and records**

- [ ] Tests live next to the behaviour they cover, and a new codec is tested
      against a golden fixture rather than against hand-written JSON. Fixtures
      are ground truth; the Swagger specification is not.
- [ ] A new fixture is a verbatim capture and is recorded in
      `modules/codec/test/resources/golden/MANIFEST.md`.
- [ ] Property suites carry the `Property` munit tag, so they stay out of the
      routine gate.
- [ ] **An operation was added:** its checkbox in `docs/API_INVENTORY.md` is
      ticked, and the §0 counts are updated to match. A tick means reachable
      from `CodebergClient` on both rails — nothing less.
- [ ] A shared model was introduced or moved: `docs/LEDGER.md` records who owns
      it. Duplicating a model instead of importing it is a review-blocking
      defect.
- [ ] A documented behaviour changed: `README.md`, the affected guides and
      `CHANGELOG.md` say so.
