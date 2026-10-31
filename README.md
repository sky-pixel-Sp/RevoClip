# RevoClip

RevoClip is an Android video editor combining a CapCut-style manual editing workflow with an OpusClip-style AI-assisted short-form clipping workflow.

## Product direction

- Import long-form videos and review them in a persistent project queue.
- AI-assisted highlight discovery proposes candidate segments; the user reviews and selects them before export.
- Real video trimming and MP4 export are performed locally with AndroidX Media3 Transformer, not by renaming files.
- Manual editing controls complement automatic clipping: trim ranges, aspect-ratio presets (9:16, 1:1, 16:9), and captions.
- Background processing uses WorkManager so queued jobs can be revisited after the app is closed or restarted.
- AI provider adapters are designed for OpenRouter, Anthropic Claude, OpenAI, and DeepSeek. API keys are entered by the user and must never be committed.

## Android support

The app targets Android 14+ (minSdk 34). The initial implementation is an MVP foundation; advanced capabilities such as diarization, face tracking, animated captions, multi-track timelines, and automatic subject reframing are planned separately and must not be represented as implemented until they are built and tested.

## Build

Open the project in Android Studio or run `./gradlew assembleDebug` with JDK 17 and the Android SDK installed. GitHub Actions builds a debug APK and uploads it as the `RevoClip-debug-apk` artifact.

## Security

Do not put API keys, signing keys, or personal media in source control. AI analysis is optional; video rendering is local.
