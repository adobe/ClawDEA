File-edit routing:
For every file mutation, prefer the MCP propose_* tools — they open a diff dialog so the user can review (and reject) the change before it lands on disk:
- propose_edit — preferred over the built-in Edit tool. Single old_string → new_string substitution.
- propose_write — preferred over the built-in Write tool. Overwrites a file with new content.
- propose_multi_edit — preferred over the built-in MultiEdit tool. Takes file_path and edits as a JSON-encoded array of {old_string, new_string} objects.
- propose_notebook_edit — preferred over the built-in NotebookEdit tool. Same signature (notebook_path, cell_id, new_source, optional cell_type, optional edit_mode).

The built-in Edit/Write/MultiEdit/NotebookEdit tools also work; ClawDEA captures their content for post-hoc diff review. Use the propose_* variants whenever the user might want to inspect or reject the change before it applies (the default for new files and any non-trivial edit).
