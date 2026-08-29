# Card Vault — Customer Workflows

Every user-facing workflow in the app, traced from the code: where it starts, what the user does,
what the app does, and — the part the other two documents skip — every branch and failure state,
including where each one leaves the user.

`README.md` explains what the app is for. `REQUIREMENTS.md` specifies what it must do. This document
answers "**what does the user actually see if X happens?**"

**Conventions used below**

- `→` a screen transition; `⤷` a branch off the main path.
- **Terminal state** means the workflow ends there — the user must start something else.
- Screens are named as the user sees them, with the implementing class in parentheses on first use.
- Where a message is quoted, it is the real string from `strings.xml`.

---

## Contents

| # | Group | Workflows |
|---|---|---|
| 1 | [Getting in](#1-getting-in) | First launch · Unlock · No screen lock · Forgot password · Return from background |
| 2 | [Adding a card](#2-adding-a-card) | Choose scan or manual · Manual entry · Save validation · Luhn confirmation · Abandon a dirty form |
| 3 | [Scanning a card](#3-scanning-a-card) | Happy path · Rescan from the form · Timeout · Partial result · Permission states · No camera · Camera fails to open · Applying and undoing |
| 4 | [Living with the list](#4-living-with-the-list) | Browse · Search · Filter by bank · Reorder · Card-limit ceiling · Empty states |
| 5 | [Using a card](#5-using-a-card) | View detail · Reveal the number · Reveal the CVV · Copy a field · Clipboard auto-clear |
| 6 | [Changing a card](#6-changing-a-card) | Edit · Delete |
| 7 | [Settings](#7-settings) | Change password · Lock on background · CVV re-auth mode · Reset app · Version |
| 8 | [Interruptions](#8-interruptions) | Backgrounding · Rotation · Process death · Locking mid-action |
| 9 | [Workflow map](#9-workflow-map) | Everything on one page |

---

## 1. Getting in

### 1.1 First launch — onboarding

**Entry:** installing and opening the app for the first time (`onboarding_done` is false in
`SecurePreferences`, so `MainActivity` immediately hands off to `OnboardingActivity`).

1. **Welcome** (`WelcomeFragment`) — "Welcome to Card Vault" and a one-line description. Tap **Get started**.
2. **Set in-app password** (`SetPasswordFragment`) — enter a password and confirm it.
   - ⤷ Empty, or under 4 characters → "Password must be at least 4 characters". An empty field is
     caught by the length check, so it does not produce a separate "Required" message here.
   - ⤷ The two fields differ → "Passwords do not match".
3. On a valid password the app generates a 16-byte random salt, derives the AES-256 key with
   PBKDF2 (100 000 iterations), encrypts the fixed canary plaintext with it, and commits **salt +
   canary in one database transaction**. It then installs the key in the Android Keystore, flags
   `onboarding_done`, and hands the key to `SessionManager`.
4. → **Cards** (main screen) showing the "No cards yet" empty state.

**Notes**

- The password is never stored, in any form. Step 3 stores only a salt and a blob that the right
  password can decrypt.
- If the process dies between the transaction commit and the Keystore install, nothing is lost —
  the next unlock re-derives the key from the stored salt and reinstalls the alias.
- **Onboarding cannot be skipped or revisited.** The only way back to it is **Reset app** (§7.4) or
  **Forgot password** (§1.4), both of which destroy the vault.

### 1.2 Unlocking — the normal path

**Entry:** opening the app when it is onboarded but locked. `MainActivity` sees
`SessionManager.isUnlocked() == false` and starts `BiometricLockActivity`.

1. **Lock screen** (`BiometricLockActivity`) presents Android's `BiometricPrompt`. The app asks for
   the strongest factor the device can satisfy, in this order:
   - Class 3 biometric **or** device credential;
   - Class 2 biometric **or** device credential;
   - device credential only.
   A cancel button appears only in the biometric-only case — Android does not allow one on a
   device-credential prompt.
2. On success the lock screen swaps its title for the **in-app password** form
   (`AppPasswordFragment`). Enter the password and tap **Unlock**.
3. The app re-derives the key from the stored salt and tries to decrypt the canary. Success means
   the password was right — the GCM authentication tag is the check, so there is no hash to compare
   and nothing to leak.
4. → **Cards**, with the task stack cleared so Back cannot walk into the lock screen.

**Branches**

| What happens | What the user sees | Where they end up |
|---|---|---|
| Empty password field | "Required" on the field | Still on the password form |
| Wrong password | "Incorrect password" on the field | Still on the password form — **no attempt limit, no lockout, no delay** |
| User cancels the biometric prompt (Cancel / back / negative button) | Nothing — the app closes silently | **Terminal.** App exits |
| Biometric fails for a system reason (hardware unavailable, too many attempts, biometrics removed) | A dialog carrying Android's own localised explanation, then OK | **Terminal.** App exits |
| Salt or canary missing from the database | Treated as a wrong password | Still on the password form |

The verification deliberately happens **before** anything is written to the Keystore, so a wrong
guess costs one PBKDF2 derivation and no Keystore round-trip.

### 1.3 Unlocking with no screen lock configured

**Entry:** same as §1.2, on a device with no PIN, pattern, password or biometric enrolled — so
Layer 1 cannot run at all.

The lock screen shows a non-cancellable dialog with three choices:

| Choice | Result |
|---|---|
| **Open security settings** (neutral) | Android's security settings open; the vault task finishes |
| **Continue anyway** (positive) | Straight to the in-app password form — **one lock layer only** |
| **Cancel** (negative) | **Terminal.** App exits |

This is the one supported way to run the app with a single factor, and it is an explicit user
decision each time.

### 1.4 Forgot the in-app password

**Entry:** the **Forgot password?** button on the in-app password form.

1. A dialog explains there is no recovery and that continuing wipes everything.
2. ⤷ **Cancel** → back to the password form, nothing changed.
3. **Reset app** → the database file is deleted (taking the salt, the canary and every card with
   it), preferences are cleared, and the Keystore alias is removed.
4. → **Welcome** (onboarding), with the task stack cleared.

**There is no other recovery path.** No recovery code, no security question, no export to fall back
on. This is the single most important thing for a user to understand before they choose a password.

### 1.5 Returning to the app after backgrounding

**Entry:** switching away from the app and back.

- With **Lock on background** ON (the default): the vault was locked on the way out — the in-memory
  key was nulled and the Keystore alias deleted. Returning routes through the full §1.2 flow, both
  factors again. The key is dropped the moment the user leaves, not whenever Android gets round to
  stopping the activity, so switching away and straight back still locks.
- With it OFF: the app resumes exactly where it was. Any revealed CVV is re-hidden and any revealed
  card number is re-masked anyway, because the detail screen resets both in `onPause`.
- If Android killed the process while it was away, the vault is locked regardless of the setting —
  the key only ever lived in memory — so the user gets §1.2 either way.

> Rotating the screen is **not** treated as backgrounding and does not lock the vault — see §8.2.

---

## 2. Adding a card

### 2.1 Choosing how to add

**Entry:** the **+** icon at the top-left of the **Cards** toolbar.

1. Before anything opens, the app counts the cards it has stored — the **unfiltered** count, so an
   active search or bank filter cannot smuggle the user past the ceiling.
   - ⤷ At 30 cards → "Card limit reached (30/30)" and nothing opens. **Terminal** until a card is
     deleted (§6.2).
2. An **add-choice bottom sheet** offers two routes:
   - **Scan card** — "Reads the number, expiry, name and bank" → §3.1.
   - **Enter manually** → §2.2.
3. ⤷ Dismissing the sheet returns to **Cards**, nothing created.

### 2.2 Entering a card manually

**Entry:** **Enter manually** from §2.1, or the fall-back **Enter manually** button available in
every state of the scanner (§3).

The **Add card** form (`AddEditCardFragment`) has a live card preview at the top and these fields:

| Field | Required | Behaviour while typing |
|---|---|---|
| **Nickname** | Yes | Appears on the preview as you type |
| **Name on card** | Yes | Force-uppercased as you type, and mirrored on the preview |
| **Card number** | Yes | Digits only, capped at 19; grouped in fours; the network logo on the preview updates live from the BIN prefix; the preview shows it masked |
| **Expiry** | Yes | Digits only, capped at 4; formatted `MM/YY` |
| **CVV** | Yes | Masked while typing (`numberPassword`), never autofilled |
| **Issuing bank** | No | Autocomplete over 18 built-in banks plus every bank already used on another card; free text accepted |
| **Card type** | No | Debit / Credit / Prepaid toggle; unset by default |
| **Colour** | No | 12 preset tile colours; a slate default is pre-selected |

Two things are deliberately absent: the app sets `importantForAutofill="no"` on the number and CVV
inputs so no autofill service can capture them, and the camera icon inside the number field is the
only way to reach the scanner from here (§3.2).

Tap **Save** → the number, expiry and CVV are encrypted, the row is written, and the app returns to
**Cards** with the new tile in place.

### 2.3 Save validation

Validation runs every check and reports **all** the failures it finds at once, each under its own
field. It does not stop at the first one, and it does not move focus to the offending field — on a
scrolled form the user may have to scroll back up to see what failed. The order below is the order
the errors read down the form:

| # | Check | Message on failure |
|---|---|---|
| 1 | Nickname is not blank | "Required" |
| 2 | Name on card is not blank | "Required" |
| 3 | Card number is 13–19 digits | "Card number must be 13–19 digits" |
| 4 | Expiry is a real month (`01`–`12`) and four digits | "Expiry must be MM/YY" |
| 5 | Expiry is not in the past | "Expiry is in the past" |
| 6 | CVV is 3–4 digits | "CVV must be 3 or 4 digits" |
| 7 | Luhn checksum, or an acknowledgement | → §2.4 |

⤷ **Expired cards cannot be saved at all.** A user who wants to keep an expired card for reference
has to give it a future date. There is no override.

⤷ If the vault locks mid-save (the user backgrounds the app with Lock on background ON at exactly
the wrong moment), the save reports "Vault is locked" rather than hanging with a dead Save button.

⤷ If the row being edited was deleted from somewhere else since the form was opened, the save
reports "Card no longer exists" instead of silently doing nothing.

### 2.4 The checksum warning and "Save anyway"

The Luhn (mod-10) check is a **warning, never a block** — real cards do fail it, including issuer
test numbers and some virtual and private-label cards.

1. **While typing:** when the number field loses focus with 13–19 digits that fail the checksum, an
   **amber helper message** appears beneath it. It is a helper, not an error, so it cannot collide
   with the hard 13–19-digit block.
2. **On Save:** a dialog asks whether to save anyway.
   - **Save anyway** → the acknowledgement is recorded and the save proceeds.
   - **Let me check** → the dialog closes and the number field is refocused.
3. Editing the number afterwards clears both the warning and the acknowledgement, so a corrected
   number is re-checked from scratch.

### 2.5 Abandoning a form with unsaved changes

**Entry:** Back, or the toolbar's up affordance, on an Add or Edit form that has been touched.

- If nothing has changed, the form closes immediately.
- If anything has changed — including a colour pick, a card-type toggle, or an applied scan — a
  confirmation dialog appears. Discarding returns to the previous screen; cancelling keeps the form.
- The dirty flag survives rotation, so a rotated form still warns.

---

## 3. Scanning a card

The scanner reads **number, expiry, name and issuing bank**. It does not read the CVV, and cannot —
the type that carries a scan result has four fields and none of them is a security code. Every
scanned value lands in an editable field.

### 3.1 Scanning from the add-choice sheet — the happy path

**Entry:** **Scan card** in the add-choice sheet (§2.1).

1. **Scan card** (`CardScanFragment`) opens the camera with the hint "Hold the front of the card
   inside the frame". A reticle marks where to put it.
2. An overlay checklist shows four fields — **Number**, **Expiry**, **Name**, **Bank** — each of
   which ticks once **three separate frames have agreed** on the same value. A field that has
   locked stays locked; a single bad read gets outvoted rather than accepted. The number has to pass
   its checksum as well as win the vote.
3. **The scan ends itself** as soon as Number *and* Expiry are both locked.
4. → **Add card**, pre-filled with whatever locked, the cursor placed in the **CVV** field, and a
   confirmation Snackbar:
   - all four fields locked → "Card details filled in — add the CVV to finish";
   - some fields missing → "Filled in what could be read — check it and add the CVV".
   Either message carries an **Undo** action (§3.8).

**Always available, in every state of this screen:** an **Enter manually** button, and a **torch**
toggle when the camera has a flash.

**Live hints**, shown as conditions change:

| Condition | Hint |
|---|---|
| Too dark to read (rolling luminance average below threshold for three consecutive samples) | "Too dark — turn on the torch or move to better light" |
| Several frames analysed with nothing locked yet | "Hold steady — fill the frame with the card" |

### 3.2 Rescanning from the Add/Edit form

**Entry:** the **camera icon inside the Card number field** on an Add or Edit form.

Identical to §3.1, with one difference: the result is merged into the form the user is already on.
Fields the scan could not read keep whatever was typed there, and the CVV is untouched.

Returning from the scanner does not re-load the stored card over the form, and does not re-apply a
scan a second time on rotation — both are one-shot by construction.

### 3.3 The scan times out

**Entry:** 25 seconds elapse without the number and expiry both locking.

1. The camera is released and a panel replaces the preview: **"Couldn't read the card"** — "Embossed
   and worn cards are hard to read, especially in low light. Try again with the torch on, or type
   the card in — that always works."
2. Three ways out:
   - **Try again** → the camera restarts. **Votes banked so far are kept**, so a second attempt
     continues from where the first got to rather than starting over.
   - **Use what was found** → §3.4. Offered only when the **number** locked; nothing else is worth
     carrying forward on its own. This button is not exclusive to the timeout panel — it appears
     during the live scan as soon as the number locks, so a user who can see the number was read
     does not have to wait out the remaining seconds.
   - **Enter manually** → the Add form, empty.
3. With **Lock on background** OFF, having timed out is a state only the user leaves: backgrounding
   the app and returning shows the same panel, not a surprise live camera.
   - ⤷ With the setting **ON** — the default — backgrounding locks the vault first, so returning
     goes through both lock layers and lands on **Cards**. The panel, the live camera and the banked
     votes are all gone, and the scan starts from scratch. See §8.1.

### 3.4 Accepting a partial result

**Entry:** **Use what was found**, on the timeout panel.

Whatever locked is applied; the rest is left blank. The user lands on the Add form with the same
"Filled in what could be read" Snackbar and Undo as §3.1.

⤷ If nothing at all locked, applying reports "Nothing could be read from the card" and the form is
left exactly as it was.

### 3.5 Camera permission

The `CAMERA` permission is requested **the first time the scanner is opened**, never at launch, and
the app is fully usable without it.

| State | What the user sees | Ways out |
|---|---|---|
| **Not yet asked** | The **system permission prompt**, straight away. There is no in-app rationale panel in front of the first ask. | **Allow** / **Don't allow**, in the system prompt |
| **Just denied** | A rationale panel: "Camera access needed" — "Scanning reads the card with the camera. The image is processed on this device and never saved or sent anywhere — the vault has no internet permission at all." A second **Allow camera** re-prompts. | **Allow camera** · **Enter manually** |
| **Denied permanently** (the system will no longer prompt) | "Camera is turned off" — "Camera access for Card Vault is disabled in Android settings, so scanning can't start. Turn it on there, or just type the card in." | **Open settings** → the app's system settings page · **Enter manually** |

⤷ **Open settings** leaves the app, and leaving the app is backgrounding it. With **Lock on
background** ON — the default — the vault locks, so coming back from the Android settings page goes
through both lock layers and lands on **Cards** rather than back in the scanner. Grant the
permission there, then open the scanner again.

⤷ The **system prompt** in the first row is the exception: answering it returns straight to the
scanner with the vault still unlocked. It is the app's own prompt, part of the flow the user is
already in, so it is not treated as leaving even though Android reports it the same way a Home press
is reported. See REQUIREMENTS.md `beginInAppExcursion()`.

### 3.6 No camera on the device

**Entry:** opening the scanner on a device with no camera at all.

"No camera on this device" — "Scanning needs a camera. Everything else works as normal — add the
card by hand instead." **Enter manually** is the way forward. The app declares the camera as an
optional feature, so a camera-less device can install and use everything except scanning.

### 3.7 The camera fails to open

**Entry:** the permission is granted and hardware exists, but binding the camera throws — most often
because another app holds it.

"Camera didn't start" — "Something else may be using the camera. Close other camera apps and try
again, or add the card by hand." **Try again** re-attempts the bind; **Enter manually** leaves.

### 3.8 Undoing an applied scan

**Entry:** **Undo** on the Snackbar shown after any scan is applied.

The form is restored to the exact snapshot taken immediately before the scan wrote to it — name,
number, expiry, bank, detected network, and the dirty flag — and "Scan undone" is confirmed.

Two things the snapshot deliberately leaves alone, because the scan never touches them: the **CVV**
and the **nickname**, along with the colour choice.

⤷ The Snackbar is the only way to reach Undo, and it does not survive a rotation. After rotating,
correcting the fields by hand is the remaining route.

---

## 4. Living with the list

### 4.1 Browsing

**Cards** (`HomeFragment`) is the home screen: one full-width tile per card, in the user's own
order. The toolbar shows **Cards** with an **`n / 30`** subtitle, a **+** on the left, and **search**
and **settings** on the right. A bank-filter chip strip sits below.

Each tile carries the nickname, the network logo, the masked number, the name in capitals, the
expiry, and — when they apply — a **card-type badge** (Debit / Credit / Prepaid) and an amber
**"Expires soon"** badge.

⤷ "Expires soon" fires for any expiry within two months **or already past**, so a long-expired card
is labelled *Expires soon* rather than *Expired*.

### 4.2 Searching

**Entry:** the search icon in the toolbar.

Typing filters the list live, matching the **nickname** or the **last 4 digits** of the card number,
case-insensitively. Nothing else is searched — in particular the name on the card is not, since it
is usually identical across a person's cards.

- ⤷ The last-4 match works for every card length the app accepts, 13 to 19 digits, not only the
  16-digit ones.
- ⤷ No matches → "No cards match your search." The vault is not described as empty.
- ⤷ Search composes with the bank filter: both active shows only cards matching both.
- ⤷ While a search is active, reorder mode is refused (§4.4).

### 4.3 Filtering by bank

**Entry:** the chip strip below the toolbar.

Chips are built from the **Issuing bank** values actually present in the vault, so the strip only
ever shows banks the user has cards from. **All** is selected by default, and an **Unknown** chip
appears if any card has no bank set.

- ⤷ Adding, editing or deleting a card rebuilds the strip and keeps the active selection, falling
  back to **All** if that bank no longer has any cards.
- ⤷ While a filter is active, reorder mode is refused (§4.4).

### 4.4 Reordering

**Entry:** long-press any tile → **Reorder cards** in the bottom sheet.

1. ⤷ If a search is active: "Clear search first to reorder" and nothing happens.
2. ⤷ If a bank filter is active: "Clear the bank filter first to reorder" and nothing happens.
   Both refusals exist so a drag always operates on the complete list — reordering a filtered subset
   would produce an order that makes no sense against the rows the user cannot see.
3. Reorder mode strips the toolbar down to a single **Done**, hides the search and settings actions
   and the chip strip, and reveals a **drag handle** on each tile.
4. Drags start **from the handle only**. Long-pressing the tile body in reorder mode does nothing.
5. Each drop writes the new order to the database immediately.
6. **Done** returns the toolbar and the chip strip.

### 4.5 Hitting the 30-card ceiling

At 30 stored cards, tapping **+** reports "Card limit reached (30/30)" and opens nothing. Deleting a
card (§6.2) frees a slot immediately. The limit is fixed; there is no setting for it.

### 4.6 Empty states

| Situation | What is shown |
|---|---|
| No cards stored at all | "No cards yet" · "Add your first card to get started." |
| Cards exist, but the search or filter matches none | "No cards match your search." |

---

## 5. Using a card

### 5.1 Opening a card

**Entry:** tap any tile on **Cards**.

**Card detail** (`CardDetailFragment`) shows a large tile at the top, then labelled rows for
**Nickname**, **Name on card**, **Card number**, **Expiry**, **CVV** and **Network**, each with a
copy icon. The number is masked; the CVV is hidden entirely.

⤷ **Issuing bank** and **Card type** are *not* shown here, even though both are stored and both
appear on the list tile. To see or change them, open the card for editing (§6.1).

⤷ If the card was deleted from another path in the meantime, a Snackbar says so and the screen
closes itself back to **Cards** rather than showing a blank record.

### 5.2 Revealing the card number

Tap the number to toggle between masked (`•••• •••• •••• 1234`) and revealed. No re-authentication —
the number alone is the less dangerous half of the pair, and the user has already passed both lock
layers to be on this screen at all.

⤷ Leaving the screen, or backgrounding the app, re-masks it. Coming back never shows a number the
user revealed minutes ago.

### 5.3 Revealing or copying the CVV

**Entry:** **Show CVV**, or the copy icon on the CVV row.

1. The requested action — reveal or copy — is remembered.
2. ⤷ **Per session** mode and CVV access already authorised this session → the action runs
   immediately, no prompt. (See §7.3.)
3. Otherwise the app asks for a **fingerprint or face** prompt. This one is Class 3 biometric
   **only** — the device PIN is deliberately not accepted as a stand-in for the vault's own factor.
4. ⤷ If Class 3 biometrics are unavailable or unenrolled, **or the user dismisses the prompt**, the
   app falls through to the **in-app password dialog**.
   - ⤷ Wrong password → "Incorrect password"; the CVV stays hidden.
   - ⤷ Dismissing the dialog → nothing happens; the CVV stays hidden.
5. On success from either route, the CVV is revealed in plain text, or placed on the clipboard with
   the auto-clear notice (§5.5).

⤷ In **Per session** mode, this first success authorises every later CVV reveal and copy until the
vault locks. In **Per action** — the default — every single reveal and every single copy prompts.

⤷ Once revealed, the button reads **Hide CVV**; tapping it re-masks the CVV straight away. Hiding is
not a privileged action, so it never prompts — only revealing does.

⤷ Leaving the screen or backgrounding the app re-hides the CVV and puts the button back to **Show
CVV**. In **Per action** mode, coming back means re-authenticating again.

### 5.4 Copying a non-sensitive field

Nickname, name on card and expiry copy on a single tap of their copy icon, with a haptic tick and
no auto-clear. They are not treated as sensitive on their own.

### 5.5 Clipboard auto-clear

Copying the **card number** or the **CVV** triggers a notice that the clipboard will be cleared in
30 seconds, and on Android 13+ the clip is additionally flagged sensitive so the system does not
show a preview of it.

After 30 seconds the app clears the clipboard — but **only if what it wrote is still there**.

- ⤷ Copy something else in the meantime and the auto-clear is abandoned, so it cannot stomp on the
  user's own later copy. The consequence is that the earlier sensitive value is no longer cleared by
  the app; it has simply been displaced.
- ⤷ Copy a second sensitive value and the newer copy owns the timer.

This is best-effort hygiene, not guaranteed erasure.

---

## 6. Changing a card

### 6.1 Editing

**Entry:** two places — **Edit card** in the long-press bottom sheet on **Cards**, or the edit icon
on **Card detail**.

The form is the same one as §2.2, pre-filled with the decrypted card. Every field is editable,
including the ones a scan filled. The same validation (§2.3) and the same checksum confirmation
(§2.4) apply, and the same unsaved-changes guard (§2.5).

On **Save** the sensitive fields are re-encrypted and written; the card keeps its position in the
list and its creation date.

- ⤷ If the card was deleted from another path since the form opened, the save reports "Card no
  longer exists".
- ⤷ Rotating mid-edit keeps every typed value, the chosen colour, the card-type toggle and the dirty
  state — it does not restore the stored card over the user's input.

### 6.2 Deleting

**Entry:** two places — **Delete card** in the long-press bottom sheet on **Cards**, or the delete
action on **Card detail**.

1. A single confirmation dialog — "Delete this card?" / "This cannot be undone."
2. ⤷ Cancel → nothing changes.
3. Confirm → the row is deleted and the list updates. From the detail screen, the app also returns
   to **Cards**.
4. The `n / 30` count drops, the bank chip strip rebuilds, and a slot is freed against the ceiling.

Deletion is immediate and permanent. There is no undo, no trash, and no export to have fallen back
on (§7.4).

---

## 7. Settings

**Entry:** the gear icon in the **Cards** toolbar.

### 7.1 Change in-app password

1. Tap **Change in-app password** — a dialog asks for the current password and the new one twice.
2. Validation, in order:
   - ⤷ Current password blank → "Required".
   - ⤷ New password under 4 characters → "Password must be at least 4 characters".
   - ⤷ The two new fields differ → "Passwords do not match".
   - ⤷ Current password wrong → "Incorrect password".
3. On success: every card is decrypted with the old key and re-encrypted with a freshly derived key,
   and the card ciphertext, the new salt and the new canary **all commit in a single database
   transaction**. The Keystore alias and the in-memory session key are then rotated.
4. "Password changed and all cards re-encrypted." confirms it. The session stays unlocked — the user is not thrown back to the
   lock screen.

**What a crash cannot do.** Because the three writes are one transaction, a process kill mid-change
rolls back completely: the **old** password still unlocks and every card still decrypts. There is no
window in which the salt, the canary and the stored cards describe different keys.

⤷ If the change fails for any other reason, a generic error is shown and nothing was committed.

### 7.2 Lock on background

A toggle, **ON by default**.

| Setting | Effect |
|---|---|
| ON | Sending the app to the background nulls the in-memory key and deletes the Keystore alias. Returning requires both lock layers again (§1.5). |
| OFF | The session survives backgrounding. A revealed CVV and a revealed number are still reset on the way out. |

⤷ ON locks at the moment the user leaves — Home, Recents, backing out of the app, or following a
link out to Android settings — not whenever Android gets round to stopping the activity. Rotation is
not backgrounding and does not lock (§8.2), and neither is the app's own camera permission prompt
(§3.5) — everything else that puts another screen in front of the vault does.

### 7.3 CVV re-auth mode

A choice of two, affecting §5.3 only:

| Mode | Effect |
|---|---|
| **Per action** (default) | Every CVV reveal and every CVV copy re-authenticates. |
| **Per session** | The first successful CVV re-auth authorises every later reveal and copy until the vault locks. |

Switching to **Per action** does not retroactively revoke an authorisation already granted this
session; locking the vault does.

### 7.4 Reset app

**Entry:** **Reset app** in Settings. Also reachable as the only exit from a forgotten password
(§1.4).

1. First confirmation dialog. ⤷ Cancel → nothing happens.
2. **Second** confirmation dialog, worded more strongly: it says outright that every card goes the
   moment the user confirms, that this is the last step, and that there is no password check after
   it. ⤷ Cancel → nothing happens.
3. Confirm → the database file is deleted (salt, canary and every card with it), preferences are
   cleared, and the session is locked.
4. → **Welcome** (onboarding), as a fresh install.

**This destroys every stored card, permanently, with no export and no backup to restore from.** Two
dialogs stand in front of it for that reason.

⤷ The §1.4 forgotten-password route reaches the same wipe behind a **single** dialog, not these two.
A user who cannot get in has nowhere else to go, so only one gate stands in front of it there.

### 7.5 App version

Read-only, from the installed package. Nothing to interact with.

---

## 8. Interruptions

These are not workflows the user chooses — they are things that happen to them mid-task, and where
the app leaves them.

### 8.1 Backgrounding mid-task

| Where the user was | What happens with Lock on background ON |
|---|---|
| Filling in an Add or Edit form | The vault locks. Returning goes through both lock layers; unsaved form contents are lost. |
| Mid-save | The save reports "Vault is locked" rather than hanging — the Save button comes back rather than staying dead. |
| Scanning | The vault locks and the camera is released. Returning goes through both lock layers and lands on **Cards** — the banked votes and any timeout panel are gone, and a new scan starts from scratch. (With the setting **OFF**: the camera re-opens, banked votes are kept, and a timeout panel comes back as a panel rather than a live camera.) |
| Viewing a card with the CVV or number revealed | Both are re-hidden. In **Per action** mode the CVV needs re-authentication again. |

### 8.2 Rotating the screen

Every screen in the app is built to survive rotation: form state, the chosen colour, the card-type
toggle, the dirty flag, banked scan votes and the timed-out state all live somewhere that outlasts
the view.

Rotation is **not** treated as backgrounding. `MainActivity` tells a configuration change apart from
the app going away, so the vault stays unlocked, the user keeps their place, and a half-filled form
survives the turn intact (§6.1). This holds with **Lock on background** at its default ON — there is
no need to turn the protection off to use the app in landscape.

One rotation-related rough edge remains: the Snackbar carrying **Undo** after a scan does not
survive a rotation (§3.8).

### 8.3 Process death

If Android reclaims the process, the vault is locked no matter what the setting says — the key only
ever existed in memory and the Keystore alias goes with the session. The next launch is §1.2. No
card data is lost; anything unsaved in a form is.

### 8.4 The vault locking mid-action

Every path that needs the key checks for it and reports a distinct outcome rather than failing
silently:

| Path | Outcome when the key is gone |
|---|---|
| Saving a card | "Vault is locked" |
| Loading a card to edit | "Vault is locked", and the form closes |
| Decrypting a card to display | "Could not read card" |
| Decrypting the list | The tile still renders. Its number falls back to the bare mask ("••••") and its expiry to blank, rather than to garbage |

The scanner is the exception that needs nothing: the bank vocabulary it matches against comes from a
plaintext column, so scanning works whether or not the vault happens to be unlocked.

---

## 9. Workflow map

```
Launch
 ├── not onboarded ────────────► Welcome ─► Set password ─► Cards (empty)          §1.1
 └── onboarded, locked ────────► Biometric / device credential                     §1.2
        ├── no screen lock ────► dialog: settings | continue anyway | cancel       §1.3
        ├── user cancelled ───► app exits (silent)
        ├── system failure ───► dialog with Android's reason ─► app exits
        └── success ──────────► In-app password
               ├── wrong ─────► "Incorrect password"  (no lockout, retry freely)
               ├── forgot ────► confirm ─► WIPE ─► Welcome                         §1.4
               └── correct ───► Cards

Cards  (n / 30 · search · bank chips · settings)                                   §4
 ├── +  ── at 30? ──► "Card limit reached (30/30)"                                 §4.5
 │        └── sheet ─┬── Scan card ────────► Scanner                               §3.1
 │                   └── Enter manually ──► Add form                               §2.2
 ├── tap tile ─────────────────► Card detail                                       §5.1
 │        ├── tap number ─────► reveal / mask  (no re-auth)                        §5.2
 │        ├── Show / copy CVV ► biometric ─► (fallback) password ─► reveal / copy   §5.3
 │        ├── copy icons ─────► clipboard (+30 s auto-clear if sensitive)           §5.5
 │        ├── edit icon ──────► Edit form                                          §6.1
 │        └── delete ─────────► confirm ─► deleted ─► Cards                        §6.2
 ├── long-press tile ─────────► sheet ─┬── Reorder ─┬── search active ► refused    §4.4
 │                                     │            ├── filter active ► refused
 │                                     │            └── ok ► drag by handle ► Done
 │                                     ├── Edit ───► Edit form                     §6.1
 │                                     └── Delete ─► confirm ─► deleted            §6.2
 ├── search ──────────────────► nickname or last-4  (no match ► "No cards match")  §4.2
 ├── bank chips ──────────────► All | <bank> | Unknown                             §4.3
 └── settings ────────────────► Change password | Lock on background |             §7
                                CVV re-auth mode | Reset app | Version

Scanner  (torch · live hints · Enter manually always present)                      §3
 ├── permission not granted ──► system prompt; rationale only after a denial       §3.5
 ├── permanently denied ──────► "Camera is turned off" ─► Open settings | Manual    §3.5
 ├── no camera ───────────────► "No camera on this device" ─► Enter manually        §3.6
 ├── bind failed ────────────► "Camera didn't start" ─► Try again | Manual          §3.7
 ├── 3 agreeing frames per field ─► Number ✓ Expiry ✓ ─► auto-finish                §3.1
 │        └──► Add form pre-filled · cursor in CVV · Snackbar + Undo                §3.8
 └── 25 s with no lock ──────► "Couldn't read the card"                            §3.3
          ├── Try again ─────► camera restarts, votes kept
          ├── Use what was found (number locked only) ─► partial fill               §3.4
          └── Enter manually ─► Add form, empty

Add / Edit form                                                                    §2
 ├── camera icon in number field ─► Scanner ─► merge into this form                 §3.2
 ├── Save ─► nickname ► name ► 13–19 digits ► expiry shape ► not past ► CVV 3–4     §2.3
 │             └── checksum fails ─► "Save anyway" | "Let me check"                 §2.4
 └── Back while dirty ─────────► discard? ─► Cards | stay                           §2.5
```

---

## 10. What the user can never do

Worth stating plainly, because each of these is a deliberate design decision rather than a gap, and
each one shapes how the app should be used:

- **Recover a forgotten password.** The only exit is a full wipe (§1.4, §7.4).
- **Export, back up, or sync anything.** No file export, no cloud, no device-to-device transfer.
  Losing the phone loses the vault.
- **Scan the CVV.** Structurally impossible, not a setting (§3, `README.md` §4.10).
- **Store more than 30 cards** (§4.5).
- **Save a card with an expiry in the past** (§2.3).
- **Screenshot or screen-record any screen.** `FLAG_SECURE` is set on every activity.
- **Use autofill.** The app neither offers nor accepts autofill; the copy icons are the way to get a
  number into a browser.
- **Search by name on card, network, or card type.** Search is nickname or last-4 only; the one
  filter is by bank (§4.2, §4.3).
- **Undo a delete.**
- **Use a light theme, or an RTL layout.**

---

*Companion documents: `README.md` (user guide), `REQUIREMENTS.md` (specification, bug catalogue,
limitations), `SCAN_FEATURE_PLAN.md` (scanning design record).*

*Document version: 1.1 — 2026-08-29. Derived from the source at commit `3d310c1`, then corrected
against a full §1–§10 run on a Pixel 9 Pro emulator (API 37).*

*Changes from v1.0 → v1.1, all from that run: §1.1 an empty password reports the length error, not
"Required"; §1.5 / §7.2 / §8.2 rotation no longer locks the vault and backgrounding locks the instant
the user leaves (both fixed in `MainActivity`); §2.3 validation reports every failure at once and does
not move focus; §3.3 "Use what was found" also appears during the live scan, and a timeout panel does
**not** survive backgrounding with the default lock setting; §3.5 the first ask goes straight to the
system prompt with no rationale panel in front of it, answering that prompt does not lock the vault,
and **Open settings** re-locks the vault;
§4.2 last-4 search now works for every accepted PAN length; §5.3 the CVV can be hidden again from the
same button; §7.4 the second dialog's wording, and a note that the §1.4 route has only one gate;
§8.1 the scanning row under the default lock setting; §8.4 a failed list decrypt falls back to the
bare mask, not to an empty string.*
