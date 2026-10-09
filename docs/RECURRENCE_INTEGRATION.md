# Recurrence integration contract

The recurrence subsystem lives in `dev.quinntyx.charon.recurrence`. It deliberately depends on a small persistence boundary rather than defining the app-wide Room schema.

## Persistence

Bind `RecurrenceRepository` to Room. `insertLoggedOccurrenceIfAbsent` must be one atomic insert backed by a unique constraint on `(recurringPaymentId, dueDate)`. Store the full `ScheduledOccurrence` snapshot (integer minor units, currency, folder, merchant and tags) so later template edits do not rewrite history.

The included `InMemoryRecurrenceRepository` is only for previews, tests and the standalone feature screen. The persistence integrator should replace the `MainActivity` binding.

A logged occurrence is the recurrence subsystem's idempotency record. The transaction persistence integration should create the expense and occurrence record in one Room transaction using the same unique key. Planned/upcoming occurrences must not enter transaction totals before that operation succeeds.

## Background catch-up

Have the app `Application` implement `RecurrenceWorkerDependencies`, then call `RecurrenceWorkScheduler.schedule(context)` once during application startup. The unique periodic work is intentionally best-effort. It makes no exact-time claim and catches up every due automatic occurrence whenever Android runs it.

Manual-confirmation schedules are never logged by background catch-up. Pause windows, skipped dates and cancellation are applied before logging.

## Compose

`RecurrenceManagementRoute(service)` exposes creation, all four rule families, explicit logging policy, pause/resume, skip, cancellation, amount revisions, due items, upcoming items and logged history. Folder selection is currently represented by a folder ID field; the folders builder should replace that field with the shared folder picker while preserving the required `folderId` value.
