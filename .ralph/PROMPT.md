# Ralph Loop: Codebase Cleanup Self-Improvement

**Meta Goal:** Make this codebase cleaner, fewer bugs, more maintainable for Claude Code

**Methodology:** Think → Verify → Research → Execute → Learn → Improve

---

## 🎯 Current Iteration State

Read `.ralph/scratchpad.md` for current task, progress, and blockers.

---

## 🔄 Self-Improving Protocol

### Phase 1: ASSUMPTION VALIDATION (MANDATORY)

Before executing any recommendation, answer these questions:

#### Questions to Ask
1. **What assumption is this recommendation based on?**
   - Write it down explicitly in `.ralph/assumptions.md`

2. **How can I verify this assumption?**
   - Code search (Grep/Glob)
   - Read the actual implementation
   - Check git history (\`git log --grep\`)
   - Test the behavior

3. **Is the assumption still valid?**
   - ✅ VERIFIED → Proceed to Phase 2
   - ❌ INVALIDATED → Update plan, mark as ⚠️ SKIP
   - ❓ UNCERTAIN → Research in Phase 2

**CRITICAL:** Never trust grep results without reading files. 80% false claim rate when files aren't read.

#### Confidence Markers (use in scratchpad)
- \`✓ VERIFIED\` - Read the code, traced the logic
- \`? INFERRED\` - Based on grep/search, must verify
- \`✗ UNCERTAIN\` - Haven't checked yet

### Phase 2: RESEARCH CHECKPOINT

For each task, check if online docs have changed:

#### Research Checklist
- [ ] IntelliJ Platform SDK docs (2026 version)
- [ ] Claude Agent SDK docs (latest)
- [ ] Java/Kotlin best practices (2026)
- [ ] TypeScript/React patterns (2026)

#### Research Commands
\`\`\`bash
# Use oracle agent for external research
Task tool with subagent_type="oracle"
Prompt: "What's the current best practice for [TOPIC] in 2026?"

# Search for official docs
WebSearch: "[Library] [Feature] best practices 2026"
\`\`\`

**Decision Point:**
- Found better approach? → Update plan, document in scratchpad
- Docs confirm approach? → Proceed with confidence
- Can't find docs? → Proceed cautiously, add TODO for verification

### Phase 3: PLAN REEVALUATION

After research, ask:

1. **Does the original recommendation still make sense?**
   - Yes → Execute
   - No → Update \`.ralph/scratchpad.md\` with new plan
   - Partially → Adjust scope

2. **Is there a simpler approach?**
   - "Investigate Before Complexity" principle
   - Fix root cause, not symptoms

3. **Will this make the codebase more Claude Code-friendly?**
   - Fewer tokens to understand?
   - Easier to search/navigate?
   - Clearer patterns?

### Phase 4: EXECUTION

Execute the smallest possible change:

1. **Make ONE change** (not multiple)
2. **Test immediately** (run tests, manual check)
3. **MANDATORY: Verify build compiles** (see below)
4. **Commit if successful** (ralph.sh handles this)
5. **Document in scratchpad** (what worked, what didn't)

**MANDATORY Build Verification (ALWAYS run before commit):**
\`\`\`bash
# 1. Java compilation MUST pass
./gradlew compileJava

# 2. Full test suite MUST pass
./scripts/test-all.sh
\`\`\`

**CRITICAL:** Never commit without running both commands above.
Iteration 8 broke the build by skipping this verification.

**Optional Quick Tests (for rapid iteration):**
\`\`\`bash
# Quick smoke test (not a substitute for full verification)
npm test --prefix webview -- --run
npm test --prefix ai-bridge -- --run
\`\`\`

### Phase 5: META-LEARNING

After completing a task, extract learnings:

#### Learning Template
\`\`\`markdown
## Learning: [Task Name]

**Assumption:** [What we thought]
**Reality:** [What we found]
**Discovery:** [What changed our understanding]
**Impact:** [How this affects other recommendations]
**For Claude Code:** [How this makes the codebase better for Claude Code]
\`\`\`

**Store learnings:**
\`\`\`bash
cd \$CLAUDE_OPC_DIR && PYTHONPATH=. uv run python scripts/core/store_learning.py \\
  --session-id "ralph-cleanup" \\
  --type WORKING_SOLUTION \\
  --content "[What you learned]" \\
  --context "idea-claude-gui codebase cleanup" \\
  --tags "ralph,cleanup,plugin" \\
  --confidence high
\`\`\`

### Phase 6: ITERATION SIGNAL

End each iteration with a completion signal:

- \`COMPLETION: ITERATION_DONE\` - Task in progress, continue next iteration
- \`COMPLETION: TASK_DONE\` - Task complete, move to next task
- \`COMPLETION: EPIC_DONE\` - All recommendations complete
- \`COMPLETION: BLOCKED\` - Need human input (document in scratchpad why)

---

## 📋 Task Queue

Read \`.ralph/scratchpad.md\` for current task and detailed plan.

### Priority Order (High → Low)

#### P0 - Critical Bugs (Production Blocking)
1. ✅ **PermissionService Singleton** - COMPLETED (v0.2.10)
2. ✅ **Image Attachments** - VERIFIED CORRECT (iter 8)

#### P1 - High Priority (Code Health)
3. ⏳ **Expand Test Suite** - IN PROGRESS
4. ⏳ **Remove Dead Code** - IDENTIFIED
5. ⏳ **Remove Chinese Comments** - PARTIAL

#### P2 - Medium Priority (Maintainability)
6. ⏳ **Gate Debug Logging** - PLANNED
7. ⏳ **Reduce Large Files** - IDENTIFIED
8. ⏳ **Add CI/CD Checks** - PARTIAL

---

## 🔍 Current Task Instructions

### Step 1: Read Scratchpad
\`\`\`bash
Read .ralph/scratchpad.md
\`\`\`

**If scratchpad is empty or has no current task:**
- Pick the next task from the queue above
- Initialize it in scratchpad with:
  - Task name
  - Assumptions to validate
  - Research questions
  - Success criteria

### Step 2: Follow Self-Improving Protocol

Execute Phases 1-6 for the current task:

1. **ASSUMPTION VALIDATION** - Don't trust grep, read files
2. **RESEARCH CHECKPOINT** - Check if docs changed
3. **PLAN REEVALUATION** - Adjust based on findings
4. **EXECUTION** - Make smallest change
5. **META-LEARNING** - Extract insights
6. **ITERATION SIGNAL** - Mark progress

### Step 3: Update Scratchpad

Document in \`.ralph/scratchpad.md\`:
- What you did
- What you found (verified facts with file:line references)
- What assumptions were validated/invalidated
- What's next

### Step 4: Update Assumptions Log

Update \`.ralph/assumptions.md\`:
- Mark assumptions as ✅ VERIFIED or ❌ INVALIDATED
- Add new assumptions discovered
- Note impact on other tasks

---

## 🎓 Meta-Principles

### From CLAUDE.md Global Rules

#### Think Before Acting (Sharpen the Axe)
1. What do I need to know?
2. What tool/approach answers that specific question?
3. Is this the RIGHT tool for the job?
4. Then execute

#### Investigate Before Complexity
- Root cause analysis first
- Simple fix at the source
- Complexity LAST

#### Verify Before Concluding
- Read files, don't trust grep
- Search for ALL occurrences
- Consider what output actually shows

### For Claude Code Maintainability

Make changes that:
- **Reduce tokens** - Smaller files, clearer structure
- **Improve searchability** - Consistent naming, clear patterns
- **Clarify architecture** - Obvious boundaries, documented flows
- **Eliminate ambiguity** - English comments, explicit types
- **Enable automation** - More tests, better CI/CD

---

## 📊 Progress Tracking

Update \`.ralph/scratchpad.md\` with:

\`\`\`markdown
## Iteration [N] Progress

**Task:** [Current task name]
**Status:** [Starting/Validating/Researching/Executing/Learning/Done]
**Confidence:** [Low/Medium/High]

### Assumptions Validated
- [x] Assumption 1: ✅ VERIFIED at file.java:123
- [ ] Assumption 2: ? INFERRED from grep results

### Research Findings
- Found: [What you discovered]
- Source: [URL or file reference]
- Impact: [How this changes the plan]

### Changes Made
- file.java:123 - [What you changed]
- Test result: [Pass/Fail]

### Next Iteration
- [What to do next]
\`\`\`

---

## 🚨 Iteration Limits

- **Max iterations:** 100 (configurable via \`MAX_ITERATIONS\`)
- **Stuck detection:** If no progress for 3 iterations → BLOCKED
- **Test requirement:** Tests must pass before commit

---

## 🎯 Success Criteria (EPIC_DONE)

Ralph loop completes when:

- [x] P0 tasks: All critical bugs fixed
- [ ] P1 tasks: All high priority improvements done
- [ ] P2 tasks: All medium priority improvements done OR documented as deferred
- [ ] Meta goal achieved: Codebase is measurably more Claude Code-friendly
  - Smaller files (<800 lines)
  - English-only comments
  - No dead code
  - Better test coverage (>60%)
  - Gated debug logging

**Measurement:**
\`\`\`bash
# File sizes
find src/main/java -name "*.java" -exec wc -l {} + | sort -rn | head -10

# Chinese characters
grep -r "[\u4e00-\u9fa5]" --include="*.java" --include="*.ts" --include="*.tsx" . 2>/dev/null || echo "No Chinese found"

# Dead code (via tldr)
tldr dead src/main/java --entry "ClaudeSDKToolWindow"

# Test coverage (estimate)
find src/test -name "*Test.java" | wc -l
\`\`\`

---

## 🔄 Start Here Each Iteration

1. Read \`.ralph/scratchpad.md\` for current state
2. If no current task → pick next from queue, initialize scratchpad
3. Follow 6-phase protocol (Validate → Research → Reevaluate → Execute → Learn → Signal)
4. Update scratchpad with progress
5. Emit completion signal

**Remember:** Quality > Speed. Verify assumptions. Check docs. Make the codebase better for Claude Code.

---

**Ready? Read scratchpad and begin.**
