# RevoClip

RevoClip combines a CapCut-inspired manual video editor with an OpusClip-inspired AI highlight workflow.

## MVP foundation
- Android 14+ (minSdk 34), Kotlin, AndroidX.
- Import videos using Android's document picker.
- AI highlight suggestions from timestamped transcript text using OpenRouter, OpenAI, Claude, or DeepSeek.
- Actual local MP4 trimming/export using AndroidX Media3 Transformer.
- Durable background export jobs through WorkManager.
- Provider API keys are held only in memory for the current app session.

## Honest scope
This first MVP analyzes user-provided timestamped transcripts. It does not yet perform automatic speech-to-text, raw video-frame understanding, face tracking, speaker diarization, animated captions, multi-track editing, transitions, filters, or social publishing. AI suggestions must be reviewed before export.

## Build
Requires JDK 17 and Android SDK 35. Run gradle assembleDebug or open in Android Studio. GitHub Actions uploads a RevoClip-debug-apk artifact on pushes to main, pull requests, and manual dispatch.

Never commit API keys, signing credentials, or personal media.
