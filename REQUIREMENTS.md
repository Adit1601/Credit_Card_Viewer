# Credit Card Vault — Requirements Document

## Overview

A personal Android app for securely storing credit and debit card information.
Fully offline, encrypted, and protected by two layers of security.
Built natively in Kotlin for Android.

---

## 1. Security Model

### 1.1 Layer 1 — Biometric / Device Credential
- On every app launch (or return from background), the user must authenticate using the phone's built-in security: fingerprint, face unlock, PIN, pattern, or password.
- Uses Android's `BiometricPrompt` API (AndroidX). Hosted in a dedicated `BiometricLockActivity`.
- Authenticator selection cascades: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` → `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` → `DEVICE_CREDENTIAL` alone, based on `BiometricManager.canAuthenticate()`.
- If the device has no screen lock configured, a blocking dialog warns the user and offers to open **Security Settings** or **Continue Anyway** (which proceeds to the in-app password only).
- If authentication fails or is cancelled, `finishAffinity()` closes the entire task.

### 1.2 Layer 2 — In-App Password
- The user sets a personal in-app password during first launch (onboarding). Minimum 4 characters.
- After passing biometric, the user must also enter this in-app password to access the app.
- The in-app password is used to derive the AES-256-GCM encryption key via PBKDF2WithHmacSHA256 (100k iterations, 256-bit).
- Verification uses a **canary** (not a hashed password): at onboarding a fixed plaintext (`"cardvault-canary-v1"`) is encrypted with the derived key and stored in the Room `metadata` table. To verify a password, the app re-derives the key and attempts to decrypt the canary — a wrong password fails the AES-GCM authentication tag, which is strictly stronger than a hash comparison. The password itself is never stored in any form.
- If the user forgets the in-app password, there is **no recovery**. The only option is to reset the app, which permanently wipes all stored card data.
- The user can change the in-app password from Settings. The change is fully atomic: every card is decrypted with the old key and re-encrypted with the newly derived key, and the salt + canary are rotated — all writes commit inside a single Room transaction. A process kill mid-change cannot leave the DB and the salt/canary describing different keys (see §2.4).

### 1.3 CVV Access
- CVV is never displayed automatically.
- To view or copy a CVV, the user must re-enter the in-app password, gated by `AppPasswordDialogFragment`.
- The re-auth cadence is configurable in Settings as **Per Action** (default — every CVV reveal or copy prompts) or **Per Session** (one successful CVV re-auth authorises CVV access for the rest of the current unlocked session, tracked by `SessionManager.cvvAuthorizedThisSession`).

### 1.4 General Security Constraints
- `FLAG_SECURE` is set on all activities (`MainActivity`, `OnboardingActivity`, `BiometricLockActivity`) — no screenshots, no app-switcher preview.
- Zero network permissions — the app is fully offline. `INTERNET` and `ACCESS_NETWORK_STATE` are explicitly stripped with `tools:node="remove"` because ML Kit's transitive telemetry dependency would otherwise contribute them.
- `CAMERA` is requested, and only for card scanning (§13). It is a runtime permission, asked for on first use of the scanner and never required to use the app. Frames are analysed in memory and never written anywhere; no capture use case is ever constructed. Declared `uses-feature ... required="false"` so a camera-less device can still install.
- Clipboard is automatically cleared 30 seconds after any sensitive field is copied. The clear only fires if the clipboard content still matches what was written (avoids stomping on something the user copied afterward).
- App locks immediately when sent to background (configurable in Settings, default: ON). On lock, the in-memory key handle is nulled and the Keystore alias is deleted.
- All activities set `android:excludeFromRecents="true"`; `MainActivity` uses `android:launchMode="singleTask"`.
- Backup is disabled three ways: `android:allowBackup="false"` in the manifest, plus `backup_rules.xml` and `data_extraction_rules.xml` that exclude the database, shared prefs, and files domain (belt-and-suspenders for forward API compatibility).

---

## 2. Data Storage & Encryption

### 2.1 Encryption
- Algorithm: **AES-256-GCM** (`AES/GCM/NoPadding`), 12-byte IV, 128-bit auth tag.
- Key derivation: **PBKDF2WithHmacSHA256**, 100,000 iterations, 256-bit key.
- A unique random **salt** (16 bytes, `SecureRandom`) is generated at onboarding and stored in the Room `metadata` table as Base64 under key `"salt"`.
- The derived key is imported into the **Android Keystore** under alias `cardvault_master_key` on unlock (`KeyProtection`: `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, `setRandomizedEncryptionRequired(true)`); on lock the alias is deleted.
- Each encrypted value stores its own random **IV** alongside the ciphertext, formatted as `Base64(iv[12] || ciphertext+tag)`. A fresh random IV per encryption is enforced by the Keystore.
- Fields encrypted at rest: Card Number, Expiry Date, CVV.
- Fields stored in plaintext: Nickname, Name on Card, Color, Network, Sort Order, Created At.

### 2.2 Storage layout

| Store | Contents |
|---|---|
| **Room** `cards` table | Card rows with encrypted PAN / Expiry / CVV plus plaintext metadata (see §2.3). |
| **Room** `metadata` table (schema v4) | Password salt (Base64) and canary blob (Base64 `iv‖ct`). Key–value shape: `key TEXT PRIMARY KEY, value TEXT`. |
| **EncryptedSharedPreferences** (`cardvault_secure_prefs`) | Non-secret routing / user prefs only: `onboarding_done`, `lock_on_background`, `cvv_reauth_mode`. |
| **Android Keystore** | Non-durable per-unlock master-key handle under alias `cardvault_master_key`. Re-installed from the persisted salt on every unlock. |

- No cloud sync, no backup to Google Drive (`android:allowBackup="false"` plus `backup_rules.xml` + `data_extraction_rules.xml`).
- The password is never persisted in any form.

### 2.3 Card Entity Fields (`cards` table)

| Field | Type | Encrypted |
|---|---|---|
| id | UUID String | No |
| nickname | String | No |
| nameOnCard | String | No |
| encryptedCardNumber | String | Yes |
| encryptedExpiry | String | Yes |
| encryptedCvv | String | Yes |
| colorHex | String | No |
| cardNetwork | Enum (VISA, MASTERCARD, AMEX, RUPAY, DISCOVER, DINERS, UNKNOWN) | No |
| sortOrder | Int | No |
| createdAt | Long (epoch ms) | No |

### 2.4 Atomicity of the password-change flow

Because salt, canary, and card ciphertext are three pieces of one crypto invariant — all three must refer to the same derived key or the vault becomes unreadable — they all live in the Room DB and rotate together:

```
db.withTransaction {
    cardDao.updateAll(rewrapped)                       // new-key ciphertext for every card
    metadata.putSaltAndCanary(newSaltB64, newCanary)   // new salt + new canary blob
}
```

Either all three writes commit atomically or none of them do. A process kill (OS memory reap, force-stop, battery-saver freeze) mid-transaction rolls back to the pre-change state — the old password unlocks and every card still decrypts. A kill *after* commit but *before* the Keystore alias re-install is also safe: `AppPasswordFragment.tryUnlock` re-derives the raw key from the persisted salt on every unlock and unconditionally re-installs the alias, so the Keystore is a per-unlock cache rather than durable state.

The canary is generated from the **raw PBKDF2-derived key** rather than the Keystore-wrapped `SecretKey` — key material is identical, and every read path (`verifyCanary`) already uses the raw derived key. This breaks the ordering dependency on `installMasterKey`, which is what allows canary generation to precede the transaction and the Keystore install to happen after it.

### 2.5 v3 → v4 upgrade path

Pre-v4 installs stored salt and canary in `EncryptedSharedPreferences`. `AppDatabase` ships `MIGRATION_3_4`, which creates the empty `metadata` table; the app then seeds it lazily. On the first `VaultMetadataRepository.getSalt()` or `getCanary()` call post-upgrade:

1. If the `metadata` table already has the row, return it (steady state).
2. Otherwise, read the value from prefs, insert both salt and canary into `metadata` inside `db.withTransaction`, then call `SecurePreferences.clearSeededSecrets()` (synchronous `commit()`) to remove them from prefs. Return the seeded value.

The seed is idempotent: once the row exists, prefs are never consulted again. Concurrent readers on the first unlock are safe because the seed itself runs inside a transaction — the second reader either sees the committed row or waits.

---

## 3. Card Network Auto-Detection

Card network is identified automatically and in real time as the user types the card number.
No manual selection is required.

### 3.1 Detection Rules (BIN Prefix Logic)

| Network | Prefix Rule |
|---|---|
| **Visa** | Starts with `4` |
| **Mastercard** | Starts with `51`–`55`, or `2221`–`2720` |
| **American Express** | Starts with `34` or `37` |
| **RuPay** | Starts with `60`, `6521`, or `6522` |
| **Discover** | Starts with `6011`, `622126`–`622925`, `644`–`649`, or `65` |
| **Diners Club** | Starts with `300`–`305`, `36`, or `38` |
| **Unknown** | Does not match any of the above |

### 3.2 Behavior
- As the user types the card number in the Add/Edit screen, the network logo on the live card preview updates in real time.
- Detection runs locally — no API call.
- The detected network is saved with the card and shown as a logo on the card tile.
- If the network is UNKNOWN, a generic card icon is shown.

---

## 4. Card Management

### 4.1 Limits
- Maximum **30 cards** can be stored.
- When 30 cards are stored, the Add Card button is disabled and a message is shown.

### 4.2 Add Card
- Accessed via a button in the **top-left corner** of the main screen toolbar.
- Form fields:
  - **Nickname** — user-defined label (e.g. "HDFC Rewards Platinum"). Required.
  - **Name on Card** — as printed on the card. Required.
  - **Card Number** — 13–19 digits. Auto-formatted with spaces every 4 digits. Required.
  - **Expiry Date** — auto-formatted as MM/YY. Required.
  - **CVV** — 3–4 digits, `inputType="numberPassword"`. Required.
  - **Card Color** — user picks from a palette of ~12 colors. Used as background of card tile.
- Network logo is auto-detected and shown live on the card preview as the user types.
- `importantForAutofill="no"` is set on the PAN and CVV inputs to prevent autofill frameworks from persisting card values.
- On Save: card number, expiry, and CVV are encrypted before being stored in Room.
- The form can optionally be pre-filled by scanning the physical card (§13). Scanning never fills the CVV, and every scanned value remains editable.

### 4.3 Edit Card
- Any saved card can be edited (all fields).
- Accessed via a long-press on the card tile or an edit icon on the card detail screen.
- On Save: re-encrypts updated sensitive fields.

### 4.4 Delete Card
- Cards can be deleted from the card detail screen.
- A confirmation dialog is shown before deletion.

### 4.5 Reorder Cards
- Cards on the main screen can be reordered via **drag-and-drop**.
- Sort order is persisted to the database.

---

## 5. Main Screen

### 5.1 Card List
- Cards are displayed as **visual card tiles** styled like physical cards.
- Tiles are arranged in a scrollable vertical list (one card per row, full width).
- Each tile displays:
  - Nickname (top-left)
  - Card network logo (top-right)
  - Card number — **masked** by default (`**** **** **** 1234`)
  - Name on Card
  - Expiry Date
  - Card background in the user-selected color
- A **Bank filter** `ChipGroup` above the list restricts the visible cards to a chosen bank (derived from nickname prefix); "All" is always selected by default.
- A toolbar `SearchView` filters the list by nickname / name substring, composed with the bank filter via a `MediatorLiveData`.

### 5.2 Interactions on Main Screen
- **Tap card** → open Card Detail screen.
- **Long-press card** → enter drag-to-reorder mode, or show Edit/Delete options.
- **Add Card button** (top-left toolbar) → open Add Card screen.
- **Reorder mode**: menu toggle turns the toolbar into a done-only affordance; `ItemTouchHelper` supplies drag handles and persists the new `sortOrder` on drop.

### 5.3 Empty State
- When no cards are added, a friendly illustration and "Add your first card" message is shown.

---

## 6. Card Detail Screen

### 6.1 Display
- Large card tile at the top (same visual style as main screen tile).
- Below the tile, all fields are listed with labels and copy icons.

### 6.2 Field Visibility & Interaction

| Field | Default State | Reveal Action | Copy Action |
|---|---|---|---|
| Nickname | Fully visible | — | Tap copy icon |
| Name on Card | Fully visible | — | Tap copy icon |
| Card Number | Masked (`**** **** **** 1234`) | Tap to toggle reveal/hide | Tap copy icon (copies full number) |
| Expiry Date | Fully visible | — | Tap copy icon |
| CVV | Hidden (`***`) | Tap "Show CVV" → enter in-app password | Tap copy icon → enter in-app password |

### 6.3 CVV Reveal Flow
1. User taps "Show CVV" button.
2. App presents in-app password entry dialog (`AppPasswordDialogFragment`).
3. On correct password → CVV is revealed in plain text.
4. CVV is hidden again when the user leaves the screen or the app is backgrounded.

### 6.4 Clipboard Behavior
- Copying any sensitive field (card number, CVV) triggers a silent notification: "Clipboard will be cleared in 30 seconds."
- After 30 seconds, the clipboard is automatically cleared (only if the current clipboard content still matches what was written).
- Copying CVV requires in-app password entry first.

---

## 7. Onboarding Flow

Triggered only on first launch (`onboarding_done` is false in `SecurePreferences`).

1. Welcome screen with brief app description.
2. Set In-App Password screen — enter password + confirm password. Minimum 4 characters.
3. A random 16-byte salt is generated (`SecureRandom`).
4. The AES-256 key is derived from password + salt via PBKDF2 (100k iterations).
5. A verification canary (fixed plaintext `"cardvault-canary-v1"` encrypted with the raw derived key) is computed.
6. Salt + canary are written in a single `db.withTransaction { metadata.putSaltAndCanary(...) }` so the pair commits atomically.
7. The derived key is imported into the Android Keystore under alias `cardvault_master_key`, `onboarding_done` is flagged in prefs, and the Keystore-backed handle is handed to `SessionManager`. A crash between transaction commit and Keystore install is fully recoverable — the next unlock re-derives the raw key from the persisted salt and reinstalls the alias.
8. Navigate to main screen (empty state).

---

## 8. Settings Screen

| Setting | Description |
|---|---|
| Change In-App Password | Verify current password (via canary) → enter new password → confirm new password → decrypt every card with the old key and re-encrypt with a freshly-derived new key → **commit card ciphertext + new salt + new canary in one `db.withTransaction`** → rotate the Keystore alias and the in-memory `SessionManager` handle. See §2.4 for atomicity guarantees. |
| Lock on Background | Toggle (default ON). When ON, the in-memory key is nulled and the Keystore alias deleted on backgrounding; app requires full re-authentication when returning from background |
| CVV Re-Auth Mode | Radio between **Per Action** (default — every CVV reveal/copy prompts) and **Per Session** (one successful CVV re-auth authorises CVV access for the rest of the current unlocked session) |
| Reset App | Two chained confirmation dialogs → on final confirm: `AppDatabase.wipe(ctx)` (deletes the DB file, taking the `metadata` table with it), `SecurePreferences.clear()`, `SessionManager.lock()` → restart onboarding |
| App Version | Displays current `versionName` from `PackageManager` (non-interactive) |

---

## 9. Non-Functional Requirements

| Area | Requirement |
|---|---|
| Language | Kotlin (JVM target 17) |
| Minimum SDK | Android 8.0 (API level 26) |
| Target SDK | Android 14 (API level 34) |
| Architecture | MVVM with Repository pattern |
| UI Framework | XML Views with ViewBinding + Material3 (no Jetpack Compose) |
| Theme | Dark-only (`Theme.Material3.Dark.NoActionBar`) |
| Database | Room v4 (SQLite) via KSP (not kapt); two entities (`CardEntity`, `MetadataEntry`), one explicit migration (`MIGRATION_3_4`), no destructive fallback |
| Encryption | AES-256-GCM + PBKDF2 via Android Keystore; canary-based password verification; atomic password rotation via Room `withTransaction` |
| Biometrics | AndroidX BiometricPrompt API |
| Network | None — zero network permissions |
| Camera | `CAMERA` (runtime, optional) for card scanning only; `Preview` + `ImageAnalysis` use cases only, no capture, no frame ever persisted |
| OCR | ML Kit text recognition v2, **bundled** model — fully on-device, no Play Services, no model download |
| APK size | ABI-split: ~14.5 MiB (arm64-v8a), ~10.4 MiB (armeabi-v7a). x86/x86_64 excluded. The bundled OCR model accounts for almost all of it |
| Backup | `android:allowBackup="false"` + `backup_rules.xml` + `data_extraction_rules.xml` |
| Screenshots | Blocked via `FLAG_SECURE` on all activities |
| Max Cards | 30 |

---

## 10. Permissions

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.USE_FINGERPRINT" />

<!-- Card scanning only (§13). Runtime-requested on first use of the scanner. -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

<!-- Contributed by ML Kit's transitive telemetry dependency; removed on purpose. -->
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

No internet, storage, contacts, or location permissions. The camera is used only while the scan
screen is open, and no frame is ever stored — see §13.3.

Verify against a built APK rather than trusting the source manifest:

```bash
aapt2 dump permissions app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

---

## 11. Implementation Steps

### Step 1 — Project Setup
- Create new Android project in Android Studio: Kotlin, Empty Views Activity, min SDK 26, target SDK 34.
- Enable ViewBinding in `buildFeatures`; JVM target 17.
- Apply plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `com.google.devtools.ksp`.
- Add Gradle dependencies (managed via `libs.versions.toml`):
  - `androidx.biometric:biometric`
  - `androidx.security:security-crypto`
  - `androidx.room:room-runtime` + `room-ktx` + `room-compiler` (via **KSP**, not kapt)
  - `androidx.navigation:navigation-fragment-ktx` + `navigation-ui-ktx`
  - `androidx.lifecycle:lifecycle-viewmodel` + `lifecycle-livedata` + `lifecycle-runtime`
  - `androidx.fragment:fragment-ktx` + `androidx.activity:activity-ktx`
  - `com.google.android.material:material` (Material3)
  - `androidx.recyclerview:recyclerview`, `androidx.constraintlayout:constraintlayout`
  - (No Gson — nothing serializes to JSON.)
- Set `FLAG_SECURE` in every Activity (`MainActivity`, `OnboardingActivity`, `BiometricLockActivity`).
- Configure `AndroidManifest.xml`: biometric permissions only, `allowBackup="false"`, `excludeFromRecents="true"` on every activity, `MainActivity` uses `launchMode="singleTask"`, `data_extraction_rules.xml` + `backup_rules.xml` referenced from the `<application>` element.

### Step 2 — Data Layer
- Define `CardEntity` Room entity with all fields listed in Section 2.3, plus a `Converters` `@TypeConverter` for the `CardNetwork` enum (persisted as the enum name).
- Define `MetadataEntry(@PrimaryKey key: String, value: String)` and `MetadataDao(get(key), put(entry))` for the `metadata` table.
- Create `AppDatabase` singleton (Room) at `version = 4`, entities `[CardEntity, MetadataEntry]`, with `Converters` registered. Register `MIGRATION_3_4` that creates the `metadata` table (`key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL`). Do not allow destructive migration.
- Write `CardRepository` wrapping `CardDao`; write `VaultMetadataRepository` (see below) wrapping `MetadataDao`.
- Write `CryptoManager` (object):
  - `randomSalt(): ByteArray` — 16 random bytes from `SecureRandom`.
  - `deriveKey(password: CharArray, salt): SecretKey` — PBKDF2WithHmacSHA256, 100k iterations, 256-bit; the char array is zeroed inside `deriveKey`.
  - `installMasterKey(rawKey): SecretKey` — imports the derived key into Android Keystore under `cardvault_master_key` and returns the Keystore-backed handle.
  - `getMasterKey(): SecretKey?` / `removeMasterKey()` — Keystore accessors.
  - `encrypt(plainText, key): String` — AES-256-GCM, returns `Base64(iv[12] || ciphertext+tag)`.
  - `decrypt(cipherText, key): String` — splits IV and ciphertext, decrypts.
  - `createCanary(rawKey): String` — encrypts the fixed canary plaintext with the raw derived key. **Not** the Keystore-wrapped key: same key material, but keeping the API on `rawKey` removes any ordering dependency between the atomic DB commit and the Keystore install.
  - `verifyCanary(rawKey, blob): Boolean` — true iff the blob decrypts to the canary plaintext (GCM auth-tag success = correct password).
- Write `VaultMetadataRepository(db, dao, prefs)`:
  - `suspend fun getSalt(): String?` / `suspend fun getCanary(): String?` — delegate to `readOrSeed(key)`.
  - `suspend fun putSaltAndCanary(saltB64, canaryB64)` — writes both rows via the DAO; callers invoke this **inside** a `db.withTransaction` block so it composes with card-ciphertext writes as one atomic step.
  - `readOrSeed(key)` — return the DB row if present; otherwise read the pre-v4 values via `prefs.readSaltForSeed()` / `prefs.readCanaryForSeed()`, insert both into `metadata` inside `db.withTransaction`, and call `prefs.clearSeededSecrets()` to remove them from prefs. Idempotent.
- Write `SecurePreferences` wrapper over `EncryptedSharedPreferences`. Persists only routing / user prefs: `onboarding_done`, `lock_on_background`, `cvv_reauth_mode`. Exposes read-only seed accessors for the v3→v4 upgrade (`readSaltForSeed()`, `readCanaryForSeed()`, `clearSeededSecrets()` using synchronous `commit()`), plus `clear()`. Also defines `CvvReauthMode { PER_SESSION, PER_ACTION }`.

### Step 3 — Card Network Detection
- Write `CardNetworkDetector.kt`:
  - Single `fun detect(cardNumber: String): CardNetwork` function.
  - Pure string prefix matching using a `when` expression.
  - Returns `CardNetwork` enum: VISA, MASTERCARD, AMEX, RUPAY, DISCOVER, DINERS, UNKNOWN.
- Store network logos as vector drawables in `res/drawable/`.
- Write unit tests for all prefix rules.

### Step 4 — Onboarding (First Launch)
- `OnboardingActivity` shown if `onboarding_done` is false in `SecurePreferences`.
- `WelcomeFragment` → `SetPasswordFragment`.
- `SetPasswordFragment.provision(password)`:
  1. Generate 16-byte salt (`CryptoManager.randomSalt()`).
  2. Derive `rawKey = CryptoManager.deriveKey(password.toCharArray(), salt)`.
  3. Compute `canary = CryptoManager.createCanary(rawKey)`.
  4. Write `salt` + `canary` inside `db.withTransaction { metadata.putSaltAndCanary(...) }`.
  5. Install `rawKey` into the Keystore, flip `onboarding_done = true`, hand the Keystore-backed key to `SessionManager`.
- Start `MainActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`.

### Step 5 — Biometric Lock Screen
- `BiometricLockActivity`: shown on every cold launch and background resume.
- Use `BiometricPrompt` with `BiometricManager.canAuthenticate()` check and the authenticator cascade from §1.1.
- On success → present `AppPasswordFragment`.
- On failure/cancel → `finishAffinity()`.

### Step 6 — In-App Password Screen
- `AppPasswordFragment.tryUnlock(password)`:
  1. Read `salt` and `canary` via `VaultMetadataRepository` (this triggers the v3→v4 seed on first post-upgrade unlock).
  2. Re-derive the raw key via PBKDF2.
  3. Call `CryptoManager.verifyCanary(rawKey, canary)`. Wrong password fails the GCM authentication tag and returns false without any Keystore interaction.
  4. On correct password: `CryptoManager.installMasterKey(rawKey)`, hand the Keystore-backed handle to `SessionManager`, navigate to Home.
- "Forgot password" → confirmation dialog explaining data loss → `AppDatabase.wipe(ctx)` + `SecurePreferences(ctx).clear()` + `SessionManager.lock()` → restart onboarding.
- The same verify path is used mid-session by `AppPasswordDialogFragment` to gate CVV reveal/copy.

### Step 7 — Session Manager
- `SessionManager` object (scoped to process lifetime):
  - Holds the Keystore-backed `SecretKey` handle in memory while the app is unlocked (`@Volatile` for cross-thread visibility, `@Synchronized` on lock/unlock).
  - `cvvAuthorizedThisSession: Boolean` — supports `PER_SESSION` CVV re-auth mode.
  - `setMasterKey(key)` / `getMasterKey()` / `requireKey()` / `isUnlocked()`.
  - `lock()` — nulls the in-memory handle, calls `CryptoManager.removeMasterKey()` to delete the Keystore alias, resets `cvvAuthorizedThisSession`.
- `MainActivity.onStop()`: call `SessionManager.lock()` if lock-on-background is enabled.
- `MainActivity.onResume()`: if the session is not unlocked, relaunch `BiometricLockActivity`.

### Step 8 — Main Screen (Card List)
- `HomeFragment` with `RecyclerView`.
- `CardTileAdapter`: renders card tiles using `MaterialCardView` with a custom XML layout (nickname, name, masked card number, expiry, network logo, color background).
- Bank filter `ChipGroup` (single-select, `selectionRequired=true`) plus toolbar `SearchView`, composed via a `MediatorLiveData` in `CardViewModel`.
- `ItemTouchHelper` callback for drag-to-reorder in reorder mode; on drop, persist the new `sortOrder`.
- Add Card button in toolbar top-left; enter-reorder-mode / done-reorder toggle in the toolbar menu.
- Observe `CardViewModel.filtered: LiveData<List<CardDisplay>>`. Empty state view toggled based on list size.

### Step 9 — Add / Edit Card Screen
- `AddEditCardFragment` (shared for add and edit, distinguished by argument).
- Card number `EditText`:
  - `TextWatcher` for auto-formatting (space every 4 digits).
  - On each change: call `CardNetworkDetector.detect()` → update network logo on live preview.
- Expiry `EditText`: `TextWatcher` auto-inserts `/` after MM.
- CVV `EditText`: `inputType="numberPassword"`.
- Both PAN and CVV inputs set `importantForAutofill="no"`.
- Color picker: horizontal `RecyclerView` of colored circles.
- Live card tile preview at top of screen that updates as user types.
- On Save: validate all fields → encrypt card number, expiry, CVV via `CryptoManager` (uses the Keystore-backed handle from `SessionManager`) → save to Room.

### Step 10 — Card Detail Screen
- `CardDetailFragment`:
  - Large card tile at top.
  - Listed fields below with copy icons.
  - Card number: tap to toggle mask/unmask.
  - CVV: "Show CVV" button → `AppPasswordDialogFragment` → on success, reveal CVV.
  - Copy icons: for CVV, show `AppPasswordDialogFragment` first (or reuse the session grant when `PER_SESSION` mode is on).
  - Clipboard auto-clear via `Handler.postDelayed(30_000)` with a hash-match guard so it only clears if the clipboard still holds what we wrote.
- Edit button → navigate to `AddEditCardFragment` with card ID.
- Delete button → confirm dialog → delete from Room → navigate back.

### Step 11 — Settings Screen
- `SettingsFragment` + `SettingsViewModel`:
  - **Change Password (atomic)**:
    1. Read `saltB64`, `canary` via `VaultMetadataRepository`.
    2. Derive `oldKey`; run `verifyCanary(oldKey, canary)` — on false return `WrongOldPassword`.
    3. Generate `newSalt`; derive `newRawKey`; compute `newCanary = CryptoManager.createCanary(newRawKey)`.
    4. Load every card, decrypt each field with `oldKey`, re-encrypt with `newRawKey`, build `rewrapped` list in memory.
    5. `db.withTransaction { cardDao.updateAll(rewrapped); metadata.putSaltAndCanary(base64Encode(newSalt), newCanary) }` — one atomic step.
    6. After commit (best-effort): `installMasterKey(newRawKey)`, `SessionManager.setMasterKey(newKeystoreKey)`. A crash here is recoverable — `tryUnlock` re-installs on next unlock.
  - Lock on Background toggle: saved to `SecurePreferences`.
  - CVV Re-Auth Mode radio: `PER_ACTION` (default) or `PER_SESSION`; saved to `SecurePreferences`.
  - Reset App: two chained `AlertDialog` confirmations → `AppDatabase.wipe(ctx)` → `SecurePreferences.clear()` → `SessionManager.lock()` → restart via `OnboardingActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`.
  - App version read from `PackageManager` (non-interactive display).

### Step 12 — Polish & Edge Cases
- Enforce 30-card limit: disable Add button + show snackbar.
- Input validation on Add/Edit: required fields, card number length (13–19 digits), expiry format, CVV length.
- Handle biometric hardware not available → skip to in-app password directly.
- Handle device with no screen lock set → warn user and prompt to set one.
- Add confirm dialog before leaving Add/Edit screen with unsaved changes.
- Accessibility: content descriptions on all icon buttons.
- App icon and name: "Card Vault".

---

## 12. Project File Structure (Actual)

Package root: `com.cardvault`

```
app/src/main/java/com/cardvault/
├── CardVaultApplication.kt
├── MainActivity.kt
├── crypto/
│   └── CryptoManager.kt
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt                   # v=4, entities [CardEntity, MetadataEntry], MIGRATION_3_4
│   │   ├── CardDao.kt
│   │   ├── CardEntity.kt
│   │   ├── Converters.kt
│   │   ├── MetadataDao.kt                   # NEW (v4) — key/value accessors for metadata table
│   │   └── MetadataEntry.kt                 # NEW (v4) — @Entity(tableName="metadata")
│   ├── prefs/
│   │   └── SecurePreferences.kt             # holds onboarding_done, lock_on_background, cvv_reauth_mode;
│   │                                        # salt/canary demoted to internal seed accessors (v3→v4 only)
│   └── repository/
│       ├── CardRepository.kt
│       └── VaultMetadataRepository.kt       # NEW — typed salt/canary API + lazy prefs→DB seed
├── scan/                                    # pure JVM — no android/androidx imports, enforced by
│   │                                        # ./gradlew :app:verifyScanInvariants
│   ├── BankMatcher.kt                       # fuzzy bank-name match (Levenshtein) against a vocabulary
│   ├── CardScanParser.kt                    # OcrFrame -> ScanCandidate (PAN / expiry / name / bank)
│   ├── NumericNormalizer.kt                 # OCR digit confusions (O->0, I/l->1, S->5 ...)
│   ├── OcrModels.kt                         # OcrLine, OcrFrame, ScanCandidate
│   └── ScanAccumulator.kt                   # multi-frame voting; locks a field at 3 agreeing reads
├── security/
│   ├── CardNetwork.kt                       # enum: VISA/MASTERCARD/AMEX/RUPAY/DISCOVER/DINERS/UNKNOWN
│   ├── CardNetworkDetector.kt
│   └── SessionManager.kt
├── ui/
│   ├── addedit/
│   │   ├── AddEditCardFragment.kt           # applyScan() fills the form; never touches cvvInput
│   │   ├── AddEditCardViewModel.kt
│   │   ├── BankSuggestions.kt               # static bank list + banks already in the vault
│   │   ├── ColorPalette.kt
│   │   └── ColorPaletteAdapter.kt
│   ├── auth/
│   │   ├── AppPasswordDialogFragment.kt     # mid-session CVV re-auth; reads via VaultMetadataRepository
│   │   ├── AppPasswordFragment.kt           # unlock; reads via VaultMetadataRepository
│   │   ├── BiometricLockActivity.kt
│   │   └── CvvBiometricPrompt.kt
│   ├── detail/
│   │   ├── CardDetailFragment.kt
│   │   └── CardDetailViewModel.kt
│   ├── home/
│   │   ├── CardDisplay.kt
│   │   ├── CardTileAdapter.kt
│   │   ├── CardViewModel.kt
│   │   └── HomeFragment.kt
│   ├── scan/                                # Android side of scanning (CameraX + ML Kit)
│   │   ├── CardScanFragment.kt              # camera, overlay, timeout, torch, result delivery
│   │   ├── CardScanViewModel.kt             # owns the accumulator across config changes
│   │   ├── MlKitTextSource.kt               # ImageProxy -> OcrFrame; off-main-thread callbacks
│   │   └── ScanResultBridge.kt              # 4-key Bundle: pan, expiry, name, bank (no CVV)
│   ├── onboarding/
│   │   ├── OnboardingActivity.kt
│   │   ├── SetPasswordFragment.kt           # provision() writes salt/canary via withTransaction
│   │   └── WelcomeFragment.kt
│   └── settings/
│       ├── SettingsFragment.kt
│       └── SettingsViewModel.kt             # runChange() commits cards+salt+canary in one withTransaction
└── util/
    ├── CardFormatting.kt
    ├── ClipboardUtil.kt
    ├── ExpiryUtil.kt
    ├── Luhn.kt                              # mod-10 checksum; soft warning only, never a block
    └── PasswordChars.kt

app/src/main/res/
├── drawable/                                # ic_visa, ic_mastercard, ic_amex, ic_rupay,
│                                            # ic_discover, ic_diners, ic_card_generic, etc.
├── layout/                                  # activity + fragment + item + dialog layouts
├── menu/
│   └── menu_home.xml
├── navigation/
│   ├── main_nav.xml                         # Home → AddEdit / Detail / Settings; Detail → AddEdit
│   └── onboarding_nav.xml                   # Welcome → SetPassword
├── values/                                  # colors, strings, themes (Theme.Material3.Dark.NoActionBar)
└── xml/
    ├── backup_rules.xml
    └── data_extraction_rules.xml

app/src/test/java/com/cardvault/
└── security/
    └── CardNetworkDetectorTest.kt           # BIN prefix coverage incl. Discover/RuPay boundary
```

---

*Document version: 1.3 — Last updated: 2026-08-29 (deep review: full feature inventory, bug catalogue, limitations and assumptions added in §14–§17).*
*Notable changes from v1.2: §14 Complete Feature Inventory added; §15 Bug Catalogue added; §16 Limitations added; §17 Assumptions added.*
*Notable changes from v1.1 → v1.2: Room schema bumped to v4 with a new `metadata` table for salt + canary; password change is now atomic via a single `db.withTransaction` that spans card ciphertext + salt + canary; canary is generated from the raw PBKDF2 key rather than the Keystore-wrapped handle (breaks the Keystore ordering dependency); `SecurePreferences` no longer stores crypto material and exposes only seed accessors for the v3→v4 upgrade; new files `MetadataEntry.kt`, `MetadataDao.kt`, `VaultMetadataRepository.kt`; `MIGRATION_3_4` shipped; explicit lazy prefs→DB seed documented.*
*Changes from v1.0 → v1.1: password verification is canary-based rather than SHA-256 hash comparison; Room compiler runs via KSP not kapt; Gson dropped (unused); Settings gains a CVV Re-Auth Mode toggle; Reset flow uses two chained confirmations; file structure updated to match `com.cardvault` package layout.*

---

## 13. Card Scanning (OCR)

Optional shortcut for populating the Add Card form from the physical card, using the device
camera. It is a convenience over §4.2, never a replacement: every field it fills stays editable,
and the form can always be reached without it.

### 13.1 Scope — what is and is not read

| Field | Read | Notes |
|---|---|---|
| Card number (PAN) | Yes | 13–19 digits, Luhn-checked as a soft warning |
| Expiry | Yes | `MM/YY`; `MM/YYYY` on the card is normalised to two-digit year |
| Name on card | Yes | Embossed or printed cardholder line |
| Issuing bank | Yes | Fuzzy-matched against a known-bank vocabulary plus banks already in the vault |
| **CVV / CVC / security code** | **Never** | See §13.2 |

### 13.2 CVV is never scanned

The scanner has no CVV code path at all — this is a structural guarantee, not a filter:

- `ScanCandidate` (the only type crossing from the parser to the UI) has four fields, none of them
  a security code. There is nothing for a CVV to be carried in.
- `ScanResultBridge` serialises exactly those four keys.
- `AddEditCardFragment.applyScan()` contains no reference to `cvvInput`. It focuses the field so
  the user types the code themselves.
- Only the front of a card is ever framed; the printed CVV is on the reverse of most cards.

The user must therefore always type the CVV by hand, which is intentional: the CVV is the one
value in the vault that is worthless to an attacker without the PAN, and pairing them
automatically from a single camera pass adds risk for a couple of saved keystrokes.

### 13.3 Privacy and persistence

- Only `Preview` and `ImageAnalysis` CameraX use cases are ever bound. `ImageCapture` and
  `VideoCapture` are never constructed, so no code path exists that could write a frame.
- Frames live in a CameraX buffer, are handed to ML Kit, and are closed. Nothing is written to
  disk, to `MediaStore`, or to the app's own storage.
- OCR runs fully on-device via **bundled** ML Kit text recognition — the model ships inside the
  APK. There is no model download and no Google Play Services dependency.
- The app still declares **no network permission**. `INTERNET` and `ACCESS_NETWORK_STATE`, which
  ML Kit's transitive telemetry dependency would otherwise contribute, are stripped with
  `tools:node="remove"` in the manifest.
- Nothing in the scan path logs. A build-time gate (`./gradlew :app:verifyScanInvariants`) fails
  the build on any `Log.`/`println`/`System.out` under `com/cardvault/scan/` or
  `com/cardvault/ui/scan/`, and on any reference to a capture use case or file sink.
- `FLAG_SECURE` is inherited from `MainActivity`, so the live preview is excluded from screenshots
  and the recents thumbnail like every other screen.

### 13.4 Entry points

- **Home → add button → "Scan card"** in a bottom-sheet chooser (the other option is
  "Enter manually"). The 30-card cap is checked *before* the chooser appears.
- **Add/Edit form → camera icon in the card-number field.** Applying a scan here preserves
  whatever the user had already typed in fields the scan could not read.

### 13.5 Multi-frame voting

A single OCR frame is not trusted. `ScanAccumulator` requires **3 agreeing reads** of a value
before it locks that field, so a one-frame misread is outvoted rather than accepted. Frames are
analysed at ~4/s. The scan finishes automatically once **both** the PAN and the expiry have
locked; name and bank are best-effort and never block completion.

### 13.6 Failure handling

Every failure state keeps **"Enter manually"** on screen:

| State | Behaviour |
|---|---|
| Permission not yet granted | System prompt on first entry only (never re-prompted after a rotation) |
| Permission denied | Panel with "Try again" |
| Permission permanently denied | Panel with "Open settings"; re-checked on return |
| No camera hardware | Explanatory panel, no action button (nothing the user can do) |
| Camera failed to open | Panel with "Try again" |
| 25 s with nothing locked | Camera released, panel with "Try again"; accumulated votes are kept |
| PAN locked but scan stopped | "Use what was found" fills in the partial result |

The scanner never auto-navigates with a partial result — a surprise jump to a half-filled form
while the user is still holding the card up is worse than a panel that explains what happened.

### 13.7 Correcting a scan

- Applying a scan raises a Snackbar with an **Undo** action that restores the form exactly as it
  was before the scan. This covers the worst case: the user had typed the number correctly, then
  scanned to fill in the rest, and OCR replaced good input with a misread.
- Luhn is evaluated immediately on an applied PAN and surfaced as an amber helper warning, because
  a single transposed digit is the likeliest way a scan goes wrong. It is a warning, never a block
  (§4.2's soft-gate rule applies unchanged).

### 13.8 Accessibility

- Each checklist field announces itself to TalkBack once, as it locks, so a user who cannot see
  the ticks knows the scan is progressing and when to stop holding the card up.
- Checklist labels and the fallback panel both survive large system font scales (the panel
  scrolls rather than clipping its action button).

---

## 14. Complete Feature Inventory

This section is the authoritative single-place list of every user-facing and system feature that
exists in the codebase as of v1.3.

### 14.1 Security Features

| Feature | Implementation |
|---|---|
| Two-layer authentication | Layer 1: `BiometricPrompt` (STRONG → WEAK → DEVICE_CREDENTIAL cascade). Layer 2: in-app password verified via AES-GCM canary. |
| Biometric shortcut for CVV | `CvvBiometricPrompt` — BIOMETRIC_STRONG only (no DEVICE_CREDENTIAL), negative button falls through to password dialog. |
| CVV re-auth modes | PER_ACTION (default) and PER_SESSION; stored in `SecurePreferences.cvv_reauth_mode`. |
| AES-256-GCM encryption | Per-field: PAN, Expiry, CVV stored as `Base64(iv[12] \|\| ciphertext+tag)`. |
| PBKDF2 key derivation | PBKDF2WithHmacSHA256, 100 000 iterations, 256-bit key, 16-byte `SecureRandom` salt. |
| Canary-based password verification | Encrypts fixed plaintext `"cardvault-canary-v1"` at onboarding; decrypt success = correct password. Never stores the password. |
| Android Keystore integration | Derived raw key imported into Keystore under alias `cardvault_master_key` on unlock; alias deleted on lock. |
| Atomic password rotation | `db.withTransaction { updateAll(rewrapped); putSaltAndCanary(...) }` — all-or-nothing. |
| Session manager with mutex | `SessionManager.withWriteLock` (coroutine `Mutex`) serialises save operations against password rotation. |
| FLAG_SECURE on all activities | `MainActivity`, `OnboardingActivity`, `BiometricLockActivity` — no screenshots, no Recents thumbnail. |
| Lock on background | `MainActivity.onStop()` calls `SessionManager.lock()` when `lock_on_background = true` (default). |
| Clipboard auto-clear | 30-second `Handler.postDelayed`; only clears if clipboard still holds what was written. |
| No network permissions | `INTERNET` and `ACCESS_NETWORK_STATE` stripped with `tools:node="remove"`. |
| No backup | `allowBackup="false"` + `backup_rules.xml` + `data_extraction_rules.xml` (all three domains excluded). |
| RTL disabled | `android:supportsRtl="false"` — consistent card layout, no mirroring. |
| Password character hardening | `readCharsSecurely()` / `clearCharsSecurely()` in `PasswordChars.kt` — password never allocated as a Kotlin `String`. |
| Sensitive clipboard marking | `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+ suppresses the system copy preview toast. |

### 14.2 Card Management Features

| Feature | Implementation |
|---|---|
| Add card (manual) | `AddEditCardFragment` (editingCardId = null): nickname, name, PAN, expiry, CVV, color, card type, issuing bank. |
| Edit card | Same fragment (editingCardId set). All fields editable. Re-encrypts on save. |
| Delete card | From detail screen or long-press sheet with confirmation dialog. |
| Duplicate-safe edit | `repo.getById(editingCardId)` re-fetches before update; returns `NotFound` if row deleted between load and save. |
| 30-card limit | Checked in `HomeFragment.onAddCardClicked()` against unfiltered `allCards`. Snackbar when reached. |
| Auto-format PAN | `TextWatcher` in `AddEditCardFragment` — groups with spaces every 4 digits, strips non-digits. |
| Auto-format expiry | `TextWatcher` inserts `/` after `MM`. |
| Luhn soft check | `Luhn.isValid()` evaluated on save and on scan-apply. Dialog offers "Save anyway" / "Let me check". Never a hard block. |
| Past-expiry validation | `isExpiryInPast()` blocks saving an expired card. |
| Card type badge | `DEBIT` / `CREDIT` / `PREPAID` pill on tile; `UNKNOWN` (no pick) = no badge. |
| Issuing bank field | Optional free-text; drives home filter chips; autocomplete from `BankSuggestions` (18 seed banks + user's own). |
| Live card preview | `AddEditCardFragment` — card tile at top updates in real time as the user types. |
| Unsaved-changes guard | `AlertDialog` shown when navigating away from a dirty form. |
| Color picker | `ColorPaletteAdapter` — 12-color horizontal `RecyclerView`; default = `#2A3140` (slate). |
| Drag-to-reorder | `ItemTouchHelper.SimpleCallback` (handle-initiated); persists `sortOrder` to Room on drop. Blocked when search or bank filter is active. |
| Sort order | `sortOrder ASC, createdAt ASC` — ties broken by insertion time. |

### 14.3 Home Screen Features

| Feature | Implementation |
|---|---|
| Card tile list | `RecyclerView` + `CardTileAdapter` (`ListAdapter` + `DiffUtil`). |
| Masked PAN on tile | Last 4 visible, rest as `•`; chunked into groups. |
| Network logo on tile | `CardNetwork` enum with `@DrawableRes logoRes`. |
| Expiry-soon badge | Amber pill "Expires soon" — `ExpiryUtil.isExpiringSoon()`, within 2 months (inclusive of expired). |
| Card type badge on tile | Debit / Credit / Prepaid pill; UNKNOWN = no badge. |
| Card count subtitle | `"X / 30"` in toolbar subtitle; driven by unfiltered `allCards`. |
| Search | `SearchView` in toolbar; filters by nickname (case-insensitive) or last 4 digits. |
| Bank filter chips | `ChipGroup` (single-select) dynamically rebuilt from `allCards`; "All" + named banks + "Unknown". Reorder blocked when filter active. |
| Empty state | View shown when vault has 0 cards. |
| No-matches state | Separate view shown when search/filter active but list is empty. |
| Long-press sheet | `BottomSheetDialog` with Reorder / Edit / Delete actions. |
| Add-choice sheet | On add button tap: "Scan card" or "Enter manually". |
| Reorder mode | Hides search + settings menu items; shows "Done"; enables drag handles on tiles. |

### 14.4 Card Detail Screen Features

| Feature | Implementation |
|---|---|
| Card preview tile | Same visual style as home tile; large at top. |
| Masked PAN with toggle | Tap to reveal / hide full PAN; re-masked on pause or `renderState()`. |
| CVV hidden by default | Shows `•••`; re-hidden on `onPause()`. |
| Show CVV button | Opens `CvvBiometricPrompt` → falls through to `AppPasswordDialogFragment` if no STRONG biometric. |
| Copy CVV | Same auth flow as Show CVV. |
| Copy other fields | `copyNickname`, `copyName`, `copyExpiry`, `copyNumber` — haptic on copy. |
| Sensitive copy | Card number + CVV use `ClipboardUtil.copySensitive()` (EXTRA_IS_SENSITIVE + 30 s auto-clear). |
| Network label | Plaintext `rowNetwork` shows e.g. "Visa". |
| Edit button | Navigates to `AddEditCardFragment` with card ID. |
| Delete button | `AlertDialog` confirmation → `viewModel.deleteCurrent()` → pops back. |
| Error handling | Missing row / locked vault / decrypt failure → Snackbar + `popBackStack()`. |

### 14.5 Authentication Screens

| Feature | Implementation |
|---|---|
| Welcome screen | `WelcomeFragment` — description text + "Get started" button. |
| Set password | `SetPasswordFragment` — min 4 chars, confirm field, PBKDF2 provision in `Dispatchers.Default`. |
| App password unlock | `AppPasswordFragment` — PBKDF2 re-derive + canary verify + Keystore install. |
| Forgot password | `AlertDialog` warning of data loss → wipes DB + prefs + Keystore → restarts onboarding. |
| Biometric lock | `BiometricLockActivity` — STRONG/WEAK/DEVICE_CREDENTIAL cascade; no-screen-lock warning dialog. |
| No-screen-lock path | `KeyguardManager.isDeviceSecure()` checked; dialog offers "Continue anyway" or "Open Settings". |
| Auth error dialog | `showAuthErrorAndFinish()` — surfaced for lockout, HW_UNAVAILABLE, etc. before `finishAffinity()`. |

### 14.6 Settings Screen Features

| Feature | Implementation |
|---|---|
| Change password | Full atomic re-encrypt in `SettingsViewModel.runChange()`. |
| Lock on background toggle | `MaterialSwitch`; default ON. |
| CVV re-auth mode | Radio buttons `PER_ACTION` / `PER_SESSION`. |
| Reset app | Two-confirmation dialog chain → `AppDatabase.wipe()` + `SecurePreferences.clear()` + `SessionManager.lock()` → restart onboarding. |
| App version display | `PackageManager.getPackageInfo().versionName`. |

### 14.7 Card Scanning Features (§13 detail)

| Feature | Implementation |
|---|---|
| Camera permission flow | `ActivityResultContracts.RequestPermission`; rationale panel vs. blocked panel; `onResume` re-check. |
| No-camera fallback | `FEATURE_CAMERA_ANY` check; fallback panel with no action button. |
| Camera error fallback | `runCatching { provider.bindToLifecycle(...) }` → fallback panel with "Try again". |
| Frame throttle | 250 ms min interval (`MIN_FRAME_INTERVAL_MS`). |
| 25 s timeout | `viewLifecycleOwner.lifecycleScope.launch { delay(25_000) }` — restartable after rotation. |
| Torch toggle | `CameraControl.enableTorch()`; driven by `torchState` observer; hidden when no flash unit. |
| Low-light hint | Luma EMA on Y plane with hysteresis (enter < 62, exit > 82); "Too dark" hint. |
| Hold-steady hint | After 8 frames with no PAN locked. |
| Multi-frame voting | `ScanAccumulator` — 3 agreeing reads lock a field; PAN + expiry needed to auto-finish. |
| Scan undo | `FormSnapshot` captured pre-apply; "Undo" Snackbar action restores it. |
| Partial result | "Use what was found" button shown when PAN is locked. |
| Rotation survival | `CardScanViewModel` holds accumulator; scan progress not lost on config change. |
| CVV structural exclusion | `ScanCandidate` has no CVV field; `ScanResultBridge` carries no CVV key. |
| OCR | ML Kit text recognition, **bundled** model; `MlKitTextSource` is the only ML Kit-aware class. |
| PAN scoring | Luhn (100 pts) + network prefix (50 pts) + canonical length (25 pts) + group structure (20 pts) + glyph height (15 pts) − letters penalty (−40 pts). Floor = 120. |
| OCR repair | `NumericNormalizer` — O→0, I/l→1, S→5, B→8, etc., applied only to digit-dominated tokens. |
| Bank fuzzy match | `BankMatcher` — exact slug → containment (min 5 chars) → Levenshtein (threshold 1 for short slugs, 2 for long). Returns canonical vocabulary spelling. |
| TalkBack announcements | Each checklist field announces once as it locks. |

---

## 15. Bug Catalogue

Bugs are classified by **severity** (Critical / Medium / Low) and **effort to fix** (Easy / Medium / Hard).

### 15.1 Open Bugs

#### LOW-1 — `AppPasswordDialogFragment.verify()` creates `SecurePreferences` on every call
**Severity:** Low  **Effort:** Easy  
**File:** `ui/auth/AppPasswordDialogFragment.kt:81`

```kotlin
val metadata = VaultMetadataRepository(db, db.metadataDao(), SecurePreferences(ctx))
```

`SecurePreferences(ctx)` opens `EncryptedSharedPreferences`, which performs a Keystore operation (~50–100 ms). This runs on `Dispatchers.Default` on every password attempt. It should be constructed once (e.g. in `onCreateDialog`) and reused. No functional impact; just a latency cost.

---

#### LOW-2 — `ExpiryUtil.isExpiringSoon()` returns `true` for long-expired cards
**Severity:** Low  **Effort:** Easy  
**File:** `util/ExpiryUtil.kt:27`

```kotlin
return !expiry.isAfter(today.plusMonths(withinMonths.toLong()))
```

This is `true` for any expiry ≤ today+2 months, which includes cards expired years ago. The tile badge says "Expires soon" for a card from 2018. Fix: distinguish `expiry.isBefore(today)` (show "Expired") from `expiry` within the window (show "Expires soon"). Requires adding a second badge string and a second return state from `ExpiryUtil`.

---

#### LOW-3 — `VaultMetadataRepository.readOrSeed()` susceptible to a benign race on first unlock
**Severity:** Low  **Effort:** Medium  
**File:** `data/repository/VaultMetadataRepository.kt:37`

If `getSalt()` and `getCanary()` are called concurrently on the very first unlock after a v3→v4 upgrade (before either row exists in the DB), both coroutines can pass the `dao.get(key) == null` check at the same time, both read from prefs, and both attempt the seed transaction. The second transaction's `REPLACE` is harmless (idempotent), but `prefs.clearSeededSecrets()` runs twice — which is also safe. The race cannot produce corruption; it is a theoretical double-write that resolves correctly. Fix: add a `Mutex` on `readOrSeed`.

---

#### LOW-4 — `CardDetailViewModel` and `AddEditCardViewModel` use hardcoded English error strings
**Severity:** Low  **Effort:** Easy  
**Files:** `ui/detail/CardDetailViewModel.kt:33,36,54`, `ui/addedit/AddEditCardViewModel.kt:42,64,144,145,146`

```kotlin
error.value = "Card not found"
error.value = "Vault is locked"
fatalError.value = "Could not read card"
fatalError.value = "Could not save card"
```

These strings bypass the `strings.xml` localisation pipeline. If the app is ever localised, these messages remain in English. Fix: add corresponding `@StringRes` references and pass them through to the fragment via `getString(R.string.xxx)`.

---

#### LOW-5 — `AddEditCardFragment.isExpiryInPast()` uses `Calendar` instead of `java.time`
**Severity:** Low  **Effort:** Easy  
**File:** `ui/addedit/AddEditCardFragment.kt:575`

`ExpiryUtil.kt` uses `java.time.YearMonth` (available from API 26, which is this app's min SDK). `isExpiryInPast()` uses the older `java.util.Calendar`. No functional difference, but the inconsistency is a minor code-style issue. Fix: replace with `YearMonth` like `ExpiryUtil` does.

---

#### LOW-6 — `CardDetailFragment.renderState()` hardcodes `network.contentDescRes` but `CardDetailState` does not carry `CardType` or `issuingBank`
**Severity:** Low  **Effort:** Easy  
**File:** `ui/detail/CardDetailState` (defined in `CardDetailViewModel.kt:70`)

`CardDetailState` does not include `cardType` or `issuingBank`, so the detail screen cannot show those fields even though they exist on the card. The detail layout (`fragment_card_detail.xml`) may or may not have rows for these. If the layout has no rows, this is intentional and fine; if it does, those rows are never populated. (Verify against the layout XML.)

---

#### LOW-7 — `REQUIREMENTS.md` §1.4 states `android:excludeFromRecents="true"` on all activities, but the manifest does not set it
**Severity:** Low  **Effort:** Easy  
**File:** `AndroidManifest.xml`, `REQUIREMENTS.md:39`

The manifest has no `android:excludeFromRecents="true"` attribute on any activity. `FLAG_SECURE` prevents screenshots/thumbnails in Recents, so there is no security hole — but the requirement is mis-stated. Either add the attribute or update §1.4 to say only `FLAG_SECURE` is used (the stronger approach, since `excludeFromRecents` also removes the task from the overview, which is a UX concern not a security one).

---

### 15.2 Previously Fixed Bugs (for reference)

| ID | Description | Fixed in |
|---|---|---|
| BUG-F1 | `SettingsFragment` observer leak in `performReset()` used in-callback observer | v1.1 |
| BUG-F2 | Non-atomic re-encryption in `SettingsViewModel` (no transaction) | v1.2 |
| BUG-F3 | `CardDetailFragment` `lateinit` crash on `onPause()` before `bindViews()` | v1.1 |
| BUG-F4 | `BiometricLockActivity` silent close on lockout — no user feedback | v1.1 |
| BUG-F5 | `CardDetailFragment` toggle icon state inconsistent after edit | v1.1 |
| BUG-F6 | `SettingsFragment` wrong error string for `ChangePasswordOutcome.Error` | v1.1 |
| BUG-F7 | `CardType.UNKNOWN` had `0` (invalid `@StringRes`) | v1.1 |
| BUG-F8 | `wireCardTypePicker` double-fired (used `buttonId` param, not `group.checkedButtonId`) | v1.1 |
| BUG-F9 | `AppPasswordDialogFragment` `deliver(false)` double-fire from `setOnCancelListener` | v1.1 |

---

## 16. Limitations

These are known, intentional constraints of the current implementation, not bugs.

### 16.1 Data & Storage

- **30-card hard limit.** Enforced in `HomeFragment`; no way to increase without a code change.
- **Single vault / single user.** One password, one encrypted key. There is no multi-profile or multi-user support.
- **No export / import.** Card data cannot be exported to a file or imported from another vault or password manager. Data loss on factory reset or uninstall is permanent.
- **No cloud backup.** Backup is explicitly disabled. There is no sync to Google Drive, Samsung Cloud, or any other cloud service.
- **No iCloud / cross-device.** Android-only app; no data portability to iOS or desktop.
- **No search by name-on-card.** `CardViewModel.matches()` searches nickname and last 4 digits only; the name-on-card field is not searched.
- **No search by network or card type.** No filter chip for Visa/Mastercard/etc. or Debit/Credit/Prepaid.
- **`issuingBank` is plaintext.** The bank name field is stored unencrypted in Room (by design — it drives the filter chips without decryption). An attacker with access to the DB file can read bank names and nicknames without the password.

### 16.2 Security

- **Minimum password length is 4 characters.** This is intentionally low (§1.2). The app relies on the biometric layer as the primary barrier; the in-app password is the fallback. A 4-character password is weak against offline brute-force but adequate given the Keystore requirement for online attacks. No maximum length is enforced.
- **No brute-force lockout on the in-app password.** `AppPasswordDialogFragment` and `AppPasswordFragment` impose no attempt counter or delay. An attacker with physical access who can somehow bypass biometric can attempt the in-app password at the speed of PBKDF2 verification (~100 ms per attempt on a modern phone).
- **PBKDF2 cost is fixed at 100 000 iterations.** This is adequate for 2024-era hardware but will age. There is no mechanism to increase the iteration count without a password change (the salt is fixed at onboarding).
- **The biometric layer is a gate, not a second factor.** If the device's biometric is bypassed (e.g. enrolled by an attacker), the in-app password is the only remaining barrier.
- **`needsReauth` flag is not enforced as a hard gate.** `SessionManager.needsReauth` is set by `markNeedsReauth()` / `lock()`, but the enforcement relies on `MainActivity.onResume()` checking it and routing to `BiometricLockActivity`. If any Activity or Fragment accesses `SessionManager.requireKey()` before `onResume()` runs, no error is thrown.
- **Clipboard clear is best-effort.** The 30-second clear only fires if the clipboard still contains what was written. If the user copies something else, the old sensitive value is not cleared. This is the intended behaviour, but it means clipboard clear is not a reliable erasure.

### 16.3 Card Scanning

- **Scanning requires a rear camera.** Front camera is a last resort (`pickCamera` tries back first). Tablets with only a front camera may have a degraded experience.
- **Scanning does not read virtual card numbers.** On-screen card images (screenshots, PDF statements) will not scan reliably — the camera is not designed for it and there is no "scan from image" path.
- **PAN score floor of 120 requires Luhn validity.** Any card number that fails the Luhn check (test PANs, some gift/virtual cards) cannot be scanned — it will never reach the score floor. The user must type it manually. (Entering it manually with the Luhn warning IS supported.)
- **Bank vocabulary is static + user-entered.** `BankSuggestions.seed` has 18 banks. A bank not in the list and not previously entered by the user may not be matched by the scanner.
- **Scanning does not read the CVV (by design).** See §13.2.
- **Scan timeout is 25 seconds.** There is no user-configurable timeout. Embossed, worn, or reflective cards may consistently hit the timeout.
- **OCR is Latin-script only.** `TextRecognizerOptions.DEFAULT_OPTIONS` targets the Latin script. Cards with names, bank names, or numbers in Devanagari, Arabic, Chinese, etc. will not scan correctly.

### 16.4 UI / UX

- **Dark theme only.** The app uses `Theme.Material3.Dark.NoActionBar` with no light-mode variant. System theme setting is ignored.
- **No RTL support.** `android:supportsRtl="false"`. Card layouts are always LTR.
- **No tablet / large-screen layout.** Layouts are phone-optimised single-pane. On a tablet the cards span the full width, which may look poor on a 12" screen.
- **No widget, shortcut, or share-sheet integration.** Cards can only be accessed from within the app.
- **Expiry past validation blocks saving expired cards.** A user who wants to archive an expired card for reference cannot do so without setting a future expiry. (The "Expires soon" badge will show, but the user cannot save it to begin with.)
- **No card number formatting for non-4-4-4-4 schemes.** Amex cards follow 4-6-5 grouping; `CardFormatting.formatPanForDisplay` uses a fixed 4-digit group regardless of network. The card is stored and displayed correctly; only the spacing on the preview/detail is off for Amex.

### 16.5 Technical

- **No ViewBinding.** The project was specified to use ViewBinding (`buildFeatures { viewBinding = true }`) but all fragments use `findViewById`. This is a maintainability issue, not a runtime bug.
- **`CardVaultApplication.instance` is accessible.** The Application exposes a static `instance` reference. Nothing currently uses it unsafely, but it is a global static that could be misused.
- **No unit tests for most logic.** Only `CardNetworkDetectorTest` exists. `CryptoManager`, `ExpiryUtil`, `Luhn`, `CardFormatting`, `BankMatcher`, `CardScanParser`, `ScanAccumulator`, and `NumericNormalizer` have no tests, despite being pure / near-pure functions that are straightforwardly testable.
- **`biometric:1.1.0` is old.** The `androidx.biometric:biometric:1.1.0` dependency was released in 2021; `1.2.0-alpha05` and later offer additional features. No functional gaps affect this app's use, but the library is due an update.
- **`security-crypto:1.1.0-alpha06` is an alpha.** `androidx.security:security-crypto` has been in alpha for several years. The API is stable in practice, but the `alpha` designation is a maintenance flag.
- **`exportSchema = false` on `AppDatabase`.** Room schema export is disabled. This makes schema diffing and migration testing harder. Recommended for production apps to enable and commit the schema JSONs to version control.

---

## 17. Assumptions

These are design decisions made where requirements were ambiguous, or constraints implied by the implementation that are not stated in the requirements document.

### 17.1 Security Assumptions

1. **The Android Keystore is trusted.** The master key is protected by the Keystore hardware (TEE/StrongBox where available). If the Keystore is compromised — e.g. by a rooted device with a Keystore bypass — the encryption provides no additional protection beyond the raw PBKDF2-derived key.

2. **The device's biometric enrolment is controlled by the user.** The app cannot verify who enrolled biometrics. An adversary with physical access who has enrolled their own biometric can pass Layer 1.

3. **PBKDF2 cost of 100 000 iterations is adequate.** This was chosen as a round number in 2024. It provides ~100 ms per guess on a modern phone, which is adequate for interactive use but not hardened against a massively parallel offline attack on the raw DB.

4. **The `cardvault-canary-v1` plaintext is not secret.** The canary is a verification mechanism, not a secret. Its confidentiality provides no security benefit; what matters is that decrypting it with the wrong key fails the GCM auth tag.

5. **`SecurePreferences` (EncryptedSharedPreferences) is trusted for non-secret data.** Salt and canary moved to Room (v4). The remaining prefs (`onboarding_done`, `lock_on_background`, `cvv_reauth_mode`) are not sensitive. If `EncryptedSharedPreferences` is bypassed on a rooted device, only these settings are exposed.

6. **`android:allowBackup="false"` prevents Google Drive backup.** This is true for user-initiated backups. It does not prevent a privileged process (ADB backup with a debug key, for example) from extracting app data on a non-production device. The `FLAG_SECURE` and encryption still protect the data in that case.

### 17.2 Data Assumptions

7. **Cards are the user's own physical cards.** The app is a personal vault. It is not intended for storing other people's card numbers.

8. **Expiry is stored as `MMYY` (4 digits).** The UI auto-formats to `MM/YY`. The storage format is digits-only, matching how `ExpiryUtil` and `CardScanParser` consume it.

9. **Card numbers are 13–19 digits.** This covers Visa (13 or 16), Mastercard (16), Amex (15), Diners (14), Discover (16), RuPay (16). Gift cards and some private-label cards may fall outside this range and cannot be stored.

10. **Nickname and Name on Card are stored as plaintext.** This is a deliberate trade-off: searching, filtering, and displaying the list does not require the vault to be unlocked for metadata. An attacker who reads the DB file can see all nicknames and names on card.

11. **Card network detection is best-effort.** `CardNetworkDetector` uses BIN prefix rules that were current at time of writing. New BIN ranges assigned after that date (e.g. new Mastercard 2-series ranges, new RuPay ranges) will default to UNKNOWN until the rules are updated.

### 17.3 UX Assumptions

12. **The user always has access to both authentication factors.** If the user changes their device PIN/biometric (Layer 1) without the app open, the Keystore key may be invalidated depending on the `KeyProtection` flags used. This app does NOT set `setUserAuthenticationRequired(true)` on the Keystore key, so changing the device credential does NOT invalidate the key. The vault remains accessible.

13. **The user will notice and act on the "Expires soon" badge.** The badge is informational only; the app does not prevent viewing, copying, or using an expired card's details.

14. **Reorder is always done on the full unfiltered list.** `HomeFragment` blocks entering reorder mode when a search query or bank filter is active, so the user must clear those first. This is a UX trade-off to avoid reordering only a visible subset and creating unexpected ordering collisions with hidden rows.

15. **The scan timeout of 25 seconds is adequate.** A user who cannot scan within 25 seconds is assumed to be better served by manual entry. The "Try again" button allows additional attempts.

16. **The 4-character minimum password is sufficient given the biometric gate.** The design assumes that Layer 1 (biometric) is the primary barrier and the in-app password is a fallback. If this assumption is wrong (e.g. deployed on a device without any biometric enrolled), a 4-character password provides little protection.

### 17.4 Technical Assumptions

17. **Android Keystore `BLOCK_MODE_GCM` with `setRandomizedEncryptionRequired(true)` guarantees IV uniqueness.** The implementation relies on the OS generating a unique IV for each encryption. If the Keystore ever reuses an IV under GCM, the encryption is broken. This is a platform guarantee, not an app-level guarantee.

18. **`db.withTransaction` in Room is equivalent to SQLite `BEGIN IMMEDIATE; ...; COMMIT`.** The atomicity of the password-change flow depends on this. Room's `withTransaction` uses SQLite transactions, which are atomic and durable on Android.

19. **`SessionManager` as a process-singleton is safe.** The entire app runs in a single process. If Android ever split the app across processes (e.g. a future multi-process manifest attribute), `SessionManager`'s in-memory key would not be shared. No such split is present.

20. **ML Kit `TextRecognizerOptions.DEFAULT_OPTIONS` is the bundled Latin recogniser.** The `com.google.mlkit:text-recognition:16.0.1` dependency ships with a bundled model. No runtime download occurs. If this changes in a future ML Kit version, the no-network guarantee would need re-verification.
