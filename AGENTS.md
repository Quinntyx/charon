# Project contract

Native Android only: Kotlin, Compose, Room, bundled on-device ML Kit OCR. No backend, cloud sync, telemetry, INTERNET permission, uploaded receipts, or automatic platform backup. Manual user-controlled backup/export is allowed.

Store money as integer minor units, not floating point. Folders represent sources/destinations (accounts); tags represent transaction types; merchants are separate. Transfers must not inflate spending. Rolling averages include zero-spend days and must state their window. Keep currencies separate.

Canonical checks: `./gradlew testDebugUnitTest lintDebug assembleDebug`. Add JUnit tests in app/src/test so CI discovers them. CI runs for every push branch and pull request. Each writer owns its separate worktree; do not edit other checkouts. Commit and push only task changes to your assigned Forgejo branch. Never add a GitHub remote.

Use tmux for commands over 10–15 seconds. Preserve failed work. No rm/grep commands, secret commits, force pushes, PR creation, or subagents in leaf tasks. Build worktrees use separate project .gradle state; do not change global caches/configuration.
