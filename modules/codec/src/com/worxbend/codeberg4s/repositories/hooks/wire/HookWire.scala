package com.worxbend.codeberg4s.repositories.hooks.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.repositories.hooks.HookEvent

/** The readings this group's DTOs share, written once because getting any of them wrong twice would be silent.
  *
  * None of these can fail. Every one turns a value the domain cannot use into absence or into a verbatim rendering,
  * which is the contract `docs/HAZARDS.md` §1 forces on a spec that declares nothing required: a field that arrives in
  * a shape this library does not recognise must cost the caller that field and nothing else.
  */
private[hooks] object HookWire:

  /** The elements of a string array read as hook events, in wire order.
    *
    * Nothing is dropped: [[com.worxbend.codeberg4s.repositories.hooks.HookEvent.parse]] is total, so an event name this
    * library does not recognise arrives as `HookEvent.Other` rather than disappearing from the subscription list.
    */
  def events(values: Vector[String]): Vector[HookEvent] =
    values.map(HookEvent.parse)

  /** The object at `name` read as a flat map of strings.
    *
    * Entries whose value is not a JSON string are dropped rather than rendered, because the only two objects read this
    * way — a hook's `config` on the way in and on the way out — are declared `additionalProperties: {type: string}` in
    * `spec/swagger.v1.json`. A non-string there is an instance doing something the contract does not describe, and
    * guessing a rendering for it would put a value in the map that no caller could act on.
    *
    * Answers an empty map when the key is absent, `null`, or not an object; see [[JsonFields]].
    */
  def stringMap(fields: JsonFields, name: String): Map[String, String] =
    fields
      .nested(name)
      .fold(Map.empty)(nested => nested.toMap.flatMap((key, value) => value.strOpt.map(text => key -> text)))

  /** The object at `name` read as a map of text, keeping values of any JSON kind.
    *
    * The rule is the one [[com.worxbend.codeberg4s.repositories.hooks.IssueFormField]] documents: a JSON string is
    * unwrapped to its text, and anything else keeps its compact JSON rendering. This exists for the two
    * `additionalProperties: {}` objects on `IssueFormField`, whose values the spec constrains in no way at all.
    *
    * Answers an empty map when the key is absent, `null`, or not an object.
    */
  def textMap(fields: JsonFields, name: String): Map[String, String] =
    fields
      .nested(name)
      .fold(Map.empty): nested =>
        nested.toMap.map((key, value) => key -> value.strOpt.getOrElse(Json.render(value)))
