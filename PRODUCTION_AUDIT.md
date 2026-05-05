# Dominion Keyboard — Production Audit Report

**Date:** May 4, 2026  
**Auditor:** Manus AI (Elite Critique Agent)  
**Severity Scale:** CRITICAL / HIGH / MEDIUM / LOW

---

## Executive Summary

The current codebase is architecturally sound at the rendering layer (custom Canvas) but has **significant production-readiness gaps** in error handling, thread safety, mic workflow, settings enforcement, memory management, and UX polish. Below is a complete issue inventory with severity ratings.

---

## CRITICAL Issues

### C1: Mic Recording Uses Synchronous `execute()` for Whisper Upload
**File:** `DominionKeyboardIME.kt:608`  
**Problem:** `openAIClient?.transcribeAudio(file)` calls `httpClient.newCall(request).execute()` — this is a **synchronous blocking HTTP call** on `Dispatchers.IO`. While it's on a background thread, OkHttp's synchronous `execute()` holds the thread hostage for up to 30 seconds. If the user taps mic multiple times or the network is slow, this stacks up threads.  
**Production Standard:** Use `enqueue()` with a callback, or use OkHttp's coroutine integration (`suspendCancellableCoroutine` wrapping `enqueue`).  
**Impact:** Thread pool exhaustion under poor network conditions.

### C2: No Cancellation of In-Flight Network Requests
**File:** `OpenAIClient.kt`  
**Problem:** If the user switches away from the keyboard while a Whisper transcription or translation is in-flight, the coroutine is cancelled but the OkHttp `Call` continues running in the background, wasting battery and bandwidth.  
**Production Standard:** Store the `Call` reference and cancel it in `onFinishInputView()` / `onDestroy()`.

### C3: MediaRecorder Not Released on All Error Paths
**File:** `DominionKeyboardIME.kt:596-598`  
**Problem:** `stopRecordingAndTranscribe()` wraps `stop()` and `release()` in a single try-catch. If `stop()` throws (which it does if called in an invalid state), `release()` is never called, leaking the native MediaRecorder resource.  
**Production Standard:** Use `try { stop() } finally { release() }` pattern.

### C4: `sentenceBuffer` Grows Without Bound
**File:** `DominionKeyboardIME.kt:84`  
**Problem:** `sentenceBuffer` appends every word the user types but is only cleared on Enter. In a long messaging session without pressing Enter (e.g., a single long paragraph), this grows indefinitely and is sent as context to the AI prediction API, potentially exceeding token limits and causing API errors.  
**Production Standard:** Cap at 200 characters or last 30 words. Trim from the front.

---

## HIGH Issues

### H1: Settings Toggles Are Cosmetic (Not Enforced)
**File:** `SettingsActivity.kt` vs `DominionKeyboardIME.kt`  
**Problem:** The Settings screen exposes 6 toggles (haptic, sound, auto-capitalize, auto-punctuate, smart compose, keystroke logging). Only **keystroke logging** is actually checked in the IME code (`prefsManager.isKeystrokeLoggingEnabled()`). The other 5 toggles are saved to SharedPreferences but **never read by the keyboard service**.  
**Impact:** Users toggle "haptic feedback off" and nothing changes. This is a trust-destroying UX bug.

### H2: AI Predictions Fire Without Checking `isSmartComposeEnabled()`
**File:** `DominionKeyboardIME.kt:492`  
**Problem:** `triggerAIPrediction()` fires unconditionally on every keystroke pause. It should check `prefsManager.isSmartComposeEnabled()` first. Users who disable "AI Smart Compose" in settings still get API calls made on their behalf, consuming their OpenAI credits.

### H3: No Key Repeat on DELETE Long-Press
**File:** `DominionKeyboardIME.kt:289`  
**Problem:** Long-pressing DELETE calls `clearAllText()` — nuking everything. GBoard's behavior is to repeat-delete characters at an accelerating rate. The current behavior is destructive and unexpected.  
**Production Standard:** Implement a repeating handler that deletes one character every 50ms (accelerating to every 30ms after 500ms).

### H4: Translate Button Not Accessible from Main Keyboard
**File:** `KeyboardCanvasView.kt:334`  
**Problem:** The TRANSLATE button was removed from the main QWERTY layout. It's only in the numbers layout. But the `onKeyPressed` handler still has `"TRANSLATE" -> handleTranslate()`. Users have no way to trigger translation from the primary keyboard without switching to numbers first.

### H5: No Input Type Adaptation
**File:** `DominionKeyboardIME.kt:228`  
**Problem:** `onStartInputView()` receives `EditorInfo` with the field's input type but does nothing with it. GBoard shows a number pad for `TYPE_CLASS_NUMBER`, an email layout for `TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, and a URL layout for `TYPE_TEXT_VARIATION_URI`. Dominion always shows QWERTY.

### H6: No Password Field Detection (Security)
**File:** `DominionKeyboardIME.kt`  
**Problem:** When the user is in a password field (`TYPE_TEXT_VARIATION_PASSWORD`), the keyboard should disable: predictions, logging, composing text, and AI suggestions. Currently it logs passwords to the database and sends them as context to OpenAI.

---

## MEDIUM Issues

### M1: `showStatus()` Auto-Hide Race Condition
**File:** `DominionKeyboardIME.kt:674-683`  
**Problem:** Every call to `showStatus()` launches a new coroutine that hides the bar after 3 seconds by checking `statusBar?.text == msg`. If two status messages fire in quick succession, the first coroutine may hide the second message prematurely.  
**Fix:** Use a single `statusHideJob` that is cancelled and re-launched on each call.

### M2: `onCreateInputView()` Recreates Everything on Configuration Change
**File:** `DominionKeyboardIME.kt:127`  
**Problem:** Android calls `onCreateInputView()` on every configuration change (rotation, dark mode toggle). The current implementation rebuilds all views from scratch. Should cache and reuse when possible.

### M3: Unused Imports and Dead Code
**Files:** Multiple  
**Problem:** `SpannableStringBuilder`, `Spanned`, `UnderlineSpan`, `VibrationEffect`, `Vibrator`, `VibratorManager`, `Color`, `SystemClock`, `Typeface`, `max` are imported but never used. `micButton` field is declared but never assigned. `AgentRouter.kt` is created but never instantiated anywhere.

### M4: PredictiveTextEngine Has Duplicate Dictionary Entries
**File:** `PredictiveTextEngine.kt:194,199`  
**Problem:** "found", "hand", "mother", "think", "need" appear multiple times in the hardcoded dictionary with different frequencies. The later entries overwrite earlier ones in the Map, making the frequency values unpredictable.

### M5: No Network Connectivity Check Before API Calls
**Problem:** The keyboard fires API calls without checking if the device has an active network connection. On airplane mode, every mic tap and AI prediction silently fails after a 15-second timeout.

---

## LOW Issues

### L1: Debug `Log.d` Statements in Production Code
**Problem:** `Log.d(TAG, "onKeyPressed: tag='${key.tag}'")` fires on every single keystroke. This creates logcat noise and minor performance overhead in release builds.  
**Fix:** Guard with `BuildConfig.DEBUG` or use Timber with a release-only no-op tree.

### L2: No ProGuard/R8 Obfuscation Rules
**File:** `proguard-rules.pro`  
**Problem:** The file is empty. Room, OkHttp, and Gson all need keep rules to prevent R8 from stripping reflection-dependent code in release builds.

### L3: Hardcoded Colors in Kotlin Code
**Problem:** All colors are hardcoded as hex integers in `KeyboardCanvasView.kt` and `DominionKeyboardIME.kt`. Should reference a centralized theme/color system for maintainability and future theming support.

---

## Mic Process Timing Audit

| Step | Current Timing | GBoard Equivalent | Issue |
|------|---------------|-------------------|-------|
| Tap mic → recording starts | ~50ms | ~30ms | Acceptable |
| Recording indicator appears | After `start()` succeeds | Before `start()` | Should show immediately |
| Tap mic → recording stops | ~20ms | ~20ms | OK |
| Audio upload begins | Immediately | Immediately | OK |
| Upload completes (Whisper) | 2-8 seconds (network) | <500ms (on-device) | **Inherent cloud latency** |
| Text inserted | After full response | Streaming partial | Should stream if possible |
| Status bar hidden | After insert | After insert | OK |

**Verdict:** The mic workflow is functional but feels slow because of the cloud round-trip. The user explicitly wants Whisper accuracy, so this is acceptable. However, the UX should show a "processing" animation and allow cancellation during upload.

---

## Comparison to Elite Keyboards (2026)

| Feature | GBoard | SwiftKey | Dominion (Current) |
|---------|--------|----------|-------------------|
| Key repeat on delete | Accelerating repeat | Accelerating repeat | Nuclear clear-all |
| Input type adaptation | Full (number, email, URL) | Full | None |
| Password field handling | Disables all AI/logging | Disables all AI/logging | Logs passwords |
| Gesture/swipe typing | Yes | Yes | No |
| Emoji panel | Yes | Yes | No |
| Theme customization | Yes | Yes | No |
| Clipboard manager | Yes | Yes | No |
| One-handed mode | Yes | Yes | No |
| Undo last autocorrect | Yes (backspace) | Yes | No |

---

## Priority Fix Order

1. **C3 + C1 + C2:** Fix MediaRecorder leak, make HTTP async/cancellable
2. **H6:** Disable AI/logging in password fields (security critical)
3. **H1 + H2:** Wire all settings toggles to actual behavior
4. **C4:** Cap sentenceBuffer
5. **H3:** Implement proper key-repeat on DELETE
6. **M1:** Fix status bar race condition
7. **H5:** Basic input type adaptation (at minimum: number pad)
8. **M3:** Clean dead code and unused imports
9. **L1:** Remove debug logging from hot path
