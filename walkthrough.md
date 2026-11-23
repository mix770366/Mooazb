# Security Summary & Walkthrough

## Architecture & Security Audit

1. **Firebase App Check & Backup Hygiene (`AppCheckInit`, `backup_rules.xml`, `data_extraction_rules.xml`)**
   - Restored environment-appropriate App Check initialization (`DebugAppCheckProviderFactory` in debug builds and `RecaptchaAppCheckProviderFactory` in release builds), prioritizing intent-injected `FIREBASE_APPCHECK_DEBUG_TOKEN` with synchronous `SharedPreferences.Editor.commit()`, falling back to user-configured or deterministic local device debug tokens so physical test devices without ADB access can register or apply their debug token directly from the error banner.
   - Excluded Firebase App Check and Firebase Auth shared preferences from Android Auto Backup and Device Transfer rules.

2. **Prompt & Attachment Sanitization (`PromptInjectionShield`, `CognitiveAiRepository`, `WorkspaceViewModel`, `ChatViewModel`)**
   - Applied Unicode NFKC normalization and stripped zero-width / bidirectional control characters (`\u200B-\u200F`, `\u202A-\u202E`, `\u2060`, `\uFEFF`) prior to indirect prompt injection inspection.
   - Added attachment label sanitization (`sanitizeAttachmentLabel`) and full-text auditing of attached code/text files before inference, preserving user input cleanly when blocked.

3. **Structured Output Serialization (`IdeBridgeViewModel`)**
   - Replaced manual JSON template string interpolation with `org.json.JSONObject` and `org.json.JSONArray` in `buildBridgeConfigJson` so arbitrary repository or branch names cannot inject keys or corrupt JSON structure.

4. **Multimodal & Screen Capture Compliance (`AndroidManifest.xml`, `ScreenCaptureManager`, `MediaAttachmentHelper`)**
   - Declared least-privilege foreground media projection service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`) with a non-exported service component and explicit `MediaProjection.Callback` cleanup (`onStop` and `onCapturedContentResize`), zero broad storage permissions, and periodic cleanup of cached audio files.

5. **Concurrency & State Hygiene (`WorkspaceViewModel`, `AgentsViewModel`, `ChatViewModel`)**
   - Cancelled overlapping streaming/workflow coroutine jobs on session switches, new sessions, or re-submissions, and ensured spoken audio/TTS and MediaRecorder lifecycles dispose cleanly.

## PoC Verification

- Verified NFKC/zero-width obfuscated prompt injection blocking, attachment label stripping, and `JSONObject` escaping in unit tests (`./PromptInjectionShieldTest.kt` -> `gradle :app:testDebugUnitTest`).
- Verified AndroidManifest foreground service/mediaProjection declarations, absence of broad storage permissions, RTL token counter rendering, App Check debug recovery banner, and `+` add-ons menu interactions in Robolectric tests.
