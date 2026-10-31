# RevoClip v0.2

RevoClip combines a CapCut-inspired manual video-editing workflow with an OpusClip-inspired AI highlight assistant.

## Implemented in v0.2

- Android 14+ (minSdk 34, targetSdk 35), Kotlin and AndroidX.
- Video import through Android's document picker. Persistable URI permission is attempted safely; unsupported providers remain usable for the current session.
- AI highlight suggestions from a user-supplied timestamped transcript using OpenRouter, OpenAI, Claude, or DeepSeek.
- Editable provider model IDs with current defaults: openai/gpt-5.6-terra (OpenRouter), gpt-5.6-terra (OpenAI), claude-haiku-5-5 (Claude), and deepseek-flash (DeepSeek).
- AI response parsing as a JSON array with start_sec, end_sec, title, reason, and confidence. Suggestions are selectable to fill the time range.
- Time input supports decimal seconds and mm:ss.
- Local MP4 clipping through AndroidX Media3 Transformer.
- Long-running export through WorkManager foreground work with progress notification, Android 14 data-sync fallback, and Android 15 media-processing foreground type.
- Finished MP4 exports are published to Movies/RevoClip via MediaStore and can be opened or shared from the app.
- Video URI, transcript, time range, selected model, provider, and AI suggestions survive activity recreation. API keys are intentionally not persisted.
- Unit tests cover AI JSON parsing and time-range parsing/validation.

## Not implemented

Speech-to-text / automatic transcript generation is NOT implemented. Users must provide a timestamped transcript before AI highlight suggestions can be generated. Automatic visual analysis, face tracking, diarization, animated captions, multi-track editing, transitions, filters, and social publishing are not included in this version.

## Build

Requires JDK 17 and Android SDK 35. Run:

    ./gradlew testDebugUnitTest
    ./gradlew assembleDebug

GitHub Actions runs the unit tests and debug APK build, then uploads the RevoClip-v0.2-debug-apk artifact.

## Security

No API keys or signing secrets belong in source control. API keys are entered at runtime and are not saved by this version. The app does not embed provider credentials.
