# Voice Command Keyboard (VCK)

> **A native Android IME that replaces GBoard** — featuring OpenAI Whisper voice dictation, predictive text, Spanish translation, and complete keystroke logging.

---

## Features

| Feature | Description |
|---|---|
| **Full QWERTY Keyboard** | Complete IME with shift, caps lock, numbers, symbols, backspace, enter |
| **Predictive Text** | Offline n-gram engine with 500+ built-in words + learns your personal vocabulary |
| **🎤 Voice Dictation** | Tap mic → speak → OpenAI Whisper transcribes and inserts text |
| **🌐 Spanish Translation** | Tap `ES` → GPT-4o-mini translates current text to Spanish in-place |
| **📊 Input Logging** | Every keystroke, word, dictation, and translation logged to local SQLite DB |
| **📋 History Dashboard** | Browse, search, and export your full typing history as CSV |
| **⚙️ Settings** | Toggle haptic, sound, auto-capitalize, smart compose, logging on/off |
| **Futuristic Dark UI** | Deep space dark theme with cyan/purple accent colors |

---

## Architecture

```
app/
├── ime/
│   └── VoiceKeyboardIME.kt       ← Core InputMethodService (the keyboard itself)
├── api/
│   └── OpenAIClient.kt           ← Whisper + GPT-4 API calls
├── data/
│   ├── db/
│   │   ├── KeyboardDatabase.kt   ← Room database
│   │   ├── KeystrokeLogDao.kt    ← Keystroke queries
│   │   └── SessionLogDao.kt      ← Session queries
│   ├── model/
│   │   ├── KeystrokeLog.kt       ← Keystroke entity
│   │   └── SessionLog.kt         ← Session entity
│   └── repository/
│       └── KeyboardRepository.kt ← Data layer abstraction
├── ui/
│   ├── MainActivity.kt           ← Setup wizard + stats home screen
│   ├── HistoryActivity.kt        ← Full history dashboard
│   ├── HistoryAdapter.kt         ← RecyclerView adapter
│   ├── HistoryViewModel.kt       ← History screen ViewModel
│   └── SettingsActivity.kt       ← Keyboard settings
└── utils/
    ├── PredictiveTextEngine.kt   ← N-gram word prediction
    └── PrefsManager.kt           ← SharedPreferences wrapper
```

---

## Build Instructions

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- **Android SDK** API 26+ (Android 8.0 Oreo minimum)
- **OpenAI API Key** (for Whisper + translation)

### Step 1 — Clone & Open

```bash
git clone https://github.com/nfollett7/voice-command-center.git
cd voice-command-center
```

Open the `voice-command-keyboard/` folder in Android Studio as a project.

### Step 2 — Configure API Key (Optional at Build Time)

You can either:

**Option A — Enter in-app (recommended):** Leave blank at build time and enter your key in the app's setup screen after installation.

**Option B — Inject at build time:** Create `local.properties` (copy from `local.properties.template`) and add:
```
OPENAI_API_KEY=sk-your-key-here
```

### Step 3 — Build

In Android Studio:
```
Build → Make Project (Ctrl+F9)
```

Or via command line:
```bash
./gradlew assembleDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Step 4 — Install on Your Android Device

**Via ADB (USB debugging):**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Via Android Studio:**
Run → Run 'app' with your device connected.

**Via file transfer:**
Copy the APK to your phone and open it. You may need to enable "Install from unknown sources" in Settings → Security.

---

## Setup on Your Phone (After Installation)

### 1. Enable the Keyboard
```
Settings → System → Languages & Input → On-screen keyboard → Manage keyboards
→ Toggle ON "VCK — Voice Command Keyboard"
```

### 2. Set as Default
Open any app with a text field → tap the keyboard icon in the navigation bar → select **VCK — Voice Command Keyboard**.

Or tap **"Set as Default Keyboard"** in the VCK app.

### 3. Enter Your OpenAI API Key
Open the VCK app → enter your `sk-...` key in Step 3 → tap **Save API Key**.

Get a key at: [platform.openai.com/api-keys](https://platform.openai.com/api-keys)

---

## Using the Keyboard

### Regular Typing
- Tap any letter key to type
- **⇧ Shift** — capitalize next letter
- **Double-tap ⇧** — Caps Lock (⇪)
- **⌫ Delete** — backspace one character
- **Long-press ⌫** — clear all text
- **123** — switch to numbers/symbols
- **ABC** — switch back to letters

### Prediction Bar
The top bar shows 3 word suggestions. Tap any suggestion to complete the current word and add a space.

### 🎤 Voice Dictation
1. Tap the **🎤** mic button
2. Speak naturally
3. Tap **⏹** to stop — Whisper transcribes and inserts the text

### 🌐 Spanish Translation
1. Type your English text
2. Tap the **ES** button
3. The text before the cursor is replaced with its Spanish translation

---

## Privacy & Data

- **All keystroke data is stored locally on your device only** — nothing is sent to any server except OpenAI API calls (Whisper audio + translation text).
- OpenAI API calls are only made when you tap 🎤 or ES.
- You can disable logging entirely in Settings → Keystroke Logging.
- You can clear all history from the History screen.

---

## Permissions Required

| Permission | Purpose |
|---|---|
| `INTERNET` | OpenAI Whisper + GPT API calls |
| `RECORD_AUDIO` | Voice dictation via microphone |
| `VIBRATE` | Haptic feedback on key press |

---

## Roadmap

- [ ] Swipe/gesture typing
- [ ] Emoji keyboard panel
- [ ] Custom word dictionary import
- [ ] Sync history to Notion
- [ ] On-device Whisper (no API key needed)
- [ ] More translation languages
- [ ] Word frequency analytics dashboard

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| IME Framework | Android InputMethodService |
| Database | Room (SQLite) |
| Networking | OkHttp |
| AI — Speech | OpenAI Whisper API |
| AI — Translation | OpenAI GPT-4o-mini |
| Architecture | MVVM + Repository |
| UI | Material Components (Dark Theme) |
| Async | Kotlin Coroutines |

---

*Built by Manus AI for Nick Follett — Voice Command Center Project*
