#!/bin/bash
# rider-screenshot.sh - Capture the active JetBrains Rider window, resized for vision model
#
# Usage:
#   ./scripts/rider-screenshot.sh [output_path] [max_px]
#
# Defaults:
#   output_path = /tmp/rider-shot.png
#   max_px      = 800   (optimal for qwen3-vl-32b with 8192 context)
#
# Why 800px:
#   Rider runs at Retina 2x, so a window is ~2000px wide natively.
#   At 800px, visual tokens = ~644 (vs ~4320 native), giving 5x faster inference.
#
# Exit codes:
#   0 = success
#   1 = Rider not running or no window found
#   2 = screencapture failed

set -euo pipefail

OUTPUT="${1:-/tmp/rider-shot.png}"
MAX_PX="${2:-800}"

# Find Rider window ID via CoreGraphics
WINDOW_ID=$(swift - 2>/dev/null <<'EOF'
import Foundation
import CoreGraphics
let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as! [[String: Any]]
for w in windows {
    let owner = w["kCGWindowOwnerName"] as? String ?? ""
    if owner.lowercased().contains("rider") {
        let wid = w["kCGWindowNumber"] as? Int ?? 0
        let layer = w["kCGWindowLayer"] as? Int ?? 99
        if layer == 0 && wid > 0 {
            print(wid)
            break
        }
    }
}
EOF
)

if [ -z "$WINDOW_ID" ]; then
    echo "ERROR: JetBrains Rider window not found. Is Rider running?" >&2
    exit 1
fi

# Capture the specific window (no shadow, no desktop)
TMP_NATIVE="/tmp/rider-shot-native-$$.png"
if ! screencapture -x -l "$WINDOW_ID" "$TMP_NATIVE" 2>/dev/null; then
    echo "ERROR: screencapture failed for window $WINDOW_ID" >&2
    exit 2
fi

# Resize to max_px (longest side) to reduce visual token count
sips -Z "$MAX_PX" "$TMP_NATIVE" --out "$OUTPUT" 2>/dev/null
rm -f "$TMP_NATIVE"

# Report actual dimensions
python3 -c "
import struct
with open('$OUTPUT','rb') as f:
    f.seek(16)
    w = struct.unpack('>I', f.read(4))[0]
    h = struct.unpack('>I', f.read(4))[0]
    tokens = (w//28)*(h//28)
    import os
    size = os.path.getsize('$OUTPUT')
    print(f'$OUTPUT: {w}x{h}px ~{tokens} visual tokens {size//1024}KB')
" 2>/dev/null || echo "$OUTPUT: captured (window $WINDOW_ID)"
