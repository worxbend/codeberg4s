package com.worxbend.codeberg4s

/** A location inside a JSON document, used to say *where* decoding failed.
  *
  * The path is built from already-rendered segments, so rendering is a concatenation and the round trip cannot lose the
  * difference between the field `"0"` and the array index `0`.
  *
  * {{{
  * JsonPath.of("owner", "login").render        // "$.owner.login"
  * JsonPath.Root.field("items").index(0).render // "$.items[0]"
  * }}}
  *
  * Values compare structurally, so a decoder test can assert on an expected path.
  */
opaque type JsonPath = List[String]

object JsonPath:

  /** The whole document. Renders as `$`. */
  val Root: JsonPath = Nil

  /** A path made of nested object field names, outermost first. Use [[index]] to descend into an array. */
  def of(fields: String*): JsonPath =
    fields.toList.map(name => s".$name")

  extension (path: JsonPath)

    /** The JSONPath-style rendering, always rooted at `$`. Never contains request or credential material. */
    def render: String =
      path.mkString("$", "", "")

    /** Descends into the object field `name`. */
    def field(name: String): JsonPath =
      path.appended(s".$name")

    /** Descends into the array element at `position`, zero-based. */
    def index(position: Int): JsonPath =
      path.appended(s"[$position]")

    /** Whether the path still denotes the whole document. */
    def isRoot: Boolean =
      path.isEmpty
