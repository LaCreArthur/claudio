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
# Performance: ~13s (thinking disabled). Use scripts/rider-screenshot.sh for Rider windows.

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

# Build payload: thinking disabled for ~13s inference (vs ~63s with thinking)
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
    "thinking": {"type": "disabled"},
    "messages": [{"role": "user", "content": [
        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img_b64}"}},
        {"type": "text", "text": "/no_think $ASSERTION\n\nBriefly describe what you see relevant to the question (1-2 sentences), then answer YES or NO on the last line."}
    ]}]
}
print(json.dumps(payload))
PYEOF
)

if [ -z "$RESPONSE" ]; then
    echo "ERROR: Empty response from LM Studio." >&2
    exit 2
fi

# Parse content — with regex fallback for LM Studio's occasional invalid JSON
CONTENT=$(python3 - <<PYEOF
import json, re, sys

raw = """$RESPONSE"""

try:
    r = json.loads(raw)
    content = r['choices'][0]['message']['content'].strip()
    print(content)
except Exception:
    # LM Studio sometimes emits unescaped control chars — regex fallback
    m = re.search(r'"content":\s*"(.*?)"(?:,\s*"reasoning_content"|\s*})', raw, re.DOTALL)
    if m:
        content = m.group(1).replace('\\n', '\n').replace('\\"', '"').strip()
        print(content)
    else:
        sys.exit(2)
PYEOF
)

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
