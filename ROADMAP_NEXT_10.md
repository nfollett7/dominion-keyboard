# Dominion Keyboard — Next 10 Build Items

**Date:** May 4, 2026  
**Priority:** Complete ALL 10 before advancing to agentic system work  
**Philosophy:** Get the keyboard to 95/100 daily-use quality first. The agentic layer is meaningless if the base input experience isn't elite.

---

## The 10 Items

| # | Item | Category | Why It Matters |
|---|------|----------|----------------|
| 1 | Gesture/Swipe Typing | Core UX | #1 feature power users rely on. Without it, the keyboard is objectively slower. GBoard's primary differentiator. |
| 2 | Emoji Panel | Core UX | Every modern keyboard has one. Users expect to tap a smiley face and browse/search emoji. Non-negotiable for daily use. |
| 3 | Clipboard Manager | Core UX | Copy something, access it later. GBoard's clipboard stores recent copies and lets you pin items. Huge productivity feature. |
| 4 | Undo Last Autocorrect | Core UX | When a suggestion is accepted and user hits backspace, revert to what they originally typed. GBoard does this seamlessly. |
| 5 | Key Press Sound | Polish | The setting toggle exists but no audio plays. Need a subtle, satisfying click sound (like GBoard's) with volume tied to system media volume. |
| 6 | Real Dictionary (50K+ words) | Prediction | The current 200-word hardcoded list is a toy. Need a proper English frequency dictionary for instant, accurate local predictions without AI calls. |
| 7 | Input Intelligence System (Full Message Capture) | Data/Twin | **REFRAME:** Not keystroke logging — this is full input capture for building the user's digital twin. Capture complete messages, app context, time patterns, communication style. This is the foundation for the agentic system. |
| 8 | One-Handed Mode | Accessibility | Shrink the keyboard to left or right side of screen. Essential for large phones (which is all phones now). |
| 9 | Theme Engine | Personalization | Users want to customize their keyboard appearance. At minimum: dark/light/AMOLED themes. Stretch: custom accent colors. |
| 10 | Number Row Option | Productivity | Optional persistent number row above QWERTY (like SwiftKey offers). Eliminates constant switching to numbers layout for people who type numbers frequently. |

---

## Item 7 Deep Dive: Input Intelligence System

This is the most architecturally significant item. The current system logs individual keystrokes — that's wrong. The correct model is:

**What to capture:**
- **Full composed messages** — The complete text committed to each input field (not individual characters)
- **App context** — Which app, what type of field (search, message, email, note)
- **Temporal patterns** — When the user types, how long sessions last, peak hours
- **Communication style** — Average message length, formality level, vocabulary richness
- **Topic clustering** — What subjects the user writes about most (via lightweight NLP)
- **Recipient patterns** — Who they message most (app + context inference, not content snooping)

**What NOT to capture:**
- Individual keystrokes (wasteful, privacy risk)
- Password field content (already blocked)
- Banking/financial app input
- Content of messages in encrypted apps

**Data model:**
```
InputCapture {
    id: Long
    sessionId: Long
    fullText: String          // The complete committed message
    appPackage: String        // Which app
    fieldType: String         // message, search, email, note, unknown
    wordCount: Int
    timestamp: Instant
    durationMs: Long          // How long they spent composing
    wasVoiceDictated: Boolean
    wasTranslated: Boolean
    languageDetected: String  // en, es, etc.
}
```

**Why this matters for the agentic system:**
This data becomes the memory layer for the digital twin. When the keyboard's AI makes predictions, routes intents, or suggests actions, it draws from this accumulated understanding of who the user is, how they communicate, and what they care about. Without this, the agentic layer is flying blind.

---

## Build Order

**Phase A (Core UX — must feel like GBoard):**
1. Real Dictionary (50K+ words)
2. Key Press Sound
3. Undo Last Autocorrect
4. Number Row Option

**Phase B (Feature Parity — what users expect):**
5. Emoji Panel
6. Clipboard Manager
7. One-Handed Mode

**Phase C (Differentiators — what makes Dominion unique):**
8. Gesture/Swipe Typing
9. Theme Engine
10. Input Intelligence System

**After all 10 are complete → Agentic System Work begins.**

---

## Definition of Done

Each item is "done" when:
- It works without bugs on a real Android device
- It matches or exceeds the equivalent GBoard feature quality
- It respects all settings toggles
- It disables in password fields where appropriate
- It has no memory leaks or thread safety issues
- The code is clean, documented, and production-grade
