# Continuity Ledger

## Goal
Run the Android app "Vision" on a simulator on the user's Mac.

## Constraints/Assumptions
- Project is a native Android project using Kotlin DSL.
- Android SDK is located at `~/Library/Android/sdk`.
- Emulator path is `/Users/hqnghi/Library/Android/sdk/emulator/emulator`.
- Available AVD: `Medium_Phone_API_36.1`.

## Key decisions
- Start the `Medium_Phone_API_36.1` emulator.
- Use `./gradlew` to build and install the app.

## State
- Done: Identified project, SDK, emulator, started emulator, built and installed app, launched app, and verified via screenshot.
- Done: Fixed UI flickering by moving CameraX binding to `LaunchedEffect` and optimizing the detection callback loop.
- Done: Improved real-time processing by updating UI on the main thread and using correct frame dimensions for coordinate scaling.
- Done: Fixed GitHub Actions `OutOfMemoryError` and JNI stripping failure by increasing Gradle heap size and using `keepDebugSymbols`.
- Done: Optimized app layout (65/35 split) to eliminate overlap and lag.
- Done: Fixed AI responsiveness by implementing immediate command confirmation and a 2-second analysis loop with flicker reduction.
- Now: Task completed. All features verified on emulator.
- Next: None.

## Open questions (UNCONFIRMED if needed)
- None at the moment.

## Working set (files/ids/commands)
- `/Users/hqnghi/git/Vision`
- `/Users/hqnghi/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1`
