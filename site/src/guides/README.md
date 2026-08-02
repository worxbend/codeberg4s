# Guides

Task-oriented documentation: one problem per page, in the order a developer
usually meets them. Every Scala snippet here is compiled against the real
library when the site is built, so a guide that documents a method the library
does not have fails the build.

If you have never used this library, read the first one and then jump to
whichever of the others you need.

## The first request

1. **[Getting started](./01-getting-started.md)** — add the dependency, build a
   client, make one anonymous call, close the client. Explains what an
   `ExecutionContext` is, for readers coming from another language.
2. **[Authentication](./02-authentication.md)** — where a token comes from,
   scopes, basic auth, anonymous, and the guarantee that a credential never
   reaches a log, an error or a `toString`.

## Getting the answers right

3. **[Errors](./03-errors.md)** — the five failures, the two error rails, how a
   `404` actually arrives, and a complete worked recovery. Read this before you
   write a `recover` block: there is no `NotFound` case and no `RateLimited`
   case.
4. **[Pagination](./04-pagination.md)** — **the most important page here.** The
   obvious loop over a listing is wrong against Forgejo, and it fails by
   silently under-reporting rather than by raising anything.
5. **[Retries and rate limits](./05-retries-and-rate-limits.md)** — what is
   repeated and what is never repeated, `Retry-After`, and why Codeberg's
   rate-limit headers are not the ones you are looking for.

## Living with it in a real codebase

6. **[Observability](./06-observability.md)** — the `Telemetry` port, a complete
   implementation, and why the library has no logging dependency.
7. **[Testing your code](./07-testing-your-code.md)** — testing what *you* built
   on the client, without a network: `usingBackend`, sttp's `BackendStub`,
   asserting on request shape, and faking every kind of failure.
8. **[Writing data](./08-writing-data.md)** — command types, which operations
   are retried and which are never retried, and optimistic concurrency on file
   writes.

## When something is different, or wrong

9. **[Self-hosted instances](./09-self-hosted.md)** — pointing the client at
   your own Forgejo, and discovering that instance's limits rather than assuming
   Codeberg's.
10. **[Troubleshooting](./10-troubleshooting.md)** — a symptom-to-cause table,
    from "401 with a token set" to "the `Future` never completes".

## See also

- **[Reference](../reference/README.md)** — the API groups, a glossary, and the
  questions the design provokes.
- **[Project documents](../project/README.md)** — the roadmap, and
  [Hazards](../project/HAZARDS.md), which records every measured divergence
  between the specification and what Codeberg actually returns. Several of these
  guides are downstream of that document.
