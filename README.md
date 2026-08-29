# Card Vault

An offline, encrypted Android app for storing your personal credit and debit card details behind two layers of security: your device's biometric / screen lock, plus an in-app password of your own choosing.

---

## 1. What is this project?

**Card Vault** is a native Android application (Kotlin) that lets you keep the details of your payment cards — number, expiry, CVV, name on card, nickname — safely on your own phone. It is designed for a single user on a single device.

Key design principles:

- **Fully offline.** The app requests no network permissions at all. Nothing ever leaves the device.
- **Encrypted at rest.** Card number, expiry, and CVV are stored using AES-256-GCM. The encryption key is derived from your in-app password via PBKDF2 (100,000 iterations) and held only transiently in the Android Keystore during an unlocked session.
- **Two independent lock layers.** Every launch (and every return from the background) requires biometric / device credential *and* the in-app password you set during onboarding.
- **No cloud, no backup, no autofill.** The database is excluded from Google backup, the app is invisible in the recents / app-switcher preview, and screenshots are blocked on every screen.

---

## 2. What problem does it solve?

Most people carry more cards than they can memorise, and end up:

- writing card details in the Notes app, Google Keep, or a text file — all unencrypted, all backed up to the cloud;
- storing them in a general-purpose password manager, which usually syncs to the vendor's servers; or
- taking a photo of the card and leaving it in the camera roll (which auto-uploads to Google Photos / iCloud).

Card Vault solves this by giving you a **single-purpose, local-only vault** that:

- keeps card data encrypted with a key that is derived only when you unlock the app and never persisted;
- gates the CVV — the field a thief actually needs — behind a second password entry every time it is revealed or copied;
- clears the clipboard automatically 30 seconds after you copy a card number or CVV, so a sensitive value cannot linger there indefinitely;
- refuses to sync, back up, or leak to any third-party service by construction (no `INTERNET` permission is declared).

It is not intended to replace a full password manager. It is a small, auditable tool for one very specific piece of sensitive data.

---

## 3. How to use the app

### 3.1 First launch (onboarding)

1. Install and open the app.
2. A short welcome screen appears.
3. You are asked to **set an in-app password** (minimum 4 characters, entered twice).
4. Behind the scenes the app generates a random salt, derives your master key, and writes a verification "canary" into its encrypted database. Your password itself is never stored.
5. You land on the main screen with an empty state prompting you to add your first card.

> If your phone does not have a screen lock (fingerprint / PIN / pattern) configured, the app will warn you and offer to open **Security Settings**. You can continue without one, but you then lose the first of the two lock layers.

### 3.2 Unlocking on every launch

Every time you open the app (or return to it after the OS backgrounds it), you go through:

1. **Biometric / device credential** — fingerprint, face, PIN, pattern, or password, using Android's `BiometricPrompt`.
2. **In-app password** — the one you chose during onboarding.

If either step fails or is cancelled, the app closes itself.

### 3.3 Adding a card

- Tap the **+** button in the top-left of the toolbar on the main screen.
- Choose **Scan card** to fill the form from the physical card with the camera (see §3.3.1), or
  **Enter manually** to type it in.
- Fill in:
  - **Nickname** — e.g. "HDFC Rewards Platinum".
  - **Name on card**
  - **Card number** — auto-formats with a space every 4 digits; 13–19 digits accepted.
  - **Expiry** — auto-formats as `MM/YY`.
  - **CVV** — 3–4 digits, masked while typing.
  - **Colour** — one of ~12 preset tile colours.
- The card network (Visa / Mastercard / Amex / RuPay / Discover / Diners) is detected in real time from the BIN prefix and shown on the live preview at the top of the screen. No network call is made.
- Tap **Save**. Card number, expiry and CVV are encrypted before being written to the local database.

You can store **up to 30 cards**.

#### 3.3.1 Scanning a card

Hold the front of the card inside the on-screen frame. A checklist shows what has been read so
far: **Number**, **Expiry**, **Name**, **Bank**. Each field only ticks after three separate frames
agree on the same value, so a single bad read is outvoted rather than accepted. The scan finishes
on its own once the number and expiry are both locked, and drops you into the Add Card form with
those fields filled in.

The camera icon inside the **Card number** field on the Add/Edit form does the same thing, and
keeps anything you have already typed in the fields the scan could not read.

Practical notes:

- **The CVV is never scanned.** You always type it yourself — the cursor is placed there for you.
  This is deliberate; see §4.10.
- A **torch button** appears if the camera has a flash, and the hint text tells you when the light
  is too low to read the card.
- If a scan gets something wrong, the confirmation Snackbar has an **Undo** that puts the form back
  exactly as it was.
- If the number fails its checksum, an amber warning appears under the field. It is a warning, not
  a block — you can still save.
- Scanning gives up after about 25 seconds and offers to try again, or to use whatever it did
  manage to read. **Enter manually** is on screen the whole time, in every state, including when
  the camera cannot be opened at all.

What happens to the camera frames: nothing. They are analysed in memory and discarded. The app
binds only the camera's preview and analysis paths — it never constructs a photo or video capture,
so there is no code path that could write a frame anywhere. Text recognition runs entirely
on-device from a model bundled inside the APK, and the app still declares no network permission.

### 3.4 Viewing a card

Tap any card tile on the main screen to open its detail view. There you can:

- **Tap the card number** to toggle between masked (`•••• •••• •••• 1234`) and revealed.
- **Tap the copy icon** next to any field to copy it to the clipboard.
- **Tap "Show CVV"** — you will be prompted to re-enter your in-app password. On success the CVV is displayed.
- **Copy CVV** — same in-app-password prompt; on success the CVV is placed on the clipboard.

Whenever a sensitive value (card number or CVV) is copied, a small notice tells you the clipboard will be cleared in 30 seconds, and it is (only if the value you copied is still there — copying something else in the meantime cancels the auto-clear so it doesn't stomp on your own copy).

### 3.5 Editing / deleting / reordering

- **Long-press** a card tile on the main screen for a bottom sheet with **Reorder**, **Edit**, and **Delete**.
- **Reorder** enters a drag-to-reorder mode with a **Done** button in the toolbar; the new order is saved to the database.
- **Delete** asks for a single confirmation before removing the card.

### 3.6 Searching and filtering

- A search bar in the toolbar filters cards by nickname or name-on-card substring.
- A **bank filter** chip strip above the list narrows the visible cards to a chosen bank (derived from the nickname prefix). "All" is selected by default.

### 3.7 Settings

Reached from the gear icon in the toolbar.

| Setting | What it does |
|---|---|
| **Change in-app password** | Verify current password, enter new one twice. Every stored card is decrypted with the old key and re-encrypted with the new key in a single atomic database transaction — either the entire vault rotates to the new password or nothing does. |
| **Lock on background** | ON by default. When ON, the in-memory key is nulled and the Keystore alias deleted whenever the app is sent to the background, forcing full re-authentication when you return. |
| **CVV re-auth mode** | Choose between **Per action** (default — every CVV reveal or copy prompts) and **Per session** (one successful CVV re-auth authorises CVV access for the remainder of the current unlocked session). |
| **Reset app** | Two chained confirmation dialogs, then wipes the database, clears preferences, locks the session, and restarts onboarding. **This is the only recovery path from a forgotten password — it destroys every stored card.** |
| **App version** | Read-only display of the installed version. |

---

## 4. Known issues and limitations

These are deliberate design constraints or accepted trade-offs, not bugs — but you should know about them before using the app.

### 4.1 No password recovery

If you forget your in-app password, **there is no way to recover your cards**. The password is never stored (only a canary blob encrypted with a key derived from it), and the derived key never leaves an unlocked session. Your only option is **Reset app** in Settings, which permanently wipes all stored cards.

### 4.2 No backup, no sync, no export

By design. Android backup is disabled three ways (`allowBackup="false"`, `backup_rules.xml`, `data_extraction_rules.xml`). There is no export-to-file, no cloud sync, and no share-with-another-device flow.

**If you factory-reset or lose the phone, or uninstall the app, every card is gone.** Consider re-adding your cards on a new device manually.

### 4.3 Minimum password length is 4

Intentionally low, because the in-app password is a *second* factor behind biometric / device unlock, not the primary defence. If you want stronger protection, choose a longer password yourself — the app does not enforce it.

### 4.4 Network logos are generic placeholders

The Visa / Mastercard / Amex / RuPay / Discover / Diners marks shown on card tiles are placeholder vector drawables, not the licensed brand marks. They convey which network was detected but do not visually match the real logos.

### 4.5 30-card ceiling

Once you have added 30 cards the **Add** button is disabled and a message is shown. Delete an existing card to free a slot.

### 4.6 No autofill integration

The app does not register with Android's Autofill Framework. Filling a card into a browser or a shopping app is done manually via the copy icons.

### 4.7 Screenshots and screen recording are blocked

`FLAG_SECURE` is set on every activity. This means you cannot screenshot the app for a bug report or a tutorial — and the app-switcher / recents preview will be blank. If you use a screen-recording accessibility tool, Card Vault frames will show as black.

### 4.8 Dark theme only

The UI ships as `Theme.Material3.Dark.NoActionBar`. There is no light-mode variant.

### 4.9 Card-network detection is prefix-based, not Luhn-validated

Detection uses BIN prefix rules (see the network table in `REQUIREMENTS.md` §3.1). It does not run a Luhn checksum on the card number, so a number that starts with a Visa prefix but is otherwise invalid will still show the Visa logo on the preview. A Luhn check *is* run separately, as a soft amber warning on the number field and as a confirm-before-save prompt — never as a block, because real cards (issuer test PANs, some virtual and private-label numbers) do fail it.

### 4.10 Scanning never reads the CVV, and never will

The scanner reads the card number, expiry, name and issuing bank. It does not read the security
code, and this is structural rather than a setting: the data type that carries a scan result has
four fields and none of them is a CVV, so there is nothing for one to be carried in. You will
always type the CVV yourself.

Two reasons. Practically, the code is on the reverse of most cards and the scanner only ever
frames the front. More importantly, the CVV is the one value in the vault that is of little use to
an attacker without the number — automatically pairing the two from a single camera pass would
trade a real reduction in safety for two seconds of typing.

### 4.11 Scanning is best-effort, and always needs checking

OCR is not reliable enough to trust unread. Expect it to struggle with:

- Worn, scratched, or flat-printed (unembossed) cards, and dark-on-dark colour schemes.
- Glare — a glossy card under a ceiling light or with the torch on can wash out a whole row.
- Cards where the name is stylised, or the bank name is a logo rather than text.
- Four-digit-year expiries, vertical card layouts, and non-Latin scripts (the recogniser is
  Latin-script only).
- Digit confusions: `8`/`B`, `0`/`O`, `1`/`I`, `5`/`S`. The checksum warning catches many of these
  but not all — two transposed digits can still pass Luhn.

Always read back what it filled in before saving. The **Undo** action and the fact that every
field stays editable are there because this will sometimes be wrong.

### 4.12 The APK is large, and camera permission is requested

Bundling the OCR model rather than downloading it means the app works with no network and no Google
Play Services, but it costs roughly 12 MB: ~14.5 MB installed on arm64 against ~2 MB before. Builds
are split per ABI so a device only downloads its own slice; `x86`/`x86_64` are not built at all.

The app now requests `CAMERA`. It is asked for the first time you open the scanner, never at
launch, and the app is fully usable if you decline. It is declared `required="false"`, so a device
with no camera can still install and use everything except scanning.

---

## 5. System requirements

| Requirement | Value |
|---|---|
| **Operating system** | Android **8.0 (API level 26)** or newer |
| **Target Android** | Android 14 (API 34) |
| **CPU architectures** | `arm64-v8a` or `armeabi-v7a`. The bundled OCR engine ships native libraries, so `x86`/`x86_64` are deliberately not built — an x86_64 emulator needs them added back in `app/build.gradle.kts` |
| **RAM** | No special requirement; a few tens of MB while running |
| **Storage** | ~15 MB installed on arm64-v8a, ~11 MB on armeabi-v7a. Almost all of it is the bundled on-device OCR model |
| **Screen** | Portrait phones. Tablets work but the layout is phone-first |
| **Screen lock** | Recommended — required for the first (biometric / device-credential) lock layer to be effective. The app runs without one but degrades to in-app password only |
| **Biometric hardware** | Optional — a device credential (PIN / pattern / password) satisfies the same lock layer |
| **Network** | None. The app declares no `INTERNET` permission |
| **Play Services** | Not required — the OCR model is bundled, not downloaded |
| **Camera** | Optional. Needed only for card scanning; declared `required="false"` |

**Permissions requested:** `USE_BIOMETRIC`, `USE_FINGERPRINT`, and `CAMERA`. The camera is used only while the scan screen is open, and no frame is ever stored. No storage, contacts, location, or internet access — `INTERNET` and `ACCESS_NETWORK_STATE` are explicitly stripped from the merged manifest, because a transitive telemetry dependency of the OCR library would otherwise add them.

---

## 6. Building from source

If you want to build the app yourself instead of installing the released `.apk`:

**Prerequisites**

- **JDK 17** (Android Gradle Plugin 8.x requirement).
- **Android Studio** (any recent stable version) or the standalone Android SDK with `platform-tools` and Android 34 platform installed.

**Build**

```bash
git clone <this-repository-url>
cd Credit_Card_Viewer
./gradlew assembleRelease     # produces app/build/outputs/apk/release/app-release.apk
```

If you have no release keystore configured at `~/keys/keystore.properties`, the release build is produced **unsigned** (rather than silently falling back to the debug key). To produce a signed release, create that file with:

```properties
storeFile=/absolute/path/to/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

For a quick installable debug build:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Run tests**

```bash
./gradlew test
```

---

## 7. Security model at a glance

For a full spec see `REQUIREMENTS.md` in this repository. In short:

- **AES-256-GCM** for card number / expiry / CVV, with a fresh 12-byte random IV per encryption.
- **PBKDF2-HmacSHA256**, 100 000 iterations, 256-bit key, per-vault random salt stored in the local Room database.
- Password verification uses a **canary blob** — a fixed plaintext encrypted with the derived key, stored in the database. A wrong password fails the GCM authentication tag on decryption; the password itself is never stored in any form.
- Password changes are **atomic**: every card is re-wrapped with the new key and the new salt + canary are written inside a single Room `withTransaction`. A crash mid-change rolls back cleanly — either the old password still works and every card still decrypts, or the new password does.
- On lock / background / logout / reset, the in-memory key handle is nulled and the Android Keystore alias `cardvault_master_key` is deleted. The key is re-derived from the persisted salt on the next unlock.
- **Card scanning** runs entirely on-device, binds only the camera's preview and analysis paths (never a capture use case), writes no frame anywhere, logs nothing — a build-time gate enforces that — and never reads the CVV. The scan screen inherits `FLAG_SECURE` like every other screen.

---

## 8. Disclaimer

Card Vault is a personal project. It has not undergone an independent security audit. The author provides no warranty and accepts no liability for lost data or unauthorised access. Use it at your own risk, and always keep an independent record of your card details somewhere you can recover them from.
