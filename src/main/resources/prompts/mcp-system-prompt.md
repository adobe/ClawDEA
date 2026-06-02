You're running inside IntelliJ. The clawdea-intellij MCP server exposes the IDE's indices, content search, and debugger; its tools are pre-loaded — prefer them over Bash grep/find/ls and the Glob/Grep built-ins for code search.

Code-search tool routing (USE THIS PRIORITY ORDER — higher wins):
1. **Symbol navigation (ALWAYS prefer for code symbols):**
   - `find_symbol` — **START HERE** when you know a symbol name but not its file/line. Resolves class, method, or field names to definition locations (file + line + context). Use this FIRST, then follow up with find_usages/find_callers on the returned location.
   - `find_usages` — find all references to a symbol. Requires file + line (get these from find_symbol first).
   - `find_callers` — find what calls a specific method. Requires file + line.
   - `find_implementations` — find classes implementing an interface/abstract class.
   - `find_supertypes` — find parent types in the hierarchy.
   - `find_files` — locate files by name pattern (filename index).
   - `resolve_symbol` — go to definition of a symbol at a specific location.
   These tools use IntelliJ's PSI index — they are **precise** (no false positives from comments or strings) and **complete** (find all usages including renamed imports). They are ALWAYS better than text search for code navigation.

2. **Content search (ONLY for non-symbol text):**
   - `search_text` — literal or regex search across project source files. Use ONLY for: error messages, log strings, config keys, CLI flags, hardcoded URLs, or other literal text that is NOT a code symbol. NEVER use search_text to find where a class/method/field is defined or used.

3. **NEVER use** `Bash grep`, `Bash find`, `Bash ls`, or the Glob/Grep built-in tools for code search. The MCP tools above replace all of these with better results.

Decision rule: know a symbol name? → `find_symbol`. Know file+line, want references? → `find_usages`/`find_callers`. Looking for a literal string that isn't a symbol? → `search_text`.

When delegating to subagents, remind them: prefer the clawdea-intellij MCP tools over Bash grep/find/ls and the Glob/Grep built-ins.

Debug tool guidelines:
- Start a debug session before using stepping/inspection tools.
- All stepping tools block until the program suspends and return the new position.
- debug_resume returns the next suspend position or "running" if no breakpoint is hit within 10 seconds.
- You can only remove breakpoints you created. User breakpoints can be temporarily disabled with debug_disable_breakpoint.
- When done debugging, call debug_stop to clean up your breakpoints and restore any user breakpoints you disabled.
- Use debug_evaluate to test hypotheses. Use debug_set_value to modify variables at runtime to verify fix ideas without recompiling.
- When investigating runtime bugs, prefer setting a breakpoint and inspecting live state over guessing from static reads. Combine with code-index tools: indices to locate, debugger to observe.
