# Assumptions Log

Track all assumptions, their validation status, and impact on the plan.

**Format:**
- ✅ VERIFIED - Read code, traced logic, confirmed
- ❌ INVALIDATED - Assumption was wrong, plan adjusted
- ? INFERRED - Based on grep/audit, needs verification
- ⏸️ DEFERRED - Can't verify yet, will revisit

---

## Task 1: PermissionService Singleton Bug

### A1.1: PermissionService uses JVM-global singleton
**Status:** ✅ VERIFIED (from audit)
**Evidence:** PermissionService.java:19-20, 149-154
**Impact:** FIXED in v0.2.10, marked as complete

### A1.2: Singleton causes cross-IDE-instance bugs
**Status:** ✅ VERIFIED (from BUG_REPORT_PERMISSION_CROSS_INSTANCE.md)
**Evidence:** Documented root cause analysis
**Impact:** FIXED in v0.2.10, marked as complete

---

## Task 2: Image Attachments Not Working

### A2.1: Image attachments currently broken
**Status:** ❌ INVALIDATED
**Evidence:** Commit 777d657 added image attachment support (bridge.js:307-314)
**Verification:** Before 777d657, attachments parameter was ignored; after, buildPrompt() handles base64 images
**Impact:** Assumption was backwards - they weren't broken, they were never implemented. Now implemented as of v0.2.10

### A2.2: Legacy OutputLineProcessor causes the issue
**Status:** ❌ INVALIDATED (irrelevant)
**Evidence:** HANDOFF doc was about message parsing, not attachments
**Verification:** Issue was missing feature, not broken legacy code
**Impact:** HANDOFF doc was outdated/speculative

### A2.3: v0.2.10 implements image attachments
**Status:** ✅ VERIFIED at ai-bridge/bridge.js:307-314
**Evidence:** Commit 777d657 added buildPrompt() function with base64 image support
**Code:** Creates { type: 'image', source: { type: 'base64', media_type, data } } blocks
**Impact:** Feature implemented correctly per SDK docs

### A2.4: Base64 prefix is stripped correctly
**Status:** ✅ VERIFIED at webview/src/components/ChatInputBox/hooks/useAttachmentManagement.ts
**Evidence:** Lines 72, 158, 242 all use `.split(',')[1]` to strip "data:image/...;base64," prefix
**Impact:** SDK requirements met - raw base64 sent to API without prefix

---

## Task 3: Remove Dead Code (CRITICAL ISSUE DISCOVERED)

### A3.1: Dead code needs to be removed
**Status:** ❌ INVALIDATED (already attempted in iteration 8)
**Evidence:** Commit 8412da3 removed dead code but broke the build
**Impact:** Task obsolete, new P0 task: Fix broken build

### A3.2: ToolInterceptor class entirely unused
**Status:** ✅ VERIFIED - correctly deleted in iteration 8
**Evidence:** File no longer exists, only referenced in .tldr cache
**Impact:** Correctly removed

### A3.3: ClaudeSession.shouldUseNewBridge() never called
**Status:** ✅ VERIFIED - correctly deleted in iteration 8
**Evidence:** Method doesn't exist in current ClaudeSession.java
**Git history:** Existed in 777d657, deleted in 8412da3
**Impact:** Correctly removed

### A3.4: SessionLoadService.clearListener() never called
**Status:** ✅ VERIFIED - correctly deleted in iteration 8
**Evidence:** Method doesn't exist in current SessionLoadService.java
**Git history:** Existed in 777d657, deleted in 8412da3
**Impact:** Correctly removed

### A3.5: Iteration 8 deleted only dead code
**Status:** ❌ INVALIDATED - BUILD BROKEN
**Evidence:** 13 handler files have "reached end of file while parsing" compilation errors
**Verification:** DependencyHandler.java ends at line 63, missing 250 lines including all method implementations
**Impact:** CRITICAL - All handler classes are broken, build fails, all tasks blocked

---

## Task 4: Fix Broken Build from Iteration 8 (NEW P0 TASK)

### A4.1: Commit 8412da3 broke the build
**Status:** ✅ VERIFIED
**Evidence:**
- Compilation errors in 13 files (all handler classes)
- DependencyHandler.java truncated at line 63
- Git diff shows 250 lines deleted from DependencyHandler
- Similar deletions in all other handler files
**Impact:** All testing, building, and deployment blocked

### A4.2: Only handler files were affected
**Status:** ✅ VERIFIED
**Evidence:** All 13 compilation errors are in src/main/java/.../handler/ directory
**Files affected:**
- DependencyHandler.java
- PermissionHandler.java
- AgentHandler.java
- TabHandler.java
- McpServerHandler.java
- SkillHandler.java
- SettingsHandler.java
- DiffHandler.java
- SessionHandler.java
- FileHandler.java
- HistoryHandler.java
- RewindHandler.java
- FileExportHandler.java
**Impact:** Scope of restoration is limited to handler package

### A4.3: Files were working in commit 777d657
**Status:** ✅ VERIFIED
**Evidence:** Commit 777d657 is tagged as working (translated Chinese comments)
**Impact:** Can safely restore from 777d657

### A4.4: No fix commit exists after 8412da3
**Status:** ✅ VERIFIED
**Evidence:** `git log --oneline` shows 8412da3 is HEAD, no subsequent commits
**Impact:** Must restore manually, no automatic fix available

### A4.5: Iteration 8 used automated deletion tool
**Status:** ? INFERRED (likely sed/awk or similar)
**Evidence:** Precision of deletions (exactly at line boundaries) suggests automated tool
**Impact:** Validates need for compilation checks before committing

---

## Task 5: Expand Test Suite

### A5.1: Test coverage is minimal (<5%)
**Status:** ? INFERRED (from audit counting test files)
**Evidence:** Audit found mostly placeholder tests
**Verification needed:** Run coverage report
**Impact:** Guides which components need tests most
**BLOCKED:** Build broken, cannot run tests

### A5.2: PermissionService needs unit tests
**Status:** ? INFERRED (from audit noting no PermissionService test)
**Evidence:** Critical component with 0 tests
**Verification needed:** Check if integration tests cover it
**Impact:** High priority if no coverage
**BLOCKED:** Build broken, cannot add tests

### A5.3: MessageDispatcher needs unit tests
**Status:** ? INFERRED (from audit)
**Evidence:** Core routing logic, 0 unit tests
**Verification needed:** Check for integration tests
**Impact:** Medium priority, may be covered by E2E tests
**BLOCKED:** Build broken, cannot add tests

---

## Task 6: Remove Chinese Comments

### A6.1: Chinese comments exist in extract-version.mjs
**Status:** ? INFERRED (from audit)
**Evidence:** Audit claimed to find Chinese at lines 7, 11, 13
**Verification needed:** Read the actual file
**Impact:** If VERIFIED → translate; if INVALIDATED → skip
**BLOCKED:** Build broken, low priority task

### A6.2: No other files have Chinese
**Status:** ? INFERRED (from audit noting "recent cleanup")
**Evidence:** Commit 777d657 "translate Chinese to English"
**Verification needed:** Full repo grep for Chinese characters
**Impact:** Scope of work - 1 file vs many files
**BLOCKED:** Build broken, low priority task

---

## Task 7: Gate Debug Logging

### A7.1: Debug logging is intentional for E2E
**Status:** ✅ VERIFIED (from LEARNINGS.md and code comments)
**Evidence:** [PERM_DEBUG], [E2E DEBUG] prefixes documented
**Impact:** Don't remove, but gate behind flag
**BLOCKED:** Build broken, low priority task

### A7.2: Debug logging not production-ready
**Status:** ✅ VERIFIED (from audit)
**Evidence:** Always-on debug statements in production code
**Impact:** Need environment variable or config flag
**BLOCKED:** Build broken, low priority task

---

## Task 8: Reduce Large Files

### A8.1: Files >800 lines hit token limits
**Status:** ✅ VERIFIED (from LEARNINGS.md)
**Evidence:** LEARNINGS.md documents 800-line guideline
**Impact:** Refactor ClaudeHistoryReader (984), BridgeDirectoryResolver (984)
**BLOCKED:** Build broken, medium priority task

### A8.2: Recent refactoring already reduced App.tsx and ClaudeSDKToolWindow
**Status:** ✅ VERIFIED (from audit and git log)
**Evidence:** App.tsx 2800→600, ClaudeSDKToolWindow 2000→200
**Impact:** Pattern to follow for other large files
**BLOCKED:** Build broken, medium priority task

---

## Task 9: Add CI/CD Checks

### A9.1: Current CI runs unit tests
**Status:** ? INFERRED (from build.yml existence)
**Evidence:** Audit mentions CI/CD checks
**Verification needed:** Read .github/workflows/build.yml
**Impact:** Understand current state before adding checks
**BLOCKED:** Build broken, low priority task

### A9.2: No test coverage gates exist
**Status:** ? INFERRED (from audit recommendation)
**Evidence:** Audit suggests adding coverage gates
**Verification needed:** Check build.yml for coverage tools
**Impact:** Need to add JaCoCo or similar
**BLOCKED:** Build broken, low priority task

---

## Meta Assumptions

### M1: Smaller files are better for Claude Code
**Status:** ✅ VERIFIED (from CLAUDE.md global rules)
**Rationale:** Fewer tokens to process, easier to navigate
**Impact:** Guides file size reduction tasks

### M2: English-only improves Claude Code understanding
**Status:** ✅ VERIFIED (from CLAUDE.md project rules)
**Rationale:** Claude Code trained primarily on English
**Impact:** Justifies Chinese comment removal

### M3: More tests improve maintainability
**Status:** ✅ VERIFIED (industry best practice)
**Rationale:** Catch regressions, enable refactoring
**Impact:** Justifies test expansion priority

### M4: Dead code increases confusion
**Status:** ✅ VERIFIED (clean code principles)
**Rationale:** Developers waste time understanding unused code
**Impact:** Justifies dead code removal

### M5: Build must pass before committing
**Status:** ✅ VERIFIED (from iteration 8 failure)
**Rationale:** Broken builds block all work
**Impact:** CRITICAL - Update Ralph protocol to enforce compilation checks
**Learning:** Iteration 8 broke the build by not verifying compilation

### M6: Automated deletion tools are dangerous without verification
**Status:** ✅ VERIFIED (from iteration 8 failure)
**Rationale:** sed/awk can accidentally delete too much (closing braces, etc.)
**Impact:** Use Edit tool for precise changes, verify each file compiles
**Learning:** Iteration 8 likely used automated tool that deleted entire class bodies

---

## Iteration 9 Key Findings

### F9.1: Iteration 8 successfully removed dead code
**Status:** ✅ VERIFIED
**Evidence:** ToolInterceptor.java, shouldUseNewBridge(), clearListener() correctly deleted
**Impact:** Original dead code task was completed

### F9.2: Iteration 8 also broke the build
**Status:** ✅ VERIFIED
**Evidence:** 13 handler files truncated, missing method implementations
**Impact:** Build broken, all work blocked

### F9.3: Dead code removal needs compilation verification
**Status:** ✅ VERIFIED (new protocol requirement)
**Evidence:** Would have caught iteration 8 breakage immediately
**Impact:** Update PROMPT.md Phase 4 to require `./gradlew compileJava` before commit

### F9.4: Ralph loop lacks safety checks
**Status:** ✅ VERIFIED
**Evidence:** No automated build verification in ralph.sh
**Impact:** Add compilation check to ralph.sh before git commit

---
