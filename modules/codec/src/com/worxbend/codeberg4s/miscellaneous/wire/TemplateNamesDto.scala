package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.miscellaneous.TemplateName
import com.worxbend.codeberg4s.repositories.wire.Elements

/** The two listings whose body is a bare array of '''strings''' — `GET /gitignore/templates` and
  * `GET /label/templates`.
  *
  * There is no DTO to write, because there is no object: the payload is `["AL","Actionscript",…]`, exactly as
  * `golden/misc/gitignore-templates.json` captured it with 297 entries. What there '''is''' is a conversion, because
  * the domain does not hand back bare strings: each name is a [[com.worxbend.codeberg4s.miscellaneous.TemplateName]],
  * so a name the by-name endpoint could not be addressed with is reported instead of handed over.
  *
  * Both endpoints share this because both are the same shape and rule 4 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] applies to conversions as well as to spellings: the argument for
  * validating a template name is written once.
  */
object TemplateNamesDto:

  /** Converts a decoded array of names, reporting the position of whichever element failed.
    *
    * '''One bad name fails the whole listing''', which is the contract every array-shaped body in this library has —
    * see [[com.worxbend.codeberg4s.repositories.wire.Elements]]. It is a very unlikely failure:
    * [[com.worxbend.codeberg4s.miscellaneous.TemplateName]] rejects only a blank name, a control character and a `.` or
    * `..` part, none of which is a file name a Forgejo distribution ships.
    *
    * @param base
    *   the path of the array itself, [[com.worxbend.codeberg4s.JsonPath.Root]] for a response body that is an array
    * @param names
    *   the decoded strings, in wire order
    */
  def toDomainAll(base: JsonPath, names: Vector[String]): Either[DecodeFailure, Vector[TemplateName]] =
    Elements.convert(base, names): (name, path) =>
      TemplateName.from(name).left.map(error => DecodeFailure(path, error.message))
