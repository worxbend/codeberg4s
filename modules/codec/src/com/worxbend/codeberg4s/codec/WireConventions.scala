package com.worxbend.codeberg4s.codec

/** The rules every wire DTO in this module follows, written down once so that the next model is mechanical rather than
  * a judgement call.
  *
  * ==1. One DTO per API model, in `<concept>.wire`==
  *
  * A Forgejo model called `Repository` becomes `com.worxbend.codeberg4s.repositories.wire.RepositoryDto`, and its
  * `Reader` lives in `RepositoryDto`'s companion. The concept package is the one `docs/LEDGER.md` assigns to the
  * '''domain''' model, so the DTO and the type it converts into stay side by side. A model that appears in several
  * endpoint groups is written once, by the wave that needs it first; forking or "temporarily" copying a DTO is a
  * review-blocking defect.
  *
  * ==2. Every field is `Option`, or a collection that defaults to empty==
  *
  * Not a style preference — a measurement. `docs/HAZARDS.md` §1: of the 246 definitions in the pinned spec, '''zero'''
  * response models declare a `required` list and the word `nullable` appears '''zero''' times, while a live `Issue`
  * returns JSON `null` for `assignee`, `assignees`, `closed_at`, `due_date` and `milestone`. The spec therefore carries
  * no optionality information at all, and the golden fixtures are the only evidence.
  *
  * Absent, `null`, and wrong-kind are one case, handled by [[JsonFields]]. An array that arrives as `null` becomes an
  * empty `Vector`, never a crash.
  *
  * ==3. Readers are hand-written over [[JsonFields]], not derived==
  *
  * ADR-0003 chose upickle and anticipated explicit `ReadWriter`s. Rule 2 forces them, for a reason worth recording
  * because it is not obvious and was measured against upickle 4.4.3 rather than assumed:
  *
  *   - JSON `null` into an `Option` field '''does''' decode as `None`. That part is free.
  *   - JSON `null` into a `Seq`/`Vector` field aborts. Not free.
  *   - An '''absent''' key aborts with `missing keys in dictionary: …` for any field that does not carry a Scala
  *     default value. Also not free, and it is the common case: `Repository` omits `external_tracker`, `external_wiki`,
  *     `internal_tracker` and `wiki_branch` entirely when they are unconfigured.
  *
  * upickle fills a missing key only from a `case class` parameter default, and `.scalafix.conf` bans default arguments
  * (`DisableSyntax.noDefaultArgs`), so `@upickle.implicits.key` plus derivation cannot express rule 2 in this codebase.
  * [[JsonFields.reader]] does, and it delegates the structural question — is the payload an object at all? — back to
  * upickle so that this module raises no exception of its own.
  *
  * ==4. Wire names are snake_case, spelled once==
  *
  * The snake_case name appears exactly once per field, as the string literal passed to the [[JsonFields]] accessor in
  * the DTO's reader. This is the place `@upickle.implicits.key` would otherwise occupy; rule 3 explains why it cannot.
  *
  * ==5. `toDomain` returns `Either`, and never throws==
  *
  * Every DTO offers `def toDomain: Either[DecodeFailure, <Model>]`. It is where the optionality of rule 2 collapses: a
  * field the domain genuinely needs but the payload did not supply becomes a
  * [[com.worxbend.codeberg4s.core.DecodeFailure]] naming the JSON path of the offending field, which the request
  * pipeline lifts into [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] with a bounded body snippet attached.
  *
  * `toDomain` is also where Forgejo's spellings are normalised out of existence — the `""`-for-absent convention, the
  * `"0001-01-01T00:00:00Z"` zero-time sentinel (see [[Timestamps]]), lowercase enum-ish strings. None of it reaches the
  * domain.
  *
  * ==6. Nested models nest their DTOs==
  *
  * A DTO holds `Option[UserDto]`, never a re-flattened `ownerLogin: Option[String]`. Conversion recurses through
  * `toDomain`, so the failure path of an embedded model is reported once, by that model.
  */
object WireConventions
