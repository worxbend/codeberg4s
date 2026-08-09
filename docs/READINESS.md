# Readiness review — what stands between this repo and a third party using it

The library itself is done: 439 of 439 in-scope operations, `./verify.sh
--with-slow` green, 100 % domain coverage, 3658 tests. What follows is
everything *else* a consumer needs, assessed honestly against the state of the
repo rather than against intentions.

Every claim below was checked against the repository on 2026-08-09. Where
something could not be checked from inside a clone — whether a workflow has
actually run on the forge, whether a page is actually served — it says so
rather than guessing.

## Blocking — a stranger cannot use the library without these

| Gap | Why it blocks | Where it lands |
| --- | --- | --- |
| **Nothing is published.** `build.mill` has full `PublishModule` config, but `Publish.version` is still `0.1.0-SNAPSHOT`, `git tag` lists nothing, and no artifact exists on Maven Central — so the README's coordinates resolve to nothing. | A dependency line that does not resolve is the first and last thing a new user tries. | cut the `v0.1.0` tag; `.github/workflows/release.yml` |
| **No mutation score.** `scripts/mutate.sh` exists and the Stryker4s runner is proven against these sources, but a scored run has never completed — it needs roughly 1400 `mill test` invocations. `PLAN.md` §10 asks for ≥ 80 %. | The number that would justify trusting the test suite does not exist. Coverage says lines were executed, not that a broken line would be caught. | a nightly run long enough to finish |

## Important — these decide whether adoption survives contact

| Gap | Why it matters |
| --- | --- |
| **The site has never been observed deployed.** `.github/workflows/site.yml` builds with `scripts/site.sh` and publishes `out/site/html`, and the guides' snippets are compiled against the real sources on every pull request. But the workflow depends on a repository setting (Settings → Pages → Source must be "GitHub Actions") that cannot be verified from a clone. Until someone confirms the page is served, treat hosted documentation as unproven rather than done. |
| **Duplication is tracked debt, not a clean result.** `./verify.sh --with-slow` passes, but it passes against a *recorded baseline* of 363 groups, of which 762 of the 1350 reported locations are in `modules/codec`. The gate fails on any increase, which is the useful property; it does not mean the code is free of duplication. `docs/LEDGER.md` names the helpers involved. |
| **Property coverage is narrow.** ScalaCheck suites carry the `Property` tag and are excluded from the default gate. They reach the domain module's identifiers, secrets, pagination and retry bounds; the codec round-trip laws `PLAN.md` §6.3 asks for still have no property suite. |

## Resolved since this document was first written

Checked, not assumed:

- **Release process** — `RELEASING.md` documents the steps, the signing key handling and the tag convention.
- **Contribution path** — `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `.github/pull_request_template.md` and four issue templates all exist.
- **CI a contributor can see** — GitHub workflows (`ci`, `nightly`, `release`, `site`) alongside the Forgejo one, and the repository now has a remote. Every `uses:` is pinned to a commit SHA, and `.github/dependabot.yml` keeps those pins current.
- **Binary-compatibility policy** — MIMA is wired on the shared publish trait via `mill-mima` 0.2.2, armed by setting `Publish.binaryCompatibleWith` once 0.1.0 exists. `RELEASING.md` carries the measured filter needed for the static forwarders on response models.
- **Worked examples that compile** — `modules/examples` is inside the gate, so an example that stops compiling fails the build. `verify.sh` step 3 exists specifically to stop a source tree drifting outside the build.
- **Task-oriented documentation** — ten guides under `site/src/guides/`, with mdoc compiling their snippets.
- **`--with-slow` is green** — it was red at 378 groups against a stale 323 baseline; the baseline is now measured, and two refactors paid 15 groups off rather than absorbing them.

## Nice to have — not blocking 0.1.0

- Dependency update automation for the Scala and Mill side (Renovate; Codeberg supports it, and Dependabot has no Mill support). The GitHub Actions pins are already covered by `.github/dependabot.yml`.
- A spec-drift issue opener rather than a warning in a log.
- Scala.js / Native cross-builds, which `PLAN.md` §0 explicitly defers.
- A JMH harness. `scripts/alloc-bench.sh` measures allocation and wall-clock off the real golden fixtures and is what the codec work was judged against, but it is a single-threaded counter that does not fork a JVM per benchmark — its own header documents the two ways that misleads.
