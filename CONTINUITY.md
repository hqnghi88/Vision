# Continuity Ledger

## Goal
Transform "Vision" into a multi-purpose Visual Assistant (AR-style) that provides situational reasoning, risk assessment, and active guidance (e.g., parking lines) via voice and text commands, with stable and precise AR tracking.

## Constraints/Assumptions
- Project is a native Android project using Kotlin DSL.
- Object detection uses MediaPipe via `ObjectDetectorHelper`.
- UI is built with Jetpack Compose.
- Performance is critical for real-time AR overlays.

## Key decisions
- Start the `Medium_Phone_API_36.1` emulator.
- Use `./gradlew` to build and install the app.

## State
- Done: Identified project, SDK, built and installed app.
- Done: Implemented `VisualAssistantEngine` and Temporal Smoothing.
- Done: Added Speech-to-Text and pulsing animations.
- Done: Corrected AR coordinate mapping for Portrait and Landscape orientations.
- Done: Implemented "Silent Mode" (filtered non-essential alerts).
- Now: Testing full landscape workflow.
- Next: Finalize documentation.

## Open questions (UNCONFIRMED if needed)
- None at the moment.

## Working set (files/ids/commands)
- `/Users/hqnghi/git/Vision`
- ADB Device: `R5CXC2Z9MHK`
