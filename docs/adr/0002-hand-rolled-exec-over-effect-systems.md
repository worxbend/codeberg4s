# ADR-0002 — A hand-rolled `Exec[F]` instead of cats-effect or ZIO

- Status: accepted
- Date: 2026-08-01

## Context

The public API is `Future`-based (ADR-0005). Internally, the cross-cutting logic
— retry, pagination, error mapping — needs to be written once and tested
**synchronously**, because `Future`-based tests are where flakiness comes from
and because mutation testing over asynchronous code is slow and unreliable.

That argues for abstracting the core over some `F[_]`. The usual way to get that
is `cats-effect` (`Sync`/`MonadError`) or `ZIO`.

## Decision

Define a minimal capability typeclass in `modules/core`:

```scala
trait Exec[F[_]]:
  def pure[A](value: A): F[A]
  def raise[A](error: CodebergError): F[A]
  def map[A, B](fa: F[A])(f: A => B): F[B]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def attempt[A](fa: F[A]): F[Either[CodebergError, A]]
  def suspend[A](thunk: () => F[A]): F[A]
```

with two instances: `Either[CodebergError, *]` for tests, `Future` for the
published client. Roughly forty lines.

## Consequences

Good:

- **Zero effect-system dependency in the published artifact.** For a client
  library this is the deciding factor: a user on ZIO should not inherit
  cats-effect, and a user on neither should inherit nothing.
- Core logic is tested with `F = Either` — synchronous, deterministic, no
  `Await`, no timeouts, and fast enough for Stryker4s to mutate.
- The retry and pagination drivers exist once, not once per rail.

Bad:

- Forty lines of typeclass boilerplate we own and must test. Accepted: it is
  covered by the core suites and is far smaller than the dependency it avoids.
- No `cats` syntax, no `traverse`, no `Resource`. We write the two or three
  combinators we actually need and stop there. If we ever need a real
  `traverse`, that is a signal to revisit this ADR, not to widen `Exec`
  opportunistically.
- `Exec` is not lawful in the cats sense and we do not claim it is. It is an
  internal seam, `private[codeberg4s]` in spirit, and is not part of the
  published API surface.

## Rejected alternatives

| Alternative        | Why rejected                                                          |
| ------------------ | ---------------------------------------------------------------------- |
| cats-effect `Sync` | Large transitive dependency imposed on every consumer of the library.  |
| ZIO                | Same, plus it would push the public API toward `ZIO` and away from ADR-0005. |
| No abstraction — write everything directly against `Future` | Retry and pagination could then only be tested asynchronously; exactly the flakiness we are avoiding. |
