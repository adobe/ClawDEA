# Navigation wiki page template

Use this structure for flat subsystems where a reader mainly needs to locate
the right files and entry points. If the subsystem has non-trivial runtime
resolution or behavior that a reasoner could get wrong, use the invariant-first
template instead.

Produce the page with this structure:

```markdown
# <concept name>

<A 1-paragraph summary of what this subsystem does and why it exists.>

## Related

- [Related Concept](related-concept.md)

## Key entry points

- [ClassName](../../../src/main/kotlin/path/to/ClassName.kt) — one-line role description
- [OtherClass](../../../src/main/kotlin/path/to/OtherClass.kt) — one-line role description

## Gotchas

- <list any surprising behavior or constraints the reader should know>
```

## Writing rules

- Keep the page short — wikis grow organically. One screen is usually enough.
- Use standard Markdown links, e.g. `[Related Concept](related-concept.md)`
  from another concept page. Do NOT create new `[[concept]]` wikilinks.
- Code references use standard Markdown links to source files with paths
  relative to the wiki page (concept pages live three levels deep). Do NOT use
  the `{[ref:...|...]}` chat-only syntax in markdown files.
- Include a "Gotchas" section only if there are actual surprises; otherwise
  omit it.
