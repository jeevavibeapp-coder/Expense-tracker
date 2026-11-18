# Android scenarios not executed by the JVM harness

All refer to 47dece72ab90a6f6a8a137a89ced08134fdb2124. These are precise device/Compose test specifications, not passing test claims.

- K6: populate an SMS provider with 500 newer nonfinancial rows and one older valid message "INR 500.00 debited from account XX1234 to AMAZON on 18-Nov-2026 at 12:00." within 90 days. Open the SMS build twice. Expected: financial message eventually appears once. Current loop stops before it. Test with a provider cursor plus emulator E2E after pagination is implemented.
- K8: use a test DocumentsProvider whose stream blocks for 2 seconds per chunk, then an oversized stream (104857600 spaces followed by {}). Trigger both export and import. Assert StrictMode reports no main-thread I/O; UI remains responsive and the oversized input is rejected at a configured byte limit. Repeat export as well as import.
- S-003: open both Settings and Privacy backup entry points. A Compose test must assert that an unencrypted-financial-data warning is visible before invoking the CreateDocument launcher. Inspecting a string literal alone does not prove this user flow.
- SMS lifecycle: inject genuine multipart PDUs; verify one assembled row; use am kill while backgrounded, deliver SMS, reopen and verify recovery. Compare original SMS timestamp with live/inbox identity across midnight. No such device execution was performed in this review.
- Permission: deny, deny permanently, grant in Settings, then return. Verify honest status, recovery scan, and no infinite allow-button loop. The no-SMS build must show paste/manual paths.
- Accessibility: run native light/dark Compose accessibility checks, inspect TalkBack names and 48dp targets, and render main flows at 200% font scale. SolContrastTest.kt is a source color calculation only, not these device checks.
