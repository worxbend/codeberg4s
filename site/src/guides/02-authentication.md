# Authentication

For anyone about to make their first authenticated call: where a token comes
from, how to give it to the client, what happens if it is malformed, and what
guarantees this library makes about never printing it.

## Three ways to authenticate, and no fourth

`com.worxbend.codeberg4s.auth.Auth` is a closed set of three:

| Case | Sent as | When |
| --- | --- | --- |
| `Auth.Anonymous` | nothing | public reads, subject to the instance's anonymous rate limit |
| `Auth.Token(token)` | `Authorization: token <value>` | the normal case |
| `Auth.Basic(username, password)` | `Authorization: Basic <base64>` | older self-hosted instances that still require it |

Forgejo's OpenAPI document advertises two more schemes — `Sudo`, in header and
query form, and `X-FORGEJO-OTP` for two-factor login. This library supports
neither. `Sudo` is an administrator impersonation feature that is out of scope
for v1, and interactive two-factor authentication does not belong inside a
client library.

Changing how you authenticate is a change of one value and nothing else. Every
call site stays as it was.

## Getting a token on Codeberg

On codeberg.org, sign in and go to **Settings → Applications → Manage Access
Tokens** (`https://codeberg.org/user/settings/applications`). Give the token a
name, pick its scopes, and create it. **The value is shown once.** Copy it then;
Codeberg cannot show it to you again, and neither can this library — see
[Reading tokens back](#reading-tokens-back) below.

The same page exists on any self-hosted Forgejo, under the same path.

### Scopes

Forgejo scopes are `read:<category>` or `write:<category>`, plus a single `all`
that grants everything the account can do. `write:` implies the matching
`read:`.

This library models the vocabulary as
`com.worxbend.codeberg4s.users.social.TokenScope`, over eight categories:
`activitypub`, `issue`, `misc`, `notification`, `organization`, `package`,
`repository` and `user`.

Two honest caveats about that list. First, it is derived from an `example` array
in the OpenAPI document, not from an `enum` — so it is evidence of eight
categories and evidence of nothing else, which is why `TokenScope.Other(raw)`
exists to carry a scope string this release does not model. Second, `all` is a
scope string in its own right and is **not** the union of every `read:` and
`write:` the type can spell.

Grant the least you need. A read-only integration wants `read:repository` and
`read:issue`; nothing about this library requires more.

### The reason scopes are a type

Forgejo does not reject a scope it does not recognise. It creates the token with
the scopes it understood and silently ignores the rest. A token created with
`read:repositories` — plural, and wrong — is created successfully, can do
nothing, and the first symptom is an unrelated `403` days later. Spelling the
scope as a value moves that failure to the line that made the typo.

## Giving the token to the client

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth

val configured: Either[ValidationError, CodebergConfig] =
  ApiToken
    .from(sys.env.getOrElse("CODEBERG_TOKEN", ""))
    .map(token => CodebergConfig(Auth.Token(token)))
```

Read the token from the environment, from a secrets manager, from a file — from
anywhere except a string literal in the source you are about to commit.

## Why `ApiToken.from` returns an `Either`

It could have taken a `String` and trusted you. It does not, because a token is
about to become an HTTP header value, and two kinds of string cannot be one:

- **A blank one.** An empty `CODEBERG_TOKEN` environment variable is by far the
  most common deployment mistake. Without the check, the client sends
  `Authorization: token ` and the instance answers `401` — a failure that looks
  like "your token is wrong" rather than "your token is missing".
- **One containing a control character.** A `\r` or `\n` inside a header value
  is a request-splitting vector. Rejecting it is a security boundary, not
  tidiness.

`ApiToken.from` also trims surrounding whitespace, because tokens are usually
read from a file or an environment variable that carries a trailing newline.

The returned `ValidationError` names the field — `"apiToken"` — and a short
reason, and it never echoes the value it rejected.

### Handling the `Either`

The shape that reads best depends on what you want to happen when the token is
missing. In an application that cannot run without one, fail at startup:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth

import scala.concurrent.ExecutionContext

def clientFromEnvironment(raw: String)(using ExecutionContext): Either[String, CodebergClient] =
  ApiToken
    .from(raw)
    .left
    .map((problem: ValidationError) => s"CODEBERG_TOKEN is unusable: ${problem.message}")
    .map(token => CodebergClient(CodebergConfig(Auth.Token(token))))
```

In one that should degrade to anonymous reads when no token is configured, fall
back instead:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth

def authFrom(raw: Option[String]): Auth =
  raw.flatMap(value => ApiToken.from(value).toOption) match
    case Some(token) => Auth.Token(token)
    case None        => Auth.Anonymous
```

That second version deliberately throws the `ValidationError` away, because in
this design a missing token and a malformed token both mean "run anonymously".
If they should mean different things to you, keep the `Either`.

## Basic authentication

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.auth.Password

val basic: Either[ValidationError, CodebergConfig] =
  Password
    .from(sys.env.getOrElse("FORGEJO_PASSWORD", ""))
    .map(secret => CodebergConfig(Auth.Basic("ci-bot", secret)))
```

`Password.from` applies the same control-character check as `ApiToken.from` but
**does not trim**: leading and trailing whitespace can be significant in a
password. It rejects an empty value.

Prefer a token wherever the instance allows one. A token can be scoped, listed
and revoked on its own; a password cannot.

## Anonymous is a real option, and not always the one you think

Plenty of Codeberg endpoints answer anonymously, and the anonymous rate limit on
codeberg.org has been measured at 2000 requests per 10-minute window.

Do not, however, infer from an endpoint's shape that it is public. The pinned
OpenAPI document carries **no per-operation security information at all** — its
five security definitions are declared globally and not one of the 506
operations overrides them — so the document cannot distinguish
`GET /repos/{owner}/{repo}`, which works anonymously, from `GET /user`, which
does not. Measured against codeberg.org, an anonymous
`GET /users/{username}/followers` answers `401`. The evidence is in
[`docs/HAZARDS.md`](../project/HAZARDS.md) §2.

The practical rule: if a call answers `401` and you expected it not to, the
endpoint needs credentials. Configure them rather than assuming a bug.

## The token never appears in logs, errors, or `toString`

This is a guarantee with tests behind it, not an intention.

- `ApiToken.toString` is the constant `***`. So is string interpolation of one,
  and so is the generated `toString` of any case class or enum case that holds
  one — including `Auth.Token` and `CodebergConfig`. `CodebergConfig.toString`
  is therefore safe to log in full.
- `Password` behaves identically.
- No `CodebergError` can contain a credential. The URI inside a `CallContext` is
  redacted by the transport *before* the context is built, and
  `CodebergException`'s message is `CodebergError.describe`, which is assembled
  only from that redacted context and from server-supplied text.
- The `Telemetry` callbacks receive the same already-redacted values, so an
  implementation cannot leak a credential by logging what it is handed. See
  [Observability](./06-observability.md).

`ApiToken` is a `final class` rather than an opaque type for exactly this
reason: an `opaque type ApiToken = String` has `Any` as its visible upper bound,
so outside its own file `s"$token"` would dispatch to `String`'s `toString` and
print the secret.

### The one place the material is revealed

`ApiToken.reveal: String` returns the raw value. Its single legitimate caller is
the transport adapter building the `Authorization` header — one call site, in
`SttpHttpPort`, alongside the equivalent for `Password`.

The method is called `reveal` and not `value` on purpose. It is a name that
stands out in a code review, and any appearance of it outside a header-building
line deserves a question.

If you find yourself wanting it — to hand the token to another HTTP client, say
— that is a legitimate use. Reveal it as late as possible, at the call site that
needs the bytes, and never into an intermediate `String` variable that might
later be logged.

## Reading tokens back

`client.users.tokens.list(username, page)` lists an account's tokens as
`AccessToken` values: id, name, scopes, repository confinement, creation time,
and the last eight characters. **There is no field that could hold the
material.** Forgejo does not send it, and the model has nowhere to put it.

The single operation in this library whose success carries a working credential
is `client.users.tokens.create(username, command)`, which returns a
`CreatedAccessToken(token: ApiToken, details: AccessToken)`. That value is
returned once. If you do not store it, revoke the token and mint another.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.users.Username
import com.worxbend.codeberg4s.users.social.CreateAccessToken
import com.worxbend.codeberg4s.users.social.CreatedAccessToken
import com.worxbend.codeberg4s.users.social.TokenCategory
import com.worxbend.codeberg4s.users.social.TokenScope

import scala.concurrent.Future

def mintReadOnlyToken(client: CodebergClient, login: String): Either[ValidationError, Future[CreatedAccessToken]] =
  for
    who     <- Username.from(login)
    command <- CreateAccessToken.named("ci-reader")
  yield client.users.tokens.create(
    who,
    command.granting(TokenScope.Read(TokenCategory.Repository), TokenScope.Read(TokenCategory.Issue)),
  )
```

Note what the result type says: the material is an `ApiToken`, so printing the
`CreatedAccessToken` prints `***`. To store it you must call `reveal`, and that
call is visible in review.

## Next

- [Errors](./03-errors.md) — including what a `401` looks like when it arrives.
- [Troubleshooting](./10-troubleshooting.md) — including "401 with a token
  set".
