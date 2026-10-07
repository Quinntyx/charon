# Charon build plan

## Objective and delivery
Build an entirely on-device Android receipt-first money tracker. Deliver accepted code to `dev`, keep `main` as the canonical clone, and publish a public Forgejo repository with a server-side public GitHub mirror. No user financial data is committed.

## Must demonstrate
1. Capture or import a receipt; bundled OCR extracts merchant/date/amount/currency; all suggestions remain editable. Tapping a folder logs the transaction with its receipt. Manual entry works without OCR.
2. Create/manage folders and tags; distinguish merchant from account and transaction type; support expenses, income and transfers.
3. Persist transactions and receipt images locally across restart. No network permission, analytics, cloud sync or platform backup.
4. Recurring transactions with due dates, pause/skip and explicit logging policy; upcoming spending remains separate from recorded expenses; catch-up is idempotent.
5. Tag/folder analytics with rolling 7/30/90-day averages including zero-spend days; currency-safe totals and no duplicate overall counting.
6. Interactive useful spending graphs and transaction drill-down.
7. Explicit local backup/export and restore through Android document APIs, without a cloud backend.

## Optional and stretch
Merchant-based tag suggestions and itemized receipts are optional. Do not substitute fake OCR or seeded financial data for the required path. No iOS work. Build APKs are debug prototypes, not production-signed releases.

## Bootstrap and evidence
CI must run the actual Gradle unit-test discovery, lint and debug APK assembly for every build/merge branch. Use the existing shared Forgejo runner and a prebuilt Android SDK 36 container; the workstation currently has SDK 37 only, so use CI for SDK 36 Android checks rather than installing machine software. Gate every candidate by green required jobs at its exact SHA.

## Cohort
Available shared ceiling is 24 agents. Nine coherent useful subsystems are planned (rather than inventing 72): persistence/domain, receipt capture, OCR parsing, entry/edit flow, folders/tags, recurrence, analytics, dashboard/graphs, backup/export. Isolate each in a separate feature worktree from the green CI baseline. Builders design their subsystem and tests; integration repairs reconcile real incompatibilities rather than imposing speculative interfaces.

## Reviews and integration
Five intermediate review/repair cycles per candidate; loose reviews reject consequential defects and unmet scope. CI-gated balanced same-depth pairwise merges, with separate merge worktrees and repair only for real conflicts/failures. Preserve all work until success. Strict whole-app final review, up to five final repair cycles. Fetch dev before delivery; final pushed dev must be CI green and contain all contributing tips. Do not call a component report a working demo. Report hardware/emulator validation honestly if unavailable.

## Orchestration
Named persistent kernel: charon-build. Use inherited subagent profile/model, no recursion, explicit tasks/checks and finite review counters. Durable notebook records identities, routing, evidence and deliberate teardown. No invented deadline. Secrets stay outside the repository and notebook. Root owns merges, publication and cleanup.
