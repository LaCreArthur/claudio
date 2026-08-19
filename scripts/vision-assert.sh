#!/bin/bash
# vision-assert.sh - Assert a visual condition on a screenshot using local VLM
#
# Returns exit code 0 if assertion passes, 1 if fails, 2 if error.
#
# Usage:
#   ./scripts/vision-assert.sh <image_path> <assertion>
#
# Requirements:
#   ~/.lmstudio/bin/lms server start
#   ~/.lmstudio/bin/lms load --yes -c 8192
#
# Performance: ~1-3s warm at 800px on the work M3 Pro. Use scripts/rider-screenshot.sh for Rider windows.

set -euo pipefail

IMAGE="${1:?Usage: vision-assert.sh <image_path> <assertion>}"
ASSERTION="${2:?Usage: vision-assert.sh <image_path> <assertion>}"

if [ ! -f "$IMAGE" ]; then
    echo "ERROR: File not found: $IMAGE" >&2
    exit 2
fi

if ! curl -sf http://localhost:1234/v1/models > /dev/null 2>&1; then
    echo "ERROR: LM Studio not running. Run: ~/.lmstudio/bin/lms server start && ~/.lmstudio/bin/lms load --yes -c 8192" >&2
    exit 2
fi

MODEL=$(~/.lmstudio/bin/lms ps 2>/dev/null | awk 'NR>1 && NF>0 && !/^$/{print $1; exit}')
if [ -z "$MODEL" ]; then
    echo "ERROR: No model loaded. Run: ~/.lmstudio/bin/lms load --yes -c 8192" >&2
    exit 2
fi

# Disable reasoning through LM Studio's current OpenAI-compatible control.
RESPONSE=$(python3 - <<PYEOF | curl -sf http://localhost:1234/v1/chat/completions \
    -H "Content-Type: application/json" \
    --data-binary @-
import json, base64
with open('$IMAGE', 'rb') as f:
    img_b64 = base64.b64encode(f.read()).decode()
payload = {
    "model": "$MODEL",
    "max_tokens": 250,
    "temperature": 0,
    "reasoning_effort": "none",
    "messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img_b64}"}},
        {"type": "text", "text": "$ASSERTION\n\nBriefly describe what you see relevant to the question (1-2 sentences), then answer YES or NO on the last line."}
    ]}]
}
print(json.dumps(payload))
PYEOF
)

if [ -z "$RESPONSE" ]; then
    echo "ERROR: Empty response from LM Studio." >&2
    exit 2
fi

# Parse the JSON response directly. Invalid JSON is an API error, not a second response format.
if ! CONTENT=$(printf '%s' "$RESPONSE" | python3 -c 'import json, sys; print(json.load(sys.stdin)["choices"][0]["message"]["content"].strip())'); then
    echo "ERROR: Could not parse model response." >&2
    exit 2
fi

if [ -z "$CONTENT" ]; then
    echo "ERROR: Could not parse model response." >&2
    exit 2
fi

echo "$CONTENT"

VERDICT=$(echo "$CONTENT" | grep -iE '^(YES|NO)$' | tail -1)
if [ -z "$VERDICT" ]; then
    VERDICT=$(echo "$CONTENT" | tail -1 | tr '[:lower:]' '[:upper:]' | xargs)
fi

if [ "$(echo "$VERDICT" | tr '[:lower:]' '[:upper:]')" = "YES" ]; then
    exit 0
elif [ "$(echo "$VERDICT" | tr '[:lower:]' '[:upper:]')" = "NO" ]; then
    exit 1
else
    echo "WARN: Could not parse YES/NO." >&2
    exit 2
fi
