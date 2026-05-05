# Dominion Keyboard — Brutal Honest Critique

**Date:** May 4, 2026  
**Verdict: This is a tech demo, not a product.**

---

## The Truth

Right now, if you handed this to anyone who has used GBoard for more than a day, they would uninstall it within 30 seconds. Here's why:

---

## DELETE / BACKSPACE — Broken

**What happens now:** You tap delete, one character disappears. You long-press delete, ALL YOUR TEXT IS NUKED. There is no middle ground.

**What GBoard does:** Tap = delete one character. Hold = characters delete one at a time, accelerating from every 80ms to every 30ms over 2 seconds. After holding for 3+ seconds, it starts deleting whole words. The user has complete granular control at all times. They never lose text unexpectedly.

**What we have:** A nuclear button disguised as backspace. This is the single fastest way to make a user hate your keyboard. They accidentally hold for 0.4 seconds and lose their entire message. That's not a feature — that's a punishment.

**The fix:** Implement a proper `RepeatHandler` that fires `handleDelete()` on a loop with accelerating intervals. Long-press should NEVER clear all text. That's a hidden destructive action with no undo.

---

## MIC EXPERIENCE — Embarrassing

**What happens now:**
1. User taps mic
2. Status bar says "🎤 Starting mic..."
3. Then changes to "🎤 Listening… tap mic to stop"
4. User speaks
5. User taps mic again
6. Status bar says "⏳ Processing…"
7. **2-8 seconds of nothing** (user stares at the screen wondering if it crashed)
8. Text appears

**What GBoard does:**
1. User taps mic
2. **Instantly** shows a full-screen voice recording UI with a pulsing animation
3. Real-time partial transcription appears AS THE USER SPEAKS
4. User stops speaking → text is committed within 200ms
5. Total time from voice to text: <1 second

**What's wrong with ours:**
- No visual recording indicator on the mic KEY itself (it should change color/animate)
- No audio level visualization (user can't tell if mic is picking up sound)
- The "Processing…" state gives ZERO feedback about progress
- 2-8 second wait with no animation feels like a crash
- No way to cancel mid-upload
- If network fails, user gets a generic toast they might not even see
- The mic button doesn't visually change state (pressed/recording/processing)

**The real problem:** We're sending audio to a cloud API and pretending it's a real-time experience. It's not. The UX needs to be honest about the latency — show a proper loading animation, show upload progress, and make it feel intentional rather than broken.

---

## TYPING FEEL — Still Not GBoard

**What's still wrong:**
- Keys fire on `ACTION_DOWN` which is correct for speed, but there's **no visual key pop-up** showing which key was pressed. GBoard shows a magnified preview of the key above your finger. Without this, users can't see what they're pressing on a small screen.
- No key press sound option (even though Settings has a "Sound Feedback" toggle that does nothing)
- The spacebar has NO LABEL — users don't know it's the spacebar. GBoard shows the current language or a subtle "English (US)" text.
- No double-space-to-period feature (Settings toggle exists but is never checked)
- No auto-capitalize after period (Settings toggle exists but is never checked)

---

## SETTINGS — A Lie

The Settings screen promises 6 features. Here's the reality:

| Setting | Promised | Actually Works |
|---------|----------|---------------|
| Haptic Feedback | Toggle on/off | **Always on** (hardcoded in Canvas view) |
| Sound Feedback | Toggle on/off | **Never works** (no sound code exists) |
| Auto-Capitalize | After sentence | **Never works** (no code checks this) |
| Double-Space → Period | Enabled | **Never works** (no code checks this) |
| AI Smart Compose | Toggle GPT suggestions | **Always on** (fires regardless of toggle) |
| Keystroke Logging | Toggle local logging | ✅ Actually works |

**1 out of 6 settings actually functions.** The rest are lies. This is the definition of garage-sale software — it looks like it has features but nothing is wired up.

---

## SECURITY — Actively Dangerous

**The keyboard logs passwords.** When a user types in a password field, every character is:
1. Logged to the local Room database
2. Sent as context to OpenAI's API for "smart predictions"
3. Stored in the `sentenceBuffer` indefinitely

This isn't a minor oversight. This is a **data breach waiting to happen.** Any production keyboard MUST check `EditorInfo.inputType` for `TYPE_TEXT_VARIATION_PASSWORD` and disable ALL intelligence features.

---

## AI PREDICTIONS — Wasteful and Slow

**The problem:** Every time you pause typing for 500ms, the keyboard fires a GPT-4o-mini API call. That's:
- ~$0.0001 per call × hundreds of calls per day = real money burned
- 500-2000ms latency before suggestions appear
- Suggestions arrive AFTER you've already moved on to the next word
- No caching — typing "the" for the 100th time still fires an API call

**What elite keyboards do:** On-device model for instant predictions. Cloud AI only for complex tasks (rewriting, translation). The prediction bar should NEVER wait for a network call.

**The fix:** The local `PredictiveTextEngine` should be the primary suggestion source (instant). AI predictions should only appear for longer contexts (5+ words) and should be clearly marked as "AI suggestion" vs regular autocomplete.

---

## CODE QUALITY — Prototype-Grade

- **Unused imports everywhere** — `SpannableStringBuilder`, `UnderlineSpan`, `VibrationEffect`, `Vibrator`, `VibratorManager`, `Color`, `SystemClock`, `Typeface`, `max` are all imported and never used
- **Dead code** — `micButton` field is declared but never assigned. `AgentRouter` is created but never called from anywhere.
- **Debug logging in hot path** — `Log.d(TAG, "onKeyPressed: tag='${key.tag}'")` fires on EVERY SINGLE KEYSTROKE in production
- **No ProGuard rules** — release builds will crash due to reflection stripping
- **Hardcoded colors** — 20+ hex color values scattered across Kotlin files instead of a centralized theme
- **`!!` operator abuse** — `keyboardView!!`, `rootView!!` etc. Any null state = crash

---

## WHAT NEEDS TO HAPPEN

This keyboard needs to go from "cool tech demo" to "I would actually use this daily." That means:

1. **DELETE must repeat-delete like every keyboard on Earth**
2. **MIC must have a proper recording UI** — visual feedback, cancel button, progress indicator
3. **All 6 settings must actually work** or be removed from the UI
4. **Password fields must disable all AI/logging** — non-negotiable security requirement
5. **AI predictions must not be the primary suggestion source** — local first, AI as enhancement
6. **Key pop-up preview** on press (or at minimum, a visible press state that's more obvious)
7. **Clean the code** — remove dead imports, dead fields, debug logs, `!!` operators
8. **Double-space-to-period** and **auto-capitalize** must work since they're advertised

The architecture is right. The Canvas renderer is right. The concept is right. But the implementation is at about 40% of where it needs to be to ship. Every interaction needs to feel polished, predictable, and trustworthy.

---

**Bottom line:** Would I use this as my daily keyboard right now? No. Would I use it after these fixes? Possibly. Would I use it after Phase 2 (on-device model, gesture typing)? Yes.
