# Recurrence integration contract

The recurrence subsystem lives in `dev.quinntyx.charon.recurrence`. It deliberately depends on a small persistence boundary rather than defining the app-wide Room schema.

## Persistence

Bind `RecurrenceRepository` to Room. Implement `inTransaction` with `RoomDatabase.withTransaction`; every `RecurrenceTransaction` read and write must use DAOs attached to that same database transaction. This serialization boundary is required: schedule mutation validation and saving must be atomic, and catch-up/manual logging must validate the current schedule and insert its log without a pause, skip, cancellation, or amount change committing between those steps.

`insertLoggedOccurrenceIfAbsent` must additionally use a unique constraint on `(recurringPaymentId, dueDate)`. Store the full `ScheduledOccurrence` snapshot (integer minor units, currency, folder, merchant and tags) so later template edits do not rewrite history.

The included `InMemoryRecurrenceRepository` is only for previews, tests and the standalone feature screen. The persistence integrator should replace the `MainActivity` binding.

A logged occurrence is the recurrence subsystem's idempotency record. The transaction persistence integration should create the expense and occurrence record inside the same `inTransaction` call using the same unique key. Planned/upcoming occurrences must not enter transaction totals before that operation succeeds. Do not implement the convenience repository methods with separate DAO calls; they delegate to `inTransaction` specifically to prevent stale reads and lost updates.

## Background catch-up

Have the app `Application` implement `RecurrenceWorkerDependencies`, then call `RecurrenceWorkScheduler.schedule(context)` once during application startup. The unique periodic work is intentionally best-effort. It makes no exact-time claim and catches up every due automatic occurrence whenever Android runs it.

Manual-confirmation schedules are never logged by background catch-up. Pause windows, skipped dates and cancellation are applied before logging.

## Compose

`RecurrenceManagementRoute(service)` exposes creation, all four rule families, explicit logging policy, pause/resume, skip, cancellation, amount revisions, due items, upcoming items and logged history. Folder selection is currently represented by a folder ID field; the folders builder should replace that field with the shared folder picker while preserving the required `folderId` value.
