# Readiness review — what stands between this repo and a third party using it

The library itself is done: 439 of 439 in-scope operations, `./verify.sh` green,
100 % domain coverage, 3524 unit tests and 1338 property tests. What follows is
everything *else* a consumer needs, assessed honestly against the state of the
repo rather than against intentions.

## Blocking — a stranger cannot use the library without these

| Gap | Why it blocks | Where it lands |
| --- | --- | --- |
| **Nothing is published.** `build.mill` has full `PublishModule` config at `0.1.0-SNAPSHOT`, but no artifact exists on Maven Central, so the README's coordinates resolve to nothing. | A dependency line that does not resolve is the first and last thing a new user tries. | `RELEASING.md`, release workflow |
| **No release process.** No documented steps, no signing key handling, no tag convention, no way to reproduce a release. | Publishing by hand from a laptop is how a supply chain gets compromised and how versions get skipped. | `RELEASING.md`, `.github/workflows/release.yml` |
| **No public API documentation.** Scaladoc exists on every public member — it is thorough — but it is never rendered or hosted. | A published library whose docs live only in source is a library people read on the train, not one they adopt. | site pipeline, Pages workflow |
| **No worked examples that compile.** README snippets were verified once by hand; nothing in the build stops them rotting. | Examples that no longer compile are worse than no examples: they teach the wrong API. | `modules/examples`, mdoc |

## Important — these decide whether adoption survives contact

| Gap | Why it matters |
| --- | --- |
| **No CI that a contributor can see.** `.forgejo/workflows/ci.yml` exists but has never run — the repo has no remote. There is no GitHub Actions equivalent, and the nightly and drift jobs were unreachable until recently. |
| **No binary-compatibility policy.** Five artifacts, no MIMA, no stated versioning scheme. Consumers pin versions; they need to know what a minor bump may break. |
| **No contribution path.** No `CONTRIBUTING.md`, no issue or pull-request templates, no code of conduct, no security policy. A drive-by bug report has nowhere to go, and a vulnerability has no private channel. |
| **No task-oriented documentation.** `README.md` is a tour and the ADRs are rationale. Neither answers "how do I paginate every issue in a repository without running out of memory" — which is what a working developer actually asks. |
| **`--with-slow` is red.** PMD CPD reports 323 duplication groups, mostly the helper duplication `docs/LEDGER.md` already tracks. Honest, but a contributor running the documented gate hits a failure that is not theirs. |

## Nice to have — not blocking 0.1.0

- Dependency update automation for the Scala and Mill side (Renovate; Codeberg supports it, Dependabot does not, and Dependabot has no Mill support). The GitHub Actions pins are already covered by `.github/dependabot.yml`.
- A spec-drift issue opener rather than a warning in a log.
- Stryker4s producing an actual mutation score (the engine is proven on Scala 3.8.4; a real run has never completed).
- Scala.js / Native cross-builds, which `PLAN.md` §0 explicitly defers.

## What is being built in response

1. **`modules/examples`** — runnable programs compiled by the build under `-Werror`, so an example cannot rot silently.
2. **mdoc** over every documentation snippet, so the guides compile against the real API on every site build.
3. **A microsite** (Laika) with a landing page, task-oriented guides and the full Scaladoc, deployable to GitHub Pages and Codeberg Pages.
4. **Guides written for a developer who is new to this library** — and, in places, new to Scala — covering the first request, authentication, errors, pagination, retries, testing, and a recipe cookbook.
5. **CI and release workflows** for GitHub Actions alongside the existing Forgejo ones, including a tag-driven publish and a Pages deploy.
6. **Community health files** — contributing, code of conduct, security policy, release process, issue and pull-request templates.
