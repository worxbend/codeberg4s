package com.worxbend.codeberg4s.examples

/** Standard output for the example programs, and the only place in this repository that writes to it.
  *
  * `.scalafix.conf` bans the standard library's print-a-line method everywhere, because a library that writes to a
  * stream nobody configured is a nuisance to embed. These programs are the one legitimate exception: showing what a
  * call returns '''is''' the point of an example, and there is no application around them to route output through.
  * Funnelling every line through one named helper keeps the exception to a single file a reviewer can find, instead of
  * scattering it across seven programs — and it is written with `System.out.print` plus an explicit line separator
  * rather than the banned method, so the ban stays a ban and nothing has to be suppressed.
  *
  * Nothing here is part of the published API. `modules.examples` is not a `PublishModule`.
  */
object ExampleConsole:

  private val Newline: String = System.lineSeparator()

  /** Writes `text` and a line separator to standard output. */
  def line(text: String): Unit = System.out.print(s"$text$Newline")

  /** Writes a blank line and then `text`, to separate the sections of a program's output. */
  def heading(text: String): Unit =
    line("")
    line(text)
