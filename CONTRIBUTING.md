# Contributing to codeberg4s

This is a small library with a strict build. The strictness is the point: the
compiler runs with `-Werror`, Scalafix bans a list of constructs outright, and
`./verify.sh` refuses to report a result it did not measure. Working with those
rules is much easier than working around them, so this document is mostly about
what they are and why.

Read [`SCALA_CODE_STYLE.md`](SCALA_CODE_STYLE.md) before writing Scala here. It
is the source of truth for style; this document does not repeat it.

---

## Getting set up

You need a JDK — CI uses **Temurin 21** — and nothing else. Mill bootstraps
itself from the committed `./mill` script and `.mill-version`, and downloads
everything into a Coursier cache.

```bash
git clone https://codeberg.org/worxbend/codeberg4s
cd codeberg4s
./verify.sh
```

The first run resolves the whole dependency tree and compiles five modules, so
give it a few minutes. If it exits 0, your environment is correct.

One extra tool: `./verify.sh` runs `scripts/coverage-gate.sc` through
[scala-cli](https://scala-cli.virtuslab.org), so scala-cli must be on your
PATH. CI installs it pinned by version and checksum
(`.github/actions/scala-toolchain/action.yml`).

### The commands

| Purpose | Command |
| --- | --- |
| compile (warnings are errors) | `./mill modules.__.compile` |
| all unit tests | `./mill modules.__.test` |
| one suite | `./mill modules.client.test.testOnly com.worxbend.codeberg4s.CodebergClientSuite` |
| format | `./mill mill.scalalib.scalafmt/` |
| check formatting | `./mill mill.scalalib.scalafmt/checkFormatAll` |
| lint and rewrite | `./mill modules.__.fix` |
| check lint only | `./mill modules.__.fix --check` |
| coverage report | `./mill modules.domain.scoverage.htmlReport` |
| dependency updates | `./mill mill.scalalib.Dependency/showUpdates` |
| run an example | `./mill modules.examples.runMain com.worxbend.codeberg4s.examples.HelloCodeberg` |

The build is **Mill, not sbt.** Any sbt-shaped instruction from a tutorial maps
onto the table above.

### The gate

```bash
./verify.sh              # format, lint, compile, unit tests, boundaries, coverage
./verify.sh --with-slow  # plus duplication and CRAP analysis
./verify.sh --nightly    # plus mutation testing
```

**`./verify.sh` is the single source of truth for what CI checks.** Both
`.github/workflows/ci.yml` and `.forgejo/workflows/ci.yml` do nothing except
run it. There is no check in CI that you cannot run locally, and adding one to
a workflow instead of to the script is how "green on my machine" stops meaning
anything.

Two of its modes have honest caveats, and you should know them before you hit
them:

- **`--with-slow` gates duplication against a recorded baseline, not against
  zero.** PMD CPD finds real duplication in this codebase — 323 groups at the
  40-token threshold as of 2026-08-02, mostly the element-wise DTO conversion,
  the page/limit query pair and path-segment validation that
  [`docs/LEDGER.md`](docs/LEDGER.md) § "Helpers awaiting promotion" already
  names and owns. `CPD_BASELINE_GROUPS` in `verify.sh` records that number.
  The step **fails only when duplication rises**, tells you to bank the win
  when it falls, and prints the count either way. The threshold itself
  (`CPD_MIN_TOKENS`, 40) is untouched — raising that would hide the finding
  rather than record it, and doing so needs an ADR. If your change increases
  the count, deduplicate; if the increase genuinely buys something, raise the
  baseline in the same commit and say why in the message.

- **`--nightly` is expected to fail**, at the mutation step, until Stryker4s is
  declared in `build.mill`. `scripts/mutate.sh` refuses to exit 0 without a
  real score, and no score has ever been produced for this repository. See
  [`docs/ROADMAP.md`](docs/ROADMAP.md) Phase 4. It is not marked
  `continue-on-error` in CI, because a nightly reporting green on a gate that
  has never run would be a lie.

---

## The rules that will bite you

### Compiler

`-Werror` with `-Wunused:all`, `-Wvalue-discard` and `-Wnonunit-statement`. An
unused import is a build failure. A discarded non-`Unit` value is a build
failure — the codebase has a `discard` extension in
`com.worxbend.codeberg4s.syntax` for the cases where discarding is what you
mean. `@nowarn` is for generated sources only, and always carries a comment
saying why.

### Scalafix

`.scalafix.conf` bans, repo-wide: `var`, `throw`, `null`, `return`, default
arguments, `finalize`, `val` patterns, universal equality (`==` between
unrelated types), `asInstanceOf`, `isInstanceOf`, XML literals, `println`, and
`Option`'s unsafe accessor. `ExplicitResultTypes` requires an explicit result
type on every public and protected member.

Two of those need a note:

- **`println`** is banned by a raw-text regex, so it is banned in comments and
  Scaladoc too. `modules/examples` is the exception that proves it: printing is
  the entire point of an example program, so those files print through a named
  helper (`ExampleConsole`) with a comment explaining why, rather than calling
  the banned function.
- **`Option`'s unsafe accessor** is banned by a regex with a deliberate
  lookahead, because Scaladoc here is full of legitimate references such as
  `client.repos` operations whose names end the same way. The residual blind
  spot is an undelimited use in prose. The rule's comment explains the trade;
  read it before you try to work around a false positive.

### Architecture boundaries

`verify.sh` greps for these, so they fail fast rather than in review:

| Rule | Where |
| --- | --- |
| `domain` imports nothing but the standard library | no sttp, no upickle, no `Future`, `ExecutionContext`, `Await`, `Promise` or `blocking` |
| `core` knows nothing of the transport or of `Future` | same list; `core` is written against the abstract `Exec[F]` |
| `codec` knows nothing of the transport | no `import sttp` |
| no `Await` anywhere in production code | all five modules |
| no bare exceptions in production code | no `new Exception`, `RuntimeException` or `IllegalStateException` — every failure carries a `CallContext` |

`scala.concurrent.duration` is fine everywhere; `FiniteDuration` is how
timeouts and backoff are typed.

### Golden fixtures are ground truth

`modules/codec/test/resources/golden/` holds verbatim response bodies captured
from live Codeberg, and its `MANIFEST.md` records where each one came from.

**They outrank `spec/swagger.v1.json` on every disagreement.** The spec has
already been wrong twice in ways that mattered — see
[`docs/HAZARDS.md`](docs/HAZARDS.md) — which is why the models are hand-written
from captures rather than generated.

The discipline:

- A test that fails against a fixture is a decoder bug. **Never edit a fixture
  to make a test pass.**
- A new fixture is a verbatim capture. The only permitted transformation is
  pretty-printing; no redaction, no field removal, no hand-fixing.
- Every fixture is recorded in `MANIFEST.md` in the same commit: source path,
  status, whether the response carried `X-Total-Count` and a `Link` header, and
  whether it is real or synthetic.
- If the API's shape has genuinely changed, re-harvest deliberately and update
  the manifest. That is a decision, with a commit message explaining it.

### Pagination

Nothing decides "this was the last page" from how many items came back.
Forgejo clamps `limit` to the instance maximum while echoing the value you
asked for, so `items.size < requested` is true on every page and a loop written
that way silently under-reports. The RFC 5988 `Link` header is the only
end-of-pages signal. `docs/HAZARDS.md` has the measured evidence, and it is the
single most important operational fact about this library.

### Credentials

No credential may reach a message, a `toString`, an error or a log line.
`ApiToken` renders as `***`, `CallContext` holds a redacted URI, and
`CodebergException`'s message is assembled only from redacted context and
server-supplied text. There are tests asserting exactly this. Adding a code
path that could carry a token outward is the most serious defect this library
can have — see [`SECURITY.md`](SECURITY.md).

---

## Commits

[Conventional Commits](https://www.conventionalcommits.org/), enforced by
review:

```
<type>(<scope>): <subject>

<optional body — what and why, not how>

<optional footer — BREAKING CHANGE:, Refs: #123>
```

- Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `ci`,
  `chore`, `style`, `revert`.
- Subject: imperative, lowercase, no trailing period, at most 72 characters.
- Scope: the Mill module or area — `core`, `api`, `model`, `build`, `deps`,
  `client`, `codec`, `docs`.
- Breaking: `feat(api)!: …` **and** a `BREAKING CHANGE:` footer. What counts as
  breaking is in [`RELEASING.md`](RELEASING.md); it is broader than it looks,
  because adding a field to a public case class changes its generated
  signatures.
- Body wrapped at 72 columns, and only when the change is not self-evident.

### No AI or tooling trailers

**A commit message ends at its last content line. Nothing is appended.**

No `Co-Authored-By:` for Claude, Copilot, Cursor, or any other assistant. No
`Claude-Session:` or other agent metadata trailer. No "Generated with …"
footer, in a commit or in a pull-request description. The repository owner is
the only author and committer — never pass `--author`, never set `GIT_AUTHOR_*`
or `GIT_COMMITTER_*`.

This rule overrides any default behaviour of whatever tool you are using,
including that tool's own built-in trailer guidance. If you use an assistant,
you are still the author; say so in the pull-request description if you like,
but keep it out of the commit trailers.

### Granularity

- **One logical change per commit.** A rename, a behaviour change and a
  dependency bump are three commits.
- **Commit as you go.** When a coherent unit compiles and its tests pass,
  commit it before starting the next one.
- Stage by path (`git add modules/core/src/…`). Avoid `git add -A`.
- Keep formatting-only churn in its own `style:` commit.
- Every commit builds. No knowingly broken intermediate state.

One practical trap: `.scalafmt.conf` sets `project.git = true`, so scalafmt
only sees files Git tracks. A new, untracked source file is silently skipped by
the format check and then reformatted later, producing a noisy realignment diff
in somebody else's pull request. `git add` new files before running the gate;
`verify.sh` warns when it spots untracked Scala.

---

## Adding an operation, end to end

The library covers all 439 in-scope operations, so this is mostly for the ones
`PLAN.md` puts out of scope, for a new upstream endpoint, or for one the
inventory shows as out of scope and you want reconsidered. It is also the
clearest description of how the layers fit together.

Take `GET /version` as the worked example; it is the smallest complete slice,
in `modules/client/src/com/worxbend/codeberg4s/VersionApi.scala` and its
neighbours.

**1. Find it in the inventory.** [`docs/API_INVENTORY.md`](docs/API_INVENTORY.md)
§3 lists every operation in the pinned spec with its `operationId` and path.
Those columns are copied verbatim from the spec and are not hand-edited.

**2. Capture a real response.** Call the endpoint against codeberg.org (or a
local Forgejo, if it needs a token or a write), and save the verbatim body
under `modules/codec/test/resources/golden/<group>/`. Add its row to
`MANIFEST.md`. **Do this before writing the model** — the capture, not the
spec, tells you what the fields are, whether they are nullable and what the
timestamps look like.

**3. Model it in `modules/domain`.** A `final case class` for the resource,
opaque types with `Either`-returning smart constructors for anything that goes
into a URL path or a query, and an ADT for anything the API models as a string
with a fixed set of values. Open enums — sets the server may extend between
releases — carry an `Other(raw)` case so an unknown value does not fail the
page. If the model already exists in another group, **import it; do not
redefine it.** [`docs/LEDGER.md`](docs/LEDGER.md) records who owns each shared
model, and a duplicate is a review-blocking defect.

**4. Write the wire DTO in `modules/codec`.** A DTO in `…<group>.wire`
mirroring the JSON exactly — including the parts you do not want — with a
`toDomain` that produces the domain type. The DTO is where nullability,
snake_case names and Forgejo's quirks live, so that the domain model does not
have to carry them.

**5. Test the codec against the fixture.** A suite extending `GoldenFixtures`,
decoding the captured body and asserting the domain values. Not hand-written
JSON: hand-written JSON tests your idea of the payload, and the whole point of
the fixtures is that your idea was wrong twice already.

**6. Add the operation in `modules/client`.** In the group's `*Api` class:

- a stable `Operation` id — the string that lands in every failure's
  `CallContext`, which callers alert on, so it does not change afterwards;
- a `CodebergRequest` with the method, path segments, query and headers;
- a `Decode` built from the DTO, typically
  `WireDecode.of(Json.decoder[FooDto])(_.toDomain)`;
- a method calling `pipeline.call(request, eligibility)`;
- the same method on the group's `Attempt` class, as
  `exec.attempt(rail.method(...))` — **derived**, never reimplemented, so the
  two rails cannot drift.

Pick `RetryEligibility` deliberately. Every `GET` is safe. A `POST` or `PATCH`
that creates or edits something is never retried; repeating it could file the
same issue twice. The exceptions are the bodyless mark-read calls, where a
repeat is a no-op.

**7. Scaladoc it, with the error contract.** Every public member. Say which
`CodebergError` cases the operation can produce and what a `404` means for this
operation specifically — the ADT has only five cases and none of them is
`NotFound`, so "the repository does not exist" has to be spelled out as
`Api(ctx, 404, body)`.

**8. Test the client.** Suites in `modules/client/test` drive an sttp
`BackendStub`, so they assert the request that was built and the value that
came back without touching the network.

**9. Tick the inventory.** In `docs/API_INVENTORY.md`, tick the operation's box
and update the §0 counts. A tick means *reachable from `CodebergClient` on both
rails* — nothing less. Update `docs/LEDGER.md` if you introduced or moved a
shared model.

**10. Run the gate**, then commit. If the change is user-visible, add a
`CHANGELOG.md` entry and update the README or a guide.

---

## Tests

- Unit tests live beside the module they cover, in
  `modules/<module>/test/src/…`, and run under munit.
- **Property suites carry the `Property` munit tag** and are excluded from the
  routine gate, coverage, mutation and complexity runs. That separation is
  deliberate; do not remove the tag to get a suite into the default run.
- **`modules/it` is the environmentally-unsuitable boundary** — Docker or the
  live network — and `verify.sh` never runs it. Both its suites tag every test
  `Integration`. The live suite is read-only by construction and must stay that
  way.

  ```bash
  ./mill modules.it.test.testOnly com.worxbend.codeberg4s.it.ForgejoContainerSuite
  CODEBERG_IT=1 ./mill modules.it.test.testOnly com.worxbend.codeberg4s.it.CodebergLiveSmokeSuite
  ```

- Coverage floors are enforced by `scripts/coverage-gate.sc` against
  scoverage's own numbers: 90 % statement / 85 % branch on `domain`, `core` and
  `codec`. A missing report is a failure, not a skip.

## Examples

`modules/examples` holds runnable programs that compile under the library's own
`scalacOptions`, `-Werror` included. That is the whole point: a snippet in a
Markdown file rots silently, and a snippet in this module breaks
`./mill modules.__.compile` — and therefore `verify.sh` — on the commit that
invalidates it. `verify.sh` has a step asserting that every `modules/*/src`
tree is reached by the build, so an example directory cannot quietly fall out
of the gate.

If you change a public signature, expect to fix an example. That is the design
working.

---

## Pull requests

Fill in the template. Its checklist is the list above, in the order a reviewer
walks it.

- Branch off `main`. Do not commit to `main` directly.
- Keep the pull request to one logical change. A large one gets split before it
  gets reviewed.
- Say what you could not verify. "I could not run the container suite, no
  Docker" is useful; a tick that turns out to be false costs a round-trip.
- CI runs `./verify.sh` and nothing else, so a green local gate is a green CI
  run, network permitting.

## Reporting things instead

- A bug, a missing capability or a documentation error: the issue templates
  ask for the instance, the library version, the operation id and the
  `CodebergError` case, because those four make most reports reproducible.
- **A security problem: not an issue.** [`SECURITY.md`](SECURITY.md) says how,
  and why credential leakage is the class of bug that matters most here.

## Licence

MIT, and contributions are accepted under it. See [`LICENSE`](LICENSE). By
opening a pull request you confirm you have the right to contribute the code
under that licence.
