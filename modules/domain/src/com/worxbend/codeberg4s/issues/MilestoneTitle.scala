package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** A milestone title as a caller '''supplies''' it when filtering an issue listing.
  *
  * `GET /repos/{owner}/{repo}/issues` takes `milestones` as one comma-separated string, exactly like `labels`, so a
  * title containing a comma silently becomes two filters. See [[FilterToken]] for why that is validated at
  * construction.
  *
  * As with [[LabelName]], a title '''read back''' from the instance is a plain `String` on [[Milestone.title]]: Forgejo
  * allows commas in a milestone title, and a page of milestones must not fail to decode because one of them cannot be
  * used as a filter.
  */
opaque type MilestoneTitle = String

object MilestoneTitle:

  /** Parses a milestone title.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `,`, and a value containing a
    * control character.
    *
    * @return
    *   the trimmed title, or a [[ValidationError]] on the `"milestoneTitle"` field
    */
  def from(value: String): Either[ValidationError, MilestoneTitle] =
    FilterToken.from("milestoneTitle", value)

  extension (title: MilestoneTitle)

    /** The title as a string, ready to be joined into a `milestones` parameter. */
    def value: String = title
