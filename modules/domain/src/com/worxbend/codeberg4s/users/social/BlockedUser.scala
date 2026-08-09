package com.worxbend.codeberg4s.users.social

import java.time.Instant

/** One entry of `GET /user/list_blocked` — a block the account has in place.
  *
  * '''Derived from `spec/swagger.v1.json`'s `BlockedUser`, not from a captured response''' — the path needs a token and
  * the golden harvest was anonymous.
  *
  * ==It does not say who is blocked==
  *
  * The wire model has exactly two properties, `block_id` and `created_at`. There is no login, no user object and no
  * link. That is Forgejo's model as the pinned spec declares it, and this type reproduces it rather than inventing a
  * field that would always be absent: an `Option[Username]` here would tell a caller the information might arrive,
  * which as far as the spec is concerned it never does.
  *
  * The practical consequence is that this listing answers "how many blocks do I have, and since when" and not "whom
  * have I blocked". A caller who needs the second has to have recorded it when they called `block`. If a later Forgejo
  * release adds the account to the payload, this model gains a field; until a capture proves it does, guessing at one
  * would be worse than the honest gap.
  *
  * @param blockId
  *   the identifier of the block relationship, the only field that distinguishes two entries
  * @param createdAt
  *   when the block was put in place
  */
final case class BlockedUser private[codeberg4s] (blockId: BlockId, createdAt: Option[Instant])
