# GitHub Automation Safety Workflow

## Objective
Prevent GitHub rate-limit problems, suspicious automation patterns, account restrictions, and repository pollution while building the Crypto AI project.

## Core Rule
Do not use GitHub as a live database. GitHub is for source code, documentation, releases, and reviewed milestones only.

## Batch and Push Strategy

### Required Behavior
- Group related edits into one milestone commit.
- Avoid repeated tiny commits for every small UI or text change.
- Prefer one push after a meaningful feature is complete, tested, and internally reviewed.
- Maximum target frequency: one push every five minutes during active development.
- Better target frequency: one push per major milestone.

### Bad Pattern
- Commit every small change.
- Push every few seconds.
- Rewrite the same file repeatedly when a smaller patch or isolated page would work.

### Good Pattern
- Inspect files.
- Plan exact edits.
- Apply grouped changes.
- Review syntax and navigation.
- Push once.

## Logs and State

### Do Not Commit Runtime Logs
Runtime logs, decision histories, trade journals, local state, and paper-position snapshots must not be committed repeatedly to the repository.

### Recommended Storage
- Local SQLite for bot state and paper-trading state.
- Local JSON for development settings.
- Supabase or another database for remote state when needed.
- Browser localStorage only for lightweight UI preferences.

### Required .gitignore Items
```gitignore
logs/
*.log
state.json
runtime-state.json
paper-state.json
*.sqlite
*.sqlite3
.env
.env.local
.env.*
```

## Authentication Guidance

### Preferred
- GitHub App / installation token for heavy automation.

### Avoid
- Personal access tokens for high-frequency automated writes.
- Storing tokens in the repo.
- Exposing keys in GitHub Pages front-end code.

## Backoff and Jitter Rules

### Write Operations
- Wait at least one second between write operations.
- Batch changes whenever possible.
- Do not run multiple writes to the same path in parallel.

### Error Handling
If GitHub returns:
- `403`
- `429`
- secondary rate-limit warning
- abuse detection warning

Then:
1. Stop write operations.
2. Wait using exponential backoff.
3. Add jitter.
4. Retry only after the wait period.
5. If repeated, stop and require manual review.

### Backoff Example
```text
Attempt 1: wait 2 seconds
Attempt 2: wait 4 seconds
Attempt 3: wait 8 seconds
Attempt 4: wait 16 seconds
Attempt 5: stop and review
```

## Development Checklist

Before pushing:

- [ ] Is this a meaningful milestone, not a tiny repeated patch?
- [ ] Did we avoid rewriting working pages unnecessarily?
- [ ] Did we avoid committing runtime logs or state files?
- [ ] Are `.env` and API keys excluded?
- [ ] Is there no user API key inside front-end code?
- [ ] Did we update navigation only when the new page exists?
- [ ] Did we hard-refresh/cache-bust when testing GitHub Pages?
- [ ] Did we avoid more than one push in five minutes unless urgent?

## Project-Specific Rule
For this Crypto AI project:

- Manual Analyzer is the detailed analysis page.
- Agent Page is the live visual monitor.
- Live Decision Page is the multi-timeframe decision scanner.
- Complex features should be built as isolated pages first, then linked from Home only after they load correctly.

## Safe Build Workflow

1. Inspect current file.
2. Identify exact target section.
3. Patch only the needed section.
4. Avoid changing unrelated UI.
5. Test page logic mentally and with browser reload.
6. Commit once with a descriptive message.
7. Wait before further writes.

## Emergency Rollback Rule
If a page breaks:

1. Stop adding features.
2. Restore the last known-good version.
3. Confirm live page loads.
4. Reintroduce the feature as an isolated module or page.

## Security Rule
GitHub Pages must remain read-only public dashboard code. Any future real account connection, API key use, private state, or execution workflow must be handled by a backend service, not browser-only static code.
