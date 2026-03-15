# Continuity Ledger

## Goal
Transform "Vision" into a multi-purpose Visual Assistant (AR-style) that provides situational reasoning, risk assessment, and active guidance (e.g., parking lines) via voice and text commands, with stable and precise AR tracking. Include features for both Parking and general Driving on-road scenarios (detecting specific traffic signs, lights, speed limits, and prohibited areas using OCR and dynamic color processing).

## Constraints/Assumptions
- Project is a native Android project using Kotlin DSL.
- Object detection uses MediaPipe via `ObjectDetectorHelper`.
- UI is built with Jetpack Compose.
- Performance is critical for real-time AR overlays.
- Park mode lines must be environment-aligned.
- MediaPipe base classification model lacks explicit locale signs, so Google ML Kit Text Recognition is used to extract explicit on-road restrictions.
- OCR misses purely symbolic/pictographic local signs without standardized machine-readable text limits, so pure visual pixel heuristics are needed.

## Key decisions
- Focus on `VisualAssistantEngine` and overlay rendering logic.
- REFINED PARKING LINES (V2): Removed the previous "double line" bug. Consolidated into one single linear path.
- DRIVING MODE: Implemented `DRIVING_ASSISTANT`. OCR parses numbers and text instructions.
- SYMBOLIC SIGN DETECTION: Developed a bespoke, high-performance red-blob detection algorithm using raw `bitmap` pixel scanning every 4 pixels. It clusters vivid traffic-red areas (ignoring white/yellow) and validates aspect ratio to detect unreadable standard prohibitive circular signs (like "No Cars" or "No Parking" symbols).

## State
- Done: Refined perspective projection and filtered intelligent obstacle detection for Parking.
- Done: Integrated Android `TextToSpeech` into `MainActivity` to audibly announce the dynamic proximity alerts and steering advice in real-time.
- Done: Rebuilt parking view into a crisp Red/Yellow/Green single-track.
- Done: Added Google ML Kit Text Recognition for clear English restriction texts.
- Done: Implemented dynamic Red-blob extraction using quick 4px sampling and connected-component algorithms directly over the raw CameraProxy to flag visually obvious standard restrictive traffic signs that defeat standard shape bounding models.
- Done: Recompiled and ran `./gradlew installDebug`, pushing to the ADB device.
- Now: Waiting for user to aim the phone at the specific prohibitive visual rings (the 2 red circled signs) to confirm immediate 1st-class bounding and warning.

## Open questions (UNCONFIRMED if needed)
- If the ML Kit Text bounds are cleanly lining up with the real-time rotated AR display frame or occasionally skewing under heavy motion (could require extra smoothing logic).
- Does the simple uniform red-ring clustering trigger aggressively on brake lights of the lead car at night? (We clipped it to the upper 70% of the screen and demanded an aspect ratio bounds, but could need tighter tuning).

## Working set (files/ids/commands)
- `/Users/hqnghi/git/Vision`
- ADB Device: `R5CXC2Z9MHK` (SM-S938B) - Connected.
- `/Users/hqnghi/git/Vision/app/src/main/java/com/example/vision/VisualAssistantEngine.kt`
- `/Users/hqnghi/git/Vision/app/src/main/java/com/example/vision/MainActivity.kt`
