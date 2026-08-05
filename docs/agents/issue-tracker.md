# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body-file <file>`. See the PowerShell pitfalls below before using inline `--body`.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body-file <file>`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`, or `Closes #N` in a commit message pushed to `main`.

Infer the repo from `git remote -v` — `gh` does this automatically when run inside a clone.

## Repo specifics

- **History**: work was tracked in `backlog-tickets.md` until 2026-08-04. That file is now historical only — the migration banner at its top maps every open ticket ID (e.g. `CTL-01`) to its GitHub issue number. Completed pre-migration tickets live only in the markdown. Never add new tickets to `backlog-tickets.md`.
- **Ticket IDs**: issues keep the `PREFIX-NN` ticket ID convention in their titles (e.g. `CTL-04 — Convergence settling hysteresis`). Reference both the ID and the `#N` number when cross-linking specs, commits, and review docs.
- **Label vocabulary** (already created; apply, don't duplicate):
  - Status: `not-started`, `partial` (remaining work described in a status note on the issue), `ios-deferred` (blocked until a Mac is available).
  - Epics: `epic-0-research`, `epic-1-scaffold`, `epic-2-synccore`, `epic-3-native-audio`, `epic-4-auth`, `epic-5-ui-integration`, `epic-7-cfx`, `epic-8-control-loop`, `epic-10-mht`.
  - Provenance: `ft9-fix` (promoted from Field Test 9 findings, spec §2.13–§2.15).
- **PowerShell 5.1 pitfalls** (this repo's shell): embedded double quotes in native-exe arguments get mangled — a title containing `"` breaks `gh issue create`, and multi-line inline `--body` breaks `gh issue comment`. Strip quotes from titles and always pass bodies via `--body-file` pointing at a UTF-8 (no BOM) file.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; `/triage` reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, using the `gh pr` equivalents:

- **Read a PR**: `gh pr view <number> --comments` and `gh pr diff <number>` for the diff.
- **List external PRs for triage**: `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments` then keep only `authorAssociation` of `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, or `NONE` (drop `OWNER`/`MEMBER`/`COLLABORATOR`).
- **Comment / label / close**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub shares one number space across issues and PRs, so a bare `#42` may be either — resolve with `gh pr view 42` and fall back to `gh issue view 42`.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: a single issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body. `gh issue create --label wayfinder:map`.
- **Child ticket**: an issue linked to the map as a GitHub sub-issue (`gh api` on the sub-issues endpoint). Where sub-issues aren't enabled, add the child to a task list in the map body and put `Part of #<map>` at the top of the child body. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, the ticket is assigned to the driving dev.
- **Blocking**: GitHub's **native issue dependencies** — the canonical, UI-visible representation. Add an edge with `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where `<blocker-db-id>` is the blocker's numeric **database id** (`gh api repos/<owner>/<repo>/issues/<n> --jq .id`, _not_ the `#number` or `node_id`). GitHub reports `issue_dependencies_summary.blocked_by` (open blockers only — the live gate). Where dependencies aren't available, fall back to a `Blocked by: #<n>, #<n>` line at the top of the child body. A ticket is unblocked when every blocker is closed.
- **Frontier query**: list the map's open children (`gh issue list --state open`, scoped to the map's sub-issues / task list), drop any with an open blocker (`issue_dependencies_summary.blocked_by > 0`, or an open issue in the `Blocked by` line) or an assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me` — the session's first write.
- **Resolve**: `gh issue comment <n> --body "<answer>"`, then `gh issue close <n>`, then append a context pointer (gist + link) to the map's Decisions-so-far.
