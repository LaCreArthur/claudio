# Epic: Clean Codebase - Remove Comments & Translate Chinese

## Goal
Remove all comments from the codebase and translate any Chinese strings into English.

## Scope
- **Languages**: Java, TypeScript, JavaScript, CSS, HTML
- **Comment types**: Single-line (`//`, `#`), multi-line (`/* */`, `<!-- -->`), JSDoc/Javadoc
- **Preserve**: License headers (if any)
- **Chinese strings**: User-facing text, error messages, log messages - translate to English

## Directories to Process
- `src/main/java/` - Java plugin code
- `webview/src/` - React/TypeScript frontend
- `ai-bridge/` - Node.js bridge

## Exclusions
- `node_modules/`, `build/`, `dist/`, `.gradle/`
- Test files (comments can stay in tests)
- Generated files
- `*.md` files (documentation)

## Success Criteria
- [x] No comments remain in source files (except license headers)
- [x] All Chinese strings translated to English
- [x] Code still compiles (`./gradlew compileJava`)
- [x] Tests still pass (`./scripts/test-all.sh`)

## Approach
1. Survey codebase - identify files with comments and Chinese strings
2. Process Java files first (largest count)
3. Process TypeScript/JavaScript files
4. Verify builds and tests pass after each batch
5. Final verification
