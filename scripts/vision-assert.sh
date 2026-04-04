#!/bin/bash
# vision-assert.sh - Assert a visual condition on a screenshot using local VLM
#
# Returns exit code 0 if the assertion passes, 1 if it fails.
# Designed for E2E test scripts that need visual verification.
#
# Usage:
#   ./scripts/vision-assert.sh <image_path> <assertion>
#
# Examples:
#   ./scripts/vision-assert.sh /tmp/shot.png "Is there a permission dialog visible?"
#   ./scripts/vision-assert.sh /tmp/shot.png "Does the terminal show Claude's response?"
#   ./scripts/vision-assert.sh /tmp/shot.png "Is the 'Allow Once' button visible?"
#
# Output: prints the model's reasoning, then YES or NO on the last line.
# Exit code: 0 = YES (assertion true), 1 = NO (assertion false), 2 = error

set -euo pipefail

IMAGE="${1:?Usage: vision-assert.sh <image_path> <assertion>}"
ASSERTION="${2:?Usage: vision-assert.sh <image_path> <assertion>}"

if [ ! -f "$IMAGE" ]; then
    echo "ERROR: File not found: $IMAGE" >&2
    exit 2
fi

if ! curl -sf http://localhost:1234/v1/models > /dev/null 2>&1; then
    echo "ERROR: LM Studio server not running at localhost:1234." >&2
    exit 2
fi

MODEL=$(curl -sf http://localhost:1234/v1/models | python3 -c "import sys,json; ms=json.load(sys.stdin)['data']; print(ms[0]['id'] if ms else '')" 2>/dev/null)

if [ -z "$MODEL" ]; then
    echo "ERROR: No model loaded in LM Studio." >&2
    exit 2
fi

PROMPT="You are a visual QA assistant for an IDE plugin. Look at this screenshot and answer the following question.

Question: $ASSERTION

First, briefly describe what you see that is relevant to the question (2-3 sentences max).
Then on the LAST line, answer exactly YES or NO (nothing else on that line)."

RESPONSE=$(llm "$PROMPT" -m "lmstudio/$MODEL" -a "$IMAGE" 2>/dev/null)

echo "$RESPONSE"

# Extract the last non-empty line
VERDICT=$(echo "$RESPONSE" | grep -iE '^(YES|NO)$' | tail -1)

if [ -z "$VERDICT" ]; then
    # Try extracting from last line if model didn't follow format perfectly
    LAST_LINE=$(echo "$RESPONSE" | tail -1 | tr '[:lower:]' '[:upper:]' | xargs)
    if [[ "$LAST_LINE" == "YES" ]]; then
        exit 0
    elif [[ "$LAST_LINE" == "NO" ]]; then
        exit 1
    else
        echo "WARN: Could not parse YES/NO from model response." >&2
        exit 2
    fi
fi

if [[ "${VERDICT^^}" == "YES" ]]; then
    exit 0
else
    exit 1
fi
