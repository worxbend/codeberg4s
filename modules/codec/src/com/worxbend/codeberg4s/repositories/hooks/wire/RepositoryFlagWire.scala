package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.hooks.RepositoryFlag
import com.worxbend.codeberg4s.repositories.wire.Elements

/** The two directions of the repository flag routes, which have no model of their own.
  *
  * `GET /repos/{owner}/{repo}/flags` answers the spec's `StringSlice` response — a bare array of strings, with no
  * object anywhere — and `PUT` takes `ReplaceFlagsOption`, whose single property is another array of strings. There is
  * therefore no DTO to write: there is nothing to be optional about. What there is instead is a conversion that can
  * fail, because a flag becomes a path segment on three of the six flag routes and
  * [[com.worxbend.codeberg4s.repositories.hooks.RepositoryFlag]] refuses one that could forge a path.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture'''; see [[WebhookDto]].
  */
private[codeberg4s] object RepositoryFlagWire:

  /** The wire key the flags are sent under in `ReplaceFlagsOption`. */
  val FlagsKey: String = "flags"

  /** Converts a decoded array of flag names, reporting the position of whichever element failed.
    *
    * A blank flag, or one carrying a slash or a control character, fails the whole listing at `$[n]` rather than being
    * dropped: a flag this library cannot address is one the caller could not then delete, and silently shortening the
    * list would hide that.
    */
  def toDomainAll(base: JsonPath, values: Vector[String]): Either[DecodeFailure, Vector[RepositoryFlag]] =
    Elements.convert(base, values): (value, path) =>
      RepositoryFlag.from(value).left.map(error => DecodeFailure(path, error.message))

  /** Renders `flags` as the JSON body of `PUT /repos/{owner}/{repo}/flags`.
    *
    * The array is emitted in the order the caller built it and is never sorted: this call replaces the whole flag set,
    * and reordering a caller's list would make a rendered body differ from what they wrote for no reason. An empty
    * vector renders as `{"flags":[]}`, which asks Forgejo to clear every flag — the same end state as
    * [[com.worxbend.codeberg4s.repositories.hooks.RepositoryFlagApi.deleteAll]] reaches, spelled as a replacement.
    */
  def renderReplace(flags: Vector[RepositoryFlag]): String =
    ujson.write(ujson.Obj(FlagsKey -> ujson.Arr.from(flags.map(_.value))))
