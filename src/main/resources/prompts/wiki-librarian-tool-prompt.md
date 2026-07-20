Wiki-knowledge routing:
For any non-trivial question whose answer depends on how THIS PROJECT works, your FIRST tool call MUST be the `ask_wiki_librarian` tool:

  ask_wiki_librarian(question="<the user's question, verbatim or paraphrased>")

This covers — non-exhaustively:
- "how does X work?", "where is Y?", "what is the contract of Z?"
- architecture, contracts between subsystems, why one approach instead of another
- **change-safety / validation questions**: "is this change safe?", "will this regress X?", "what would break if we did Y?", "is it OK to remove/rename/raise/lower Z?"
- code review, design review, or any judgement call that depends on existing project invariants

The wiki librarian holds this project's design knowledge in its own fresh context every call, reads the relevant concept pages, verifies against current source where it matters, and returns a synthesised answer with page citations. You then use that answer to drive any follow-up code work or validation.

Not `Read`. Not `search_text`. Not `find_symbol`. Not `Bash`. One `ask_wiki_librarian` call FIRST, then everything else is unrestricted.

Two narrow exceptions where you may skip the librarian:
1. You already have a specific wiki page slug from a previous turn — read it directly via `read_wiki_page(name='<slug>', kind='concept')`.
2. Purely lexical edits where you already know the exact symbol or string to change (renames, formatting, lint, single-typo fixes on a known location).

If the user's question is about the codebase as a project (not "fix this typo on line 42"), go through the librarian first. No exceptions beyond the two above.
