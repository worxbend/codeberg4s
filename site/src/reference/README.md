# Reference

Pages to look things up in, rather than to read through. The
[guides](../guides/README.md) explain how to do something; these explain what a
thing is.

- **[API groups](./api-groups.md)** — every one of the eight accessors on
  `CodebergClient` and all 29 nested groups: what each covers, how many
  operations it has, and which class to open in the Scaladoc.
- **[Glossary](./glossary.md)** — the vocabulary this library and Forgejo use.
  Owner against username against organisation name; ref against branch against
  tag; rail; page against limit; idempotent; opaque type.
- **[FAQ](./faq.md)** — the questions the design provokes. Why `Future` and not
  an effect system, why opaque types instead of `String`, why two rails, why
  why the page walk is one helper rather than a `listAll` per group, why jsoniter, and whether it works against Gitea.

The complete and authoritative description of the API is the Scaladoc, which is
generated from the sources for all five published artifacts as part of building
this site. Every public member carries it.
