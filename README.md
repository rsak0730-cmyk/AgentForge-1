# AgentForge

GitHub-ready Android AI-agent starter with a four-tab Compose UI, Gemini/OpenAI/OpenRouter configuration, user-authorized Accessibility automation, Shizuku UserService integration, voice input, contact lookup, and a local voicemail-style inbox.

## Important security model
- Shizuku is **not silently enabled**. The user must install/start Shizuku and explicitly authorize AgentForge.
- Accessibility must be explicitly enabled by the user in Android Settings.
- Calls and messages are confirmation-oriented; the app does not silently place a call or send a message from an AI guess.
- API keys are stored only in app-private preferences in this starter. For production, move them to Android Keystore/Encrypted DataStore and never log them.
- The app does not bypass lock screens, app authentication, DRM, or security controls.

## Features included
- ChatGPT/Gemini-like home chat.
- Voice input button.
- Instagram creator link `@edit.og_`.
- API Setup: Gemini/OpenRouter/OpenAI, key, model, base URL.
- Voicemail-style inbox UI with play/stop/delete controls.
- Theme/UI/text-effect settings and dynamic-island geometry settings.
- Accessibility service for user-directed screen inspection, scrolling, text entry and a small accessibility overlay status island.
- Shizuku UserService with a strict allowlist for Home, Back, Recents and Play/Pause key actions.
- Contact lookup and dialer confirmation flow.
- Volume-up accessibility shortcut hook.
- GitHub Actions debug APK build + unit-test job.

## Shizuku setup
Install Shizuku from its official project, start it using its documented method, then open AgentForge > Settings > Authorize Shizuku. Shizuku API 13.1.5 is used.

## Accessibility setup
Android Settings > Accessibility > Installed apps/Downloaded apps > AgentForge > enable the service. This grants the app the ability to read the active UI and perform user-authorized gestures. Treat this as sensitive access.

## GitHub build
Push this folder to a blank GitHub repository. The workflow at `.github/workflows/build-debug-apk.yml` uses Java 17 + Gradle 9.3.1, builds `assembleDebug`, runs `testDebugUnitTest`, and uploads the APK as the `agentforge-debug-apk` artifact.

## Voicemail limitation
Android does not expose one universal API that lets third-party apps record the carrier's incoming call audio after 20 seconds on every device/carrier. The included page is therefore a voicemail-style inbox; a production carrier-voicemail implementation must use the device/carrier-supported visual-voicemail APIs or an approved telephony role.
