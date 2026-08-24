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
- Zero network permissions — the app is fully offline. Only `USE_BIOMETRIC` and `USE_FINGERPRINT` are requested.
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
| Backup | `android:allowBackup="false"` + `backup_rules.xml` + `data_extraction_rules.xml` |
| Screenshots | Blocked via `FLAG_SECURE` on all activities |
| Max Cards | 30 |

---

## 10. Permissions

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.USE_FINGERPRINT" />
```

No internet, storage, camera, or any other permissions.

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
├── security/
│   ├── CardNetwork.kt                       # enum: VISA/MASTERCARD/AMEX/RUPAY/DISCOVER/DINERS/UNKNOWN
│   ├── CardNetworkDetector.kt
│   └── SessionManager.kt
├── ui/
│   ├── addedit/
│   │   ├── AddEditCardFragment.kt
│   │   ├── AddEditCardViewModel.kt
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
│   │   ├── BankSuggestions.kt
│   │   ├── CardDisplay.kt
│   │   ├── CardTileAdapter.kt
│   │   ├── CardViewModel.kt
│   │   └── HomeFragment.kt
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
    └── ExpiryUtil.kt

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

*Document version: 1.2 — Last updated: 2026-08-24 (reconciled with post-bug-#1-fix implementation).*
*Notable changes from v1.1: Room schema bumped to v4 with a new `metadata` table for salt + canary; password change is now atomic via a single `db.withTransaction` that spans card ciphertext + salt + canary; canary is generated from the raw PBKDF2 key rather than the Keystore-wrapped handle (breaks the Keystore ordering dependency); `SecurePreferences` no longer stores crypto material and exposes only seed accessors for the v3→v4 upgrade; new files `MetadataEntry.kt`, `MetadataDao.kt`, `VaultMetadataRepository.kt`; `MIGRATION_3_4` shipped; explicit lazy prefs→DB seed documented.*
*Changes from v1.0 → v1.1: password verification is canary-based rather than SHA-256 hash comparison; Room compiler runs via KSP not kapt; Gson dropped (unused); Settings gains a CVV Re-Auth Mode toggle; Reset flow uses two chained confirmations; file structure updated to match `com.cardvault` package layout.*
