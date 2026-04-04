#!/bin/bash
# vision-describe.sh - Describe a screenshot using local Qwen3-VL via LM Studio
#
# Usage:
#   ./scripts/vision-describe.sh <image_path> [prompt]
#
# Requirements:
#   - LM Studio running with Qwen3-VL-32B loaded, local server ON (http://localhost:1234/v1)
#   - llm CLI installed (pipx install llm && pipx inject llm llm-lmstudio)
#
# Examples:
#   ./scripts/vision-describe.sh /tmp/screenshot.png
#   ./scripts/vision-describe.sh /tmp/screenshot.png "Is there a permission dialog visible? Answer YES or NO, then describe it."
#   ./scripts/vision-describe.sh /tmp/screenshot.png "List all visible UI elements as a JSON array."

set -euo pipefail

IMAGE="${1:?Usage: vision-describe.sh <image_path> [prompt]}"
PROMPT="${2:-Describe this IDE screenshot in detail. List every visible UI element, dialog, panel, tab, button, and text content. Be exhaustive.}"

if [ ! -f "$IMAGE" ]; then
    echo "ERROR: File not found: $IMAGE" >&2
    exit 1
fi

# Check LM Studio server is up
if ! curl -sf http://localhost:1234/v1/models > /dev/null 2>&1; then
    echo "ERROR: LM Studio server not running at localhost:1234. Start it first." >&2
    exit 1
fi

# Get the model name from LM Studio (first loaded model)
MODEL=$(curl -sf http://localhost:1234/v1/models | python3 -c "import sys,json; ms=json.load(sys.stdin)['data']; print(ms[0]['id'] if ms else '')" 2>/dev/null)

if [ -z "$MODEL" ]; then
    echo "ERROR: No model loaded in LM Studio." >&2
    exit 1
fi

# Use llm CLI with the lmstudio model
llm "$PROMPT" -m "lmstudio/$MODEL" -a "$IMAGE"
