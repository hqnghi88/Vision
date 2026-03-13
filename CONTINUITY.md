# Continuity Ledger

## Goal
Transform "Vision" into a multi-purpose Visual Assistant (AR-style) that provides situational reasoning, risk assessment, and active guidance (e.g., parking lines) via voice and text commands, with stable and precise AR tracking. Specifically, fix "Park Mode" where cyan lines are fixed to the screen instead of aligned with the environment.

## Constraints/Assumptions
- Project is a native Android project using Kotlin DSL.
- Object detection uses MediaPipe via `ObjectDetectorHelper`.
- UI is built with Jetpack Compose.
- Performance is critical for real-time AR overlays.
- Park mode lines must be environment-aligned.

## Key decisions
- Focus on `VisualAssistantEngine` and overlay rendering logic.
- IMPLEMENTED: Dynamic horizon adjustment in `VisualAssistantEngine` using hardware sensor data (pitch/roll).
- REFINED: Redesigned parking guide with realistic 3D perspective (nearWidth=0.45, farWidth=0.05) and improved pitch correction.
- FILTERED: Added obstacle filtering in Park Mode to ignore false positives (like "airplane") and focus on path-intersecting hazards.
- DEPLOYED: Re-installed build to device and restarted app.

## State
- Done: Identified project, SDK, built and installed app.
- Done: Fixed Park Mode cyan lines alignment by integrating IMU sensor data.
- Done: Refined perspective projection for more realistic "pilot" guidance.
- Done: Implemented intelligent obstacle filtering for path-relative hazards.
- Now: Verifying second iteration of alignment with the user.
- Next: Finalize documentation.

## Open questions (UNCONFIRMED if needed)
- None.

## Working set (files/ids/commands)
- `/Users/hqnghi/git/Vision`
- ADB Device: `R5CXC2Z9MHK` (SM-S938B)
- `/Users/hqnghi/git/Vision/app/src/main/java/com/example/vision/VisualAssistantEngine.kt`
- `/Users/hqnghi/git/Vision/app/src/main/java/com/example/vision/MainActivity.kt`
