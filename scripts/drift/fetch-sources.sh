#!/usr/bin/env bash
# Captures the current state of every source listed in watchlist.yaml and
# emits a single JSON document to stdout.
#
# Output shape:
#   { "<id>": "<text content>", "<id>": "<text content>", ... }
#
# Usage: scripts/drift/fetch-sources.sh > snapshot.json
#
# Requires: bash, curl, claude (in PATH), jq, yq (mikefarah/yq).

set -euo pipefail

WATCHLIST="$(cd "$(dirname "$0")" && pwd)/watchlist.yaml"
[[ -f "$WATCHLIST" ]] || { echo "watchlist.yaml not found at $WATCHLIST" >&2; exit 1; }

# Convert HTML to prose lines, suitable as diff input.
#
# 1. Strip <script>, <style>, <noscript> blocks entirely (multi-line, slurped
#    via perl -0777). This nukes Next.js/Mintlify __next_f.push blobs that
#    embed bundle hashes and CDN deploy IDs in inline-encoded JSON.
# 2. Insert newlines at structural close tags so the output is one line per
#    paragraph/list-item/heading rather than one giant blob.
# 3. Strip remaining tags, decode common entities, normalize whitespace.
# 4. Drop residual noise lines: ?dpl=<deploy-id> query params, /static/chunks/<hash>.js
#    paths, and standalone hex hashes that survived the pass.
# 5. Drop lines shorter than 4 chars (single tokens with no diff signal).
strip_html() {
  perl -0777 -pe 's{<(script|style|noscript)\b[^>]*>.*?</\1>}{}gsi' \
    | perl -pe 's{</(p|li|h[1-6]|div|tr|td|th|article|section|header|footer|nav|main|blockquote|pre)>}{\n}gi' \
    | sed -E 's/<[^>]+>/ /g; s/&nbsp;/ /g; s/&amp;/\&/g; s/&lt;/</g; s/&gt;/>/g; s/&#x27;/'\''/g; s/&quot;/"/g' \
    | sed -E 's/[[:space:]]+/ /g; s/^ +//; s/ +$//' \
    | awk 'length > 3' \
    | grep -vE '\?dpl=dpl_[A-Za-z0-9_]+|static/chunks/[^[:space:]]+\.js|^[a-f0-9]{20,} *$'
}

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

ids=()
count=$(yq '. | length' "$WATCHLIST")
for ((i=0; i<count; i++)); do
  id=$(yq ".[$i].id" "$WATCHLIST")
  src_type=$(yq ".[$i].source.type" "$WATCHLIST")
  ids+=("$id")
  out_file="$TMPDIR/$id.txt"
  case "$src_type" in
    command)
      cmd=$(yq ".[$i].source.cmd" "$WATCHLIST")
      eval "$cmd" > "$out_file" 2>&1 || true
      ;;
    url)
      url=$(yq ".[$i].source.url" "$WATCHLIST")
      curl -fsSL --max-time 30 "$url" 2>/dev/null | strip_html > "$out_file" || true
      ;;
    npm)
      pkg=$(yq ".[$i].source.package" "$WATCHLIST")
      npm view "$pkg" version > "$out_file" 2>/dev/null || true
      ;;
    *)
      echo "unknown source type: $src_type for id $id" >&2
      : > "$out_file"
      ;;
  esac
done

# Build the JSON by accumulating jq additions, one key per id, value via --rawfile
# so size limits and escaping are not our problem.
JQ_FILTER='.'
JQ_ARGS=()
for id in "${ids[@]}"; do
  # jq variable names can't contain hyphens; underscore-ify for the var only.
  var="v_${id//-/_}"
  JQ_FILTER+=" | .[\"$id\"] = \$$var"
  JQ_ARGS+=(--rawfile "$var" "$TMPDIR/$id.txt")
done

# shellcheck disable=SC2068
jq -n "${JQ_ARGS[@]}" "$JQ_FILTER" <<<'{}'
