package com.worxbend.codeberg4s.repositories.hooks

import java.time.Instant

/** One repository webhook, as `GET /repos/{owner}/{repo}/hooks/{id}` reports it.
  *
  * '''Derived from `spec/swagger.v1.json`'s `Hook` definition, not from a captured response.''' The golden harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every hook route requires a token, so no fixture
  * exists for this model. The field set and the nullability treatment are the spec read literally under the rule
  * `docs/HAZARDS.md` §1 forces on the whole API; the payloads asserted in the suites were written by hand to match that
  * definition and are not evidence that Forgejo sends exactly this.
  *
  * ==Two fields of the spec are deliberately not here==
  *
  *   - `authorization_header`. It is a bearer credential that is replayed on every delivery, and this library will not
  *     hand one back through a model whose generated `toString` would print it. It can be '''set''' — see
  *     [[CreateHook.authorization]] — and never read. [[HookSecret]] explains the discipline.
  *   - `metadata`. `spec/swagger.v1.json` declares the property with '''no type at all''' — not an object, not a
  *     string, nothing — so there is no shape to model and no way to know what a value would mean. `config` carries the
  *     same information for every hook type this library has seen, and is what [[configuration]] reads, even though the
  *     spec marks it `Deprecated: use Metadata instead`. Modelling an untyped field by guessing would be exactly the
  *     invention `docs/HAZARDS.md` warns against.
  *
  * @param id
  *   the hook's instance-wide identifier, which is what every other hook route takes
  * @param hookType
  *   the delivery format; see [[HookType]]
  * @param configuration
  *   the open string map Forgejo stores for this hook, credentials already stripped — see [[HookConfig]]
  * @param events
  *   what the hook subscribes to, in the order the instance listed them. Empty when the payload carried no `events`
  *   key, `null`, or an empty array — the three are indistinguishable on the wire and mean the same thing here
  * @param url
  *   the `url` key of the `Hook` model. The spec describes it only as a string and does not say whether it is the
  *   delivery destination or the hook's own address on the instance, so it is reported verbatim rather than
  *   interpreted; [[HookConfig.url]] is the destination a caller configured
  * @param branchFilter
  *   the glob deciding which branches a push event fires for, absent when the hook fires for all of them
  * @param isActive
  *   whether Forgejo will deliver to this hook. Absent means the instance did not say, which is not the same as `false`
  * @param createdAt
  *   when the hook was created, absent when the instance sent no timestamp or the zero-time sentinel
  * @param updatedAt
  *   when the hook was last changed, on the same terms as [[createdAt]]
  */
final case class Webhook(
    id: HookId,
    hookType: Option[HookType],
    configuration: HookConfig,
    events: Vector[HookEvent],
    url: Option[String],
    branchFilter: Option[String],
    isActive: Option[Boolean],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
):

  /** Whether the hook subscribes to `event`. */
  def subscribesTo(event: HookEvent): Boolean =
    events.contains(event)
