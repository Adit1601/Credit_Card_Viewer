# Card Scan — Implementation Plan

**Status:** code complete — **Phases 0-9 all built.** Everything is implemented, compiles in debug and release, and passes the static verification below. What remains is entirely **on-device**: nothing here has been run against a real camera or a real card, so the recognition-quality gates for Phases 4 and 7 are unverified by construction. See "Outstanding device checks" at the end of the build log.

**Date:** 2026-08-28
**Target:** Card Vault v1.1

### Build log

| Phase | State | Notes |
|---|---|---|
| 0 — De-risk dependency | **done** | R8 verified, no keep rules needed. Size resolved via ABI splits (14.32 MiB arm64). ML Kit's injected `INTERNET` stripped and verified absent. One device check still open. |
| 1 — Luhn on the form | **done** | `util/Luhn.kt`, 18 tests green. 4 manual on-device checks outstanding (see gate). |
| 2 — Pure parser | **done** | 5 files in `scan/`, 49 tests green. Two design rules added during implementation, recorded below. |
| 3 — Camera scaffolding | **done** | `ui/scan/CardScanFragment.kt` (preview only), 5 states all keeping "Enter manually". Release verified: `CAMERA` in, network still out, R8 keeps the fragment with no hand-written rule. Package moved to `ui/scan` to keep `scan/` Android-free. No entry point yet (Phase 6), so the device pass is blocked. |
| 4 — Analyzer + accumulator | **done (device gate open)** | `ImageAnalysis` @720p, `KEEP_ONLY_LATEST`, single-thread executor, ~4 fps throttle, 25 s timeout. Checklist renders from `CardScanViewModel.progress`. **Gate "PAN + expiry lock within a few seconds" cannot be checked here — needs a real card.** |
| 5 — Result plumbing | **done** | `applyScan()` in `AddEditCardFragment`, two delivery routes, Undo snackbar, Luhn evaluated on apply, `cvvInput` never written. Re-apply guard is `AddEditCardViewModel.scanApplied`. |
| 6 — Entry points | **done** | Home add button → bottom-sheet chooser (`dialog_add_card_choice.xml`), after the MAX_CARDS gate. Camera end-icon on the PAN field, in add *and* edit. |
| 7 — Torch + low-light | **done (device gate open)** | Torch gated on `hasFlashUnit()`, icon driven from `CameraInfo.torchState`. Low-light from sparse mean-luma with hysteresis (62/82) + a 3-sample streak. **Thresholds are guesses until measured on real hardware.** |
| 8 — Hardening | **done** | Invariants moved from a manual grep to a build-time gate: `./gradlew :app:verifyScanInvariants`, wired into `preBuild`. Verified it actually fails by injecting a `Log.d`, an `ImageCapture`, and an `android.util.Log` import into `scan/`. Checklist labels + fallback panel made font-scale safe; TalkBack announces each field as it locks. |
| 9 — Docs | **done** | `REQUIREMENTS.md` §13 (new) + §1.4/§9/§10/§4.2/§12 updated; `README.md` §3.3.1 + §4.10-4.12 + §5 + §7. Two README claims that scanning falsified were corrected: "no native `.so` libraries" and "< 20 MB installed". |

**Final verification (2026-08-28, static only)**

| Check | Result |
|---|---|
| `:app:assembleDebug` | clean |
| `:app:assembleRelease` | clean, `verifyScanInvariants` ran as part of it |
| `:app:testDebugUnitTest` | 110 tests, 3 failed — the same 3 pre-existing `CardNetworkDetectorTest` reds from the audit. No new failures, count unchanged |
| `:app:verifyScanInvariants` | pass; **and proven non-vacuous** — injecting a `Log.d`, an `ImageCapture.Builder()` and an `import android.util.Log` into `scan/` produced 3 correctly-attributed failures |
| Release APK permissions (`aapt2 dump permissions`) | `USE_BIOMETRIC`, `USE_FINGERPRINT`, `CAMERA`. **No `INTERNET`, no `ACCESS_NETWORK_STATE`** |
| Release APK `uses-feature` | `android.hardware.camera` and `.autofocus`, both `required=false` |
| R8 mapping | `CardScanFragment` self-maps unrenamed (nav graph references it by name). `ScanResultBridge` is removed as a class but both its methods are inlined into the two call sites — verified in `mapping.txt`, so the wiring survives |
| APK size | 14.60 MiB arm64-v8a / 10.51 MiB armeabi-v7a / 21.11 MiB universal (+0.14 MiB over Phase 3) |

**Outstanding device checks — none of these can be done from here**

1. **Recognition quality.** Does a real card lock PAN + expiry in a few seconds? This is the
   feature's whole premise and it is untested. The 49 parser tests run against hand-written
   fixtures, not against what ML Kit actually emits for a card.
2. **Play-Services-free image.** The only AVD available is `google_apis_playstore`. The bundled-model
   claim was verified statically in Phase 0 (zero `GoogleApiAvailability`/`DynamiteModule` refs) but
   not by running on an image without Play Services.
3. **First-scan logcat.** Watch for a `transport-runtime` / `ConnectivityManager` `SecurityException`
   from ML Kit's telemetry uploader hitting the stripped `INTERNET` permission. Expected to be
   caught internally by the library; needs confirming it is not fatal.
4. **Luma thresholds (62 / 82).** Picked by reasoning about YUV mean values, not measured. Verify
   the low-light hint appears in a dim room and not in a normally lit one.
5. **Timeout feel.** 25 s: long enough for a difficult card, short enough not to cook the phone.
6. **Phase 1's 4 manual Luhn checks** and **Phase 3's 6 on-device state checks**, both still open.
7. **Torch on a device with no flash** (front-camera-only tablet): button must be absent, not inert.

**Rules added during Phase 2** (not in the original plan, both forced by fixtures):

1. **No PAN, no output.** `parse` returns `ScanCandidate.EMPTY` unless a PAN clears the score
   floor. Without this, the "frame with no card in it" fixture returned `nameOnCard = "GROCERY
   STORE"` — two uppercase words pass every name heuristic. Every other field is only meaningful
   as part of a card, so gating on the PAN is what makes a miss return nothing rather than
   something plausible and wrong.
2. **Name blocklisting is token-wise, not substring-wise.** Squashed-substring matching
   false-rejects real names (a holder called GOLDIE contains `GOLD`). Single words are matched as
   whole tokens; a short phrase list covers wording that survives tokenisation.

**Known-red, pre-existing, unrelated:** 3 tests in `CardNetworkDetectorTest`
(`discover_622125_belowBound`, `discover_622926_aboveBound`, `progressive_60_thenDiscoverOn6011`).
Verified during Phase 2 to be **wrong test expectations, not detector bugs** — all three assert
`RUPAY` where the documented longest-prefix rule table yields `UNKNOWN`. The detector itself
behaves as specified, so §5.1's use of it as a plausibility oracle stands. The gap it does have
(the `62xxxx` space resolves to `UNKNOWN`) is covered by
`CardScanParserTest.unrecognisedNetworkPrefix_stillScans`, which pins that such a card still
clears the score floor without the network bonus.

---

## 1. Scope

Add a camera-based scan flow that reads a physical card and **pre-populates the Add-card
form**, which the user then corrects and completes by hand.

**Extracted:** PAN, expiry (MM/YY), name on card, issuing bank.
**Never extracted:** CVV / CVC / Amex CID. See §6 — this is enforced structurally, not by convention.

### Non-goals

- No CVV capture of any kind, on either side of the card.
- No image storage. No frame, crop, or OCR string is ever written to disk or to a log.
- No network. The app has no `INTERNET` permission and this feature does not add one.
- No gallery / "scan from a photo" import in v1 (the system photo picker is out-of-process — see §3.1).
- Card type (Debit/Credit/Prepaid) is **not** auto-detected in v1 (deferred per review).
- Nickname is **not** auto-suggested in v1 (deferred per review).

### Confirmed decisions (from review)

| Decision | Choice |
|---|---|
| OCR engine | ML Kit Text Recognition v2, **bundled** model (`com.google.mlkit:text-recognition`) |
| Entry points | Both — Home bottom sheet (primary) + camera end-icon on the card-number field |
| Luhn | Yes — soft warning on the **add/edit form** (audit enhancement §4-6), **and** reused internally for PAN selection. Ships as an independent slice: Phase 1, no camera involved. |
| Torch + low-light hint | Yes |
| Card-type detection | No (deferred) |
| Nickname auto-suggest | No (deferred) |
| Lock-on-background hardening | No code change; rely on entry-point placement |

---

## 2. Why this shape — constraints found in the existing code

These are not preferences; they are forced by code already in the repo.

### 2.1 The scanner MUST be in-process

`MainActivity.kt:52-60`:

```kotlin
override fun onStop() {
    super.onStop()
    if (isFinishing) return
    if (prefs.getLockOnBackground()) {
        SessionManager.lock()          // drops master key AND deletes the Keystore alias
    }
}
```

Any scanner that lives in another process — Google Pay's `PaymentCardRecognitionIntent`,
`ACTION_IMAGE_CAPTURE`, or `ActivityResultContracts.PickVisualMedia` — backgrounds
`MainActivity`, which destroys the session key and bounces the user to
`BiometricLockActivity` with their form contents gone. The codebase already reasons about
exactly this hazard: `CvvBiometricPrompt` deliberately refuses `DEVICE_CREDENTIAL` for the
same reason.

**Consequence:** an in-app CameraX fragment inside `main_nav.xml` is the only viable design.
It never leaves `MainActivity`, so `onStop` never fires and the vault never locks mid-scan.

### 2.2 The CVV requirement is already structurally enforced

`AddEditCardFragment.kt:309`:

```kotlin
if (cvvDigits.length !in 3..4) { cvvLayout.error = ...; ok = false }
```

CVV is a hard save-time requirement. A scan therefore **cannot** produce a saveable card
without the user typing the CVV manually. The "don't scan the CVV" requirement is backed by
the form's own validation, not just by the parser's good behaviour.

### 2.3 The scanner never needs the master key

`issuingBank` is a **plaintext** column (`CardDao.kt:27` queries it directly;
`AddEditCardViewModel.loadIfEditing` reads `entity.issuingBank` with no `decrypt` call). The
bank matcher can therefore read the user's existing banks without touching
`SessionManager.requireKey()`. The scan package will have **zero** references to
`CryptoManager` or `SessionManager` — it cannot leak a key it never holds.

### 2.4 FLAG_SECURE is inherited for free

`MainActivity.onCreate` sets `FLAG_SECURE` on the window before `super.onCreate`. A scan
fragment hosted in that activity inherits it, so the live camera preview of the user's card
cannot be screenshotted or screen-recorded. No extra work — but see §9 for the SurfaceView
caveat.

### 2.5 Existing patterns to reuse, not reinvent

- `BottomSheetDialog` + an inflated layout: already the pattern in
  `HomeFragment.showActionsSheet` / `dialog_card_actions.xml`. The scan/manual chooser copies it.
- `CardNetworkDetector.detect()` works on any prefix and returns `UNKNOWN` for junk — it is a
  ready-made **PAN plausibility oracle**, not just a logo picker.
- `CardFormatting.formatPanForDisplay` / `formatExpiry` already turn raw digits into display
  form. The scanner emits **raw digits only** and lets the existing watchers format.
- `BankSuggestions.combined(userBanks)` is already the canonical bank vocabulary. The matcher
  resolves to entries from this list so scanned banks collapse onto existing
  `bankFilterOptions` chips instead of creating near-duplicates.
- `suppressWatchers` + explicit preview refresh: `applyInitial` (`:266-292`) is the exact
  template for applying scanned values without re-triggering the text watchers.

---

## 3. Architecture

```
                      ┌──────────────────────────────────────────┐
  HomeFragment        │ scan/ (pure Kotlin — no Android imports) │
   [+] ──┐            │                                          │
         │  bottom    │  (imports util.Luhn — Phase 1)           │
         │  sheet     │  NumericNormalizer.kt   (O→0, I→1, S→5)  │
         ├─ manual ──►│  BankMatcher.kt                          │
         │            │  CardScanParser.kt                       │
         └─ scan ────►│    OcrFrame ─► ScanCandidate             │
                      │  ScanAccumulator.kt   (multi-frame vote) │
  AddEditCardFragment └──────────────────────────────────────────┘
   [📷] end-icon                      ▲
         │                            │ OcrFrame
         │                            │
         ▼                    ┌───────┴────────────────┐
  CardScanFragment ──────────►│ MlKitTextSource        │
   (CameraX PreviewView,      │ (ImageAnalysis only —  │
    torch, overlay, hint)     │  never ImageCapture)   │
         │                    └────────────────────────┘
         │ ScanResult via NavBackStackEntry.savedStateHandle
         ▼
  AddEditCardFragment.applyScan()
```

The split matters: **everything with logic is pure Kotlin and JVM-unit-testable**;
everything Android-shaped is a thin adapter with no branching worth testing. This is what
lets §8 exist at all, and it directly addresses the project's current thin test coverage.

### 3.1 Why no gallery import

`PickVisualMedia` needs no permission, but it *is* out-of-process → §2.1 applies → vault
locks. Deferred. If wanted later, the honest fix is a same-process document reader, not the
system picker.

---

## 4. Files

### New — pure Kotlin (`app/src/main/java/com/cardvault/scan/`)

| File | Contents |
|---|---|
| `OcrModels.kt` | `OcrLine(text, left, top, right, bottom)`, `OcrFrame(lines, width, height)`, `ScanCandidate(panDigits?, expiryDigits?, nameOnCard?, issuingBank?)`. Plain data classes, no `android.graphics.Rect`. |
| `NumericNormalizer.kt` | Digit-context OCR repair: `O`/`o`/`Q`→`0`, `I`/`l`/`|`→`1`, `S`→`5`, `B`→`8`, `Z`→`2`, `G`→`6`. Applied only to runs already dominated by digits. |
| `BankMatcher.kt` | `fun match(lines: List<String>, vocabulary: List<String>): String?` — returns the **canonical** spelling. |
| `CardScanParser.kt` | `fun parse(frame: OcrFrame, bankVocabulary: List<String>): ScanCandidate`. The whole extraction algorithm (§5). |
| `ScanAccumulator.kt` | Per-field vote counting across frames; locks a field at N agreements; `isSettled()`. |

### New — shared util (`app/src/main/java/com/cardvault/util/`)

| File | Contents |
|---|---|
| `Luhn.kt` | `fun isValid(digits: String): Boolean` — mod-10 checksum. Lives in `util`, **not** `scan`, because two independent callers need it: the add/edit form (Phase 1) and the PAN scorer (§5.1). |

### New — Android

| File | Contents |
|---|---|
| `ui/scan/MlKitTextSource.kt` | Wraps `TextRecognizer`; maps `Text` → `OcrFrame`; owns `close()`. |
| `ui/scan/CardScanFragment.kt` | CameraX lifecycle, permission handling, `ImageAnalysis` analyzer, overlay state, torch, timeout, result plumbing. |
| `ui/scan/CardScanViewModel.kt` | Holds the `ScanAccumulator` across config changes; loads `bankVocabulary` via `CardRepository.distinctIssuingBanks()`. |

> **Package correction (made during Phase 3).** These three were originally listed under
> `com.cardvault.scan`. They now live in **`com.cardvault.ui.scan`**, for two reasons.
> First, `com.cardvault.scan` is pure-JVM by Phase 2's invariant — no `android`/`androidx`/
> `com.google` imports, no `Log.*` — and §11's grep gate enforces that by *path*. Putting an
> Android fragment in the same directory would have made the gate un-runnable as written.
> Second, every other fragment in this codebase lives at `ui/<feature>/`
> (`addedit`, `auth`, `detail`, `home`, `onboarding`, `settings`), so `ui/scan/` is where a
> reader will look for it. The pure-Kotlin table above is unchanged: `com.cardvault.scan` stays
> exactly as specified and stays Android-free.
| `res/layout/fragment_card_scan.xml` | `PreviewView` + reticle + 4-field checklist overlay + torch button + "Use what you have" + "Enter manually". |
| `res/layout/dialog_add_card_choice.xml` | Two-button sheet, modelled on `dialog_card_actions.xml`. |
| `res/drawable/ic_camera.xml`, `ic_torch_on.xml`, `ic_torch_off.xml`, `ic_keyboard.xml`, `scan_reticle.xml` | Vector assets. |

### New — tests (`app/src/test/java/com/cardvault/scan/`)

`NumericNormalizerTest.kt`, `BankMatcherTest.kt`, `CardScanParserTest.kt`,
`ScanAccumulatorTest.kt`, plus `ScanFixtures.kt` holding hand-authored `OcrFrame` fixtures.

And `app/src/test/java/com/cardvault/util/LuhnTest.kt`, alongside the existing `ExpiryUtilTest`.

### Modified

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | CameraX + ML Kit entries |
| `app/build.gradle.kts` | 5 new `implementation` lines |
| `app/src/main/AndroidManifest.xml` | `CAMERA` permission + optional `uses-feature` |
| ~~`app/proguard-rules.pro`~~ | **Not needed.** Phase 0 proved the AARs' consumer rules suffice; Phase 3 confirmed the fragment itself needs no rule either (see the Phase 3 results below). File is untouched. |
| `app/src/main/res/navigation/main_nav.xml` | `cardScanFragment` destination + **3** actions (`action_home_to_scan`, `action_add_to_scan`, `action_scan_to_add`) — the original count of 2 omitted the route *out* of the scanner |
| `app/src/main/java/com/cardvault/ui/home/HomeFragment.kt` | `onAddCardClicked` → chooser sheet |
| `app/src/main/java/com/cardvault/ui/addedit/AddEditCardFragment.kt` | Phase 1: Luhn focus-loss warning + save-time confirm. Phase 5: `applyScan()` + result observer. Phase 6: scan end-icon |
| `app/src/main/res/layout/fragment_add_edit_card.xml` | `app:endIconMode="custom"` + camera drawable on `panLayout` |
| `app/src/main/res/values/strings.xml` | ~18 new strings |
| `REQUIREMENTS.md` | New §9 "Card scan"; note the CAMERA permission in §1.4 |
| `README.md` | Scan section; permission disclosure |

---

## 5. The parsing algorithm

This is the part that decides whether the feature feels good or feels broken, so it is
specified rather than left to implementation.

### 5.1 PAN

1. Per `OcrLine`, build candidate digit strings three ways: (a) the line's own digits,
   (b) digits of adjacent lines merged left-to-right by x-order (ML Kit sometimes splits
   `4111 1111` / `1111 1234` into separate lines), (c) the line after `NumericNormalizer`.
2. Keep candidates of length 13–19.
3. Score each:
   - `+100` passes **Luhn**
   - `+50` `CardNetworkDetector.detect(c) != UNKNOWN`
   - `+25` length is 15 or 16
   - `+20` source line splits into 3–4 groups of 4–6 digits
   - `+15` line's glyph height is in the top quartile for the frame (the PAN is the largest text)
   - `−40` source line contains ≥3 alphabetic characters
4. Highest score above a floor of 120 wins — i.e. **Luhn alone is not enough**; a plausible
   network prefix or strong layout evidence is also required.

Reusing `CardNetworkDetector` here is the highest-leverage decision in the whole plan: it is
already correct, already unit-tested, and it eliminates most false positives for free.

### 5.2 Expiry

1. Match `(?<!\d)(0[1-9]|1[0-2])\s*[/\-–]\s*(\d{2}|\d{4})(?!\d)` per line. 4-digit years → last two.
2. **Collect every match, not the first.** Indian debit cards commonly print
   `VALID FROM 05/22` *and* `VALID THRU 05/27` — taking the first gives the user a card that
   expired years ago.
3. Rank: `+30` line contains `THRU`/`THROUGH`/`EXPIRES`/`GOOD THRU`; `−30` line contains
   `FROM`/`SINCE`/`MEMBER`; tie-break on the **chronologically later** date.
4. Discard any match whose digits are a substring of the chosen PAN.

### 5.3 Name on card

1. Candidate lines: only letters, space, `.`, `-`, `'`; length 5–26; ≥2 tokens (or 1 token of
   ≥5 chars); ≥60% of letters uppercase.
2. Reject on a normalized-contains blocklist:
   - Networks — `VISA MASTERCARD MAESTRO RUPAY AMERICAN EXPRESS DISCOVER DINERS JCB`
   - Tiers/types — `DEBIT CREDIT PREPAID PLATINUM GOLD TITANIUM SIGNATURE INFINITE WORLD SELECT CLASSIC BUSINESS CORPORATE INTERNATIONAL CONTACTLESS`
   - Furniture — `VALID THRU FROM EXPIRES GOOD MEMBER SINCE AUTHORIZED SIGNATURE CUSTOMER SERVICE CARDHOLDER NAME BANK LTD LIMITED PVT WWW COM TOLL FREE HELPLINE CVV CVC`
   - Month names
3. Reject the line already consumed as the bank name.
4. Positional prior: `+30` if the line's `top` is below the PAN line's `bottom`; `+15` if in
   the bottom third of the frame. Names sit low on the card face.
5. Highest score wins; emit as printed (the form's own watcher uppercases the preview).

### 5.4 Issuing bank

1. Vocabulary = `BankSuggestions.combined(distinctIssuingBanks())`.
2. Normalize both sides: uppercase, strip non-alphanumerics, drop a trailing
   `BANK`/`LIMITED`/`LTD`.
3. Exact normalized containment in either direction wins.
4. Otherwise fuzzy: Levenshtein on normalized forms, with a **length-sensitive** threshold —
   `≤1` for tokens under 6 characters, `≤2` for 6+.
   > This is not arbitrary. `HDFC` and `HSBC` are both 4 characters and are Levenshtein
   > distance **2** apart. A flat `≤2` threshold would silently mislabel HDFC cards as HSBC —
   > on an India-first app, that is the single most likely wrong answer this feature could give.
   > `BankMatcherTest` gets a named test for it.
5. **Return the canonical spelling from the vocabulary**, never the raw OCR text, so a scanned
   card lands on the same `bankFilterOptions` chip as a hand-typed one.

### 5.5 Multi-frame accumulation

Single-frame OCR of an embossed card is unreliable; voting across frames is what makes this
usable.

- `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`, throttled to ~4 fps.
- `ScanAccumulator.offer(candidate)` increments a per-field vote for the exact value.
- A field **locks** at 3 identical votes (PAN additionally requires Luhn to have passed).
- Overlay ticks each field as it locks: `Number ✓  Expiry ✓  Name ✓  Bank ✓`.
- Auto-finish when PAN **and** expiry are locked. Otherwise: "Use what you have" is enabled
  as soon as the PAN locks, and a 25 s timeout offers manual entry.

---

## 6. CVV suppression — how "never the CVV" is guaranteed

Not a convention. Five independent mechanisms:

1. **`ScanCandidate` has no CVV field.** The parser's return type makes a CVV unrepresentable.
2. **Bare 3–4 digit runs are never emitted.** A digit run only becomes output as a PAN (13–19
   digits) or as an expiry (which requires a `MM/YY` separator *and* a month in 01–12). A
   standalone `123` or `4567` has no path to any output field.
3. **Amex front CID is covered by the same rule.** Amex prints a 4-digit CID on the *front*,
   above the PAN — so this is not a hypothetical. `CardScanParserTest` gets an explicit
   `amex_frontCid_isNeverEmitted` fixture.
4. **Back-of-card scanning is safe by construction.** Modern cards increasingly print the PAN
   on the reverse, next to the CVV. Rules 1–3 mean the scanner can read whichever side has the
   number without the CVV ever becoming output — so no "front only" restriction is needed.
5. **Nothing is retained.** Only `ImageAnalysis` is ever bound to the camera — `ImageCapture`
   and `VideoCapture` are never constructed, so no code path exists that could write a file.
   `ImageProxy.close()` in a `finally`; recognizer `close()`d and accumulator cleared in
   `onDestroyView`; **no `Log.*` calls anywhere in `com.cardvault.scan`**, enforced by a grep
   check in Phase 8.

---

## 7. Phases

Ordered so the riskiest unknown is settled first and the highest-value code is written second.

### Phase 0 — De-risk the dependency (½ day, throwaway)

Before writing anything real, confirm the engine choice survives contact.

1. Add CameraX + ML Kit to the version catalog:
   ```toml
   camerax   = "1.3.4"   # NOT 1.4.x — that line wants compileSdk 35; this project is on 34
   mlkitText = "16.0.1"
   ```
2. Build a scratch activity that OCRs one frame and logs the block count.
3. **Verify and record:**
   - APK size delta (`./gradlew assembleRelease`, compare before/after). Expect ~+5–6 MB.
   - Runs on a device/emulator image **without** Google Play Services (bundled ML Kit is
     documented to, but this is the assumption the whole choice rests on — prove it).
   - Release build with R8 on actually works, not just debug.
4. If any check fails, stop and re-open the engine decision before sinking effort into §5.

**Gate:** all four verified, numbers written into this document.

#### Phase 0 results (measured 2026-08-28)

Versions as planned: `camerax = "1.3.4"`, `mlkitText = "16.0.1"`, five `implementation` lines.
Verified on the **release** build (`isMinifyEnabled = true`, `isShrinkResources = true`).

**a. R8 / release build — PASS, and no keep rules were needed.**

`assembleRelease` succeeds. The consumer ProGuard rules shipped inside `text-recognition` and
`text-recognition-bundled-common` already cover the native-method and proto-field keeps, so
`proguard-rules.pro` is **unchanged** — nothing was added on faith.

The failure mode that actually mattered here is not the native code, it is reflection. ML Kit
registers its components by *literal class name* in manifest `meta-data`, so if R8 renamed them
initialisation would die with `ClassNotFoundException` at first use. Checked against
`mapping.txt`, all three are kept unrenamed and still match the names in the shipped manifest:

| Reflectively-loaded class | R8 outcome |
|---|---|
| `com.google.mlkit.vision.text.internal.TextRegistrar` | kept, not renamed |
| `com.google.mlkit.vision.common.internal.VisionCommonRegistrar` | kept, not renamed |
| `com.google.mlkit.common.internal.CommonComponentRegistrar` | kept, not renamed |

`BundledTextRecognizerCreator` survives, and `libmlkit_google_ocr_pipeline` /
`libimage_processing_util_jni` / `loadLibrary` are all present in `classes.dex`. `TextRecognition`
itself shows as `R8$$REMOVED$$CLASS$$399` — that is R8 inlining a static-only factory into the
call site, not breakage.

**b. APK size — FAIL against the plan's estimate. This is the gate trip.**

Baseline (before the two dependencies): **2,249,781 bytes (2.15 MiB)**, one `classes.dex`, and
notably **zero native libraries**. ML Kit introduces the APK's first `.so` files.

| Artifact | Size | Delta vs baseline |
|---|---|---|
| Baseline universal APK | 2,249,781 B (2.15 MiB) | — |
| Universal APK, all 4 ABIs | 45,135,486 B (43.04 MiB) | **+40.90 MiB** |
| Split APK, `arm64-v8a` (measured) | 15,246,046 B (14.54 MiB) | **+12.39 MiB** |
| Split APK, `armeabi-v7a` (measured) | 10,963,446 B (10.46 MiB) | **+8.31 MiB** |
| AAB (upload artifact, not a download size) | 22,000,228 B (20.98 MiB) | — |

Where it goes, per ABI (compressed):

| ABI | `libmlkit_google_ocr_pipeline.so` | total native |
|---|---|---|
| `arm64-v8a` | 11,064,544 B | 10.58 MiB |
| `armeabi-v7a` | 6,781,940 B | 6.49 MiB |
| `x86` | 11,561,048 B | 11.06 MiB |
| `x86_64` | 11,626,128 B | 11.13 MiB |

Plus **1.28 MiB of `assets/mlkit-google-ocr-models/`** (the `.tflite` / `.fb` model files — the
price of "bundled") and a modest dex increase (981,049 → 1,178,113 B compressed).

So the plan's "~+5–6 MB" was low by roughly 2×: the real per-device cost is **+12.4 MiB on
arm64**, and the *universal* APK — which is what `assembleRelease` produces today and what
sideloading a single APK means — grows to **43 MiB**, a 20× increase. The x86 ABIs account for
22 MiB of that and are only ever used by emulators.

**Decision taken (2026-08-28): ABI splits, x86 pair dropped.** Implemented in
`app/build.gradle.kts`. Two knobs are required, which is worth knowing because the first one alone
looks like it works and doesn't:

  - `splits { abi { include("arm64-v8a", "armeabi-v7a"); isUniversalApk = true } }` — produces the
    per-ABI outputs.
  - `defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }` — **also needed.**
    `splits.abi.include` filters only the *split* outputs; the universal APK ignores it and still
    packaged all four ABIs at 43.04 MiB until `abiFilters` kept x86 out of the build entirely.

Resulting release outputs, ABI contents verified by listing `lib/*` in each:

| Output | Size | Contains |
|---|---|---|
| `app-arm64-v8a-release.apk` | 15,016,634 B (**14.32 MiB**) | `arm64-v8a` |
| `app-armeabi-v7a-release.apk` | 10,734,034 B (**10.24 MiB**) | `armeabi-v7a` |
| `app-universal-release.apk` | 21,842,568 B (20.83 MiB) | both arm ABIs — fallback only |

x86/x86_64 dropped: they were 22 MiB of the original 43, no shipping Android phone uses them, and
this machine's only emulator image is `arm64-v8a`. If an x86_64 emulator is ever needed, add it to
**both** knobs. Permissions re-verified on the split APK — still three, none of them network.
Splits apply to debug too (`installDebug` picks the matching output; verified resolvable).

The alternatives, for the record:
  - **Unbundled ML Kit** (`play-services-mlkit-text-recognition`) would keep the APK near
    baseline, but requires Google Play Services and a first-run model download — which defeats
    both the offline property and gate (c). Not recommended.
  - **Accept 43 MiB universal.** Rejected — 22 MiB of it was emulator-only ABIs.

**c. Works without Google Play Services — PASS (static evidence; one device check still open).**

Stronger than expected. Across all **2,858** classes in `mlkit:common`, `vision-common`,
`vision-interfaces`, `play-services-mlkit-text-recognition-common` and
`text-recognition-bundled-common`, there are **zero** references to `GoogleApiAvailability`,
`DynamiteModule`, or `isGooglePlayServicesAvailable`. Nothing queries whether Play Services is
installed, so there is no code path that can gate on its absence.

Worth recording how the artifact is actually laid out, because the naming is misleading:

| Artifact | Classes | Role |
|---|---|---|
| `com.google.mlkit:text-recognition:16.0.1` | 1 | aggregator only |
| `play-services-mlkit-text-recognition:19.0.1` | 2 | supplies `TextRecognizerOptions` — *not* the engine |
| `text-recognition-bundled-common:17.0.0` | 1,200 | **the real local engine**: native pipeline + tflite assets |

The `play-services-*` artifacts come in as *libraries* (`com.google.android.gms.tasks.Task` is
ML Kit's async type), not as a requirement that the Play Services APK be present.

Still open, and it needs hardware: actually running a recognition on a GMS-less image. The static
evidence says it will work; it is not the same as having seen it work.

**d. Unlisted risk found: ML Kit adds `INTERNET`. Fixed.**

Not in the plan and more serious than the size. The merged manifest gained:

```
uses-permission android:name="android.permission.INTERNET"
uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"
```

Blame, from `manifest-merger-release-report.txt`: **`com.google.android.datatransport:transport-backend-cct:2.3.3`**
— Google's Cloud Client Telemetry uploader, pulled in transitively by `com.google.mlkit:common`
for usage logging. A card vault whose selling point is "zero network permissions" must not
silently start requesting the internet.

Aggravating detail: `MlKitInitProvider` is a `ContentProvider` with `initOrder="99"`, so ML Kit
initialises at **process start** — app-wide, not just while the scan screen is open.

Fixed in `app/src/main/AndroidManifest.xml` by stripping both permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Removing the *permission* rather than excluding the *dependency* is deliberate. ML Kit's own
classes reference `TransportFactory`, `Transport`, `Transformer` and `Encoding` directly (confirmed
in their constant pools), so `exclude(group = "com.google.android.datatransport")` would risk
`NoClassDefFoundError` on the OCR path. Leaving the classes present but denying the capability is
the safer half: the telemetry code links, and has no network to reach.

There is no supported ML Kit opt-out to use instead — the artifacts contain no logging-disable
`meta-data` key.

Verified against the **shipped APK binary**, not just the intermediate — `aapt2 dump permissions`:

```
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.USE_FINGERPRINT'
uses-permission: name='com.cardvault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

Zero network permissions. The property holds.

**Residual risk for Phase 3's device pass:** the telemetry code will now attempt to upload and be
refused by the OS. `transport-runtime` consults `ConnectivityManager`, which without
`ACCESS_NETWORK_STATE` throws `SecurityException`. ML Kit logs on a background executor and
neither ML Kit nor its vision libraries touch `ConnectivityManager` themselves, so this should
stay off the recognition path — but "should" is doing real work in that sentence. **Watch logcat
during the first device scan and confirm recognition completes and nothing crashes.**

**e. Regression check.** Full suite after adding the dependencies: **110 tests, 3 failed** — the
same three pre-existing `CardNetworkDetectorTest` cases that were already red before Phase 0
(wrong test expectations, not detector bugs). No new failures.

**Gate verdict: 3 of 4 pass; the size check fails its estimate and needs a distribution decision.**
The engine choice itself still holds — ML Kit is genuinely local and genuinely Play-Services-free,
which were the load-bearing assumptions. What changed is the price. Two things to settle before
Phase 3: the ABI/distribution question in (b), and the device confirmation in (c)/(d).

**Spike artefact:** `com/cardvault/spike/OcrSpikeActivity.kt` exercised the real CameraX +
ML Kit surface (`ProcessCameraProvider`, `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`,
`PreviewView.ImplementationMode.COMPATIBLE`, `InputImage.fromMediaImage`,
`TextRecognition.getClient`) and compiled clean against both. It was registered in the manifest so
R8 could not shrink it away, measured, then deleted along with its manifest entry — the tree is
back to no `spike` package. The API surface Phase 4 needs is confirmed to compile.

### Phase 1 — Luhn on the add/edit form (½ day) ← ships independently

Standalone: **no camera, no ML Kit, no dependency on Phase 0.** This is audit enhancement §4-6,
and it can be reviewed, merged and released entirely on its own before any scan work begins.
The scanner then imports it rather than owning it.

1. **`util/Luhn.kt`** — pure Kotlin, in `util` and not `scan`, because two unrelated callers
   need it. Returns `false` for anything that isn't 13–19 digits, so callers never pre-check
   length.

2. **Focus-loss warning.** An `OnFocusChangeListener` on `panInput`: when focus leaves, if the
   field holds 13–19 digits and `!Luhn.isValid(...)`, set an amber `panLayout.helperText`;
   clear it on the next edit.
   - **Not on every keystroke.** A partially typed PAN fails Luhn almost always, so a live
     check would keep the field flagged the entire time the user is typing — the classic way to
     make this feature feel broken. Focus-loss is the earliest honest moment to judge.
   - **`helperText`, not `error`.** `error` is reserved for the hard length block at `:306`,
     and `TextInputLayout` hides helper text while an error is showing, so the two can never
     collide on screen.
   - **No warning on load.** `applyInitial` (`:266-292`) runs with `suppressWatchers = true`
     and never moves focus, so opening the edit screen on an already-saved card that fails Luhn
     stays silent until the user actually touches the field. Don't nag about stored data the
     user didn't just type.

3. **Save-time confirm.** The gate slots between the existing `if (!ok) return` (`:310`) and
   `saveButton.isEnabled = false` (`:312`), so hard validation still wins and the user never
   gets a checksum dialog stacked on top of "nickname required":

   ```
   clearErrors() → read fields → hard validation → if (!ok) return
                 → [NEW: Luhn gate] → saveButton.isEnabled = false → viewModel.save(...)
   ```

   On failure, an `AlertDialog` — the pattern `attemptExit` (`:352-362`) already uses:

   > **Check the card number**
   > This number's checksum doesn't look right, which usually means one digit is off.
   > \[ Save anyway ]  \[ Let me check ]

   "Save anyway" proceeds and sets a `luhnAcknowledged` flag so the dialog appears at most once
   per form session. "Let me check" returns focus to `panInput`.

4. **Soft, never blocking.** Real numbers do fail Luhn — issuer test PANs, some
   disposable/virtual numbers, gift and private-label cards. A hard block would leave the user
   unable to store a card they legitimately hold, which is a worse failure than a wrong digit.

Because add and edit are the *same fragment*, this one change covers both screens.

**Gate:** `LuhnTest` green; a bad checksum warns on focus-loss and once more on save, then
saves on confirm; a valid card is completely silent; opening edit on a Luhn-failing saved card
shows nothing until the field is touched.

### Phase 2 — Pure parser + tests (2–3 days) ← the real work

Implement `NumericNormalizer`, `BankMatcher`, `CardScanParser`, `ScanAccumulator` and their
tests, reusing `util.Luhn` from Phase 1. **No Android, no camera, no UI.** Fixtures are hand-authored `OcrFrame`s
transcribed from real card layouts.

Fixtures to include (`util.Luhn` already exists from Phase 1):
- Visa 16-digit, 4×4 groups, name below PAN
- Amex 15-digit **with a 4-digit front CID** (must not appear in output)
- RuPay 16-digit with both `VALID FROM` and `VALID THRU` (later date must win)
- A back-of-card layout with PAN, expiry **and CVV** present (CVV must not appear)
- OCR-garbled digits (`O`→0, `I`→1, `S`→5) that only pass Luhn after normalization
- `HDFC BANK` vs `HSBC` disambiguation
- Bank name printed in mixed case; bank name absent entirely
- A frame with no card in it at all (must return an all-null `ScanCandidate`, not garbage)

**Gate:** `./gradlew testDebugUnitTest` green, including the 3 pre-existing failures in
`CardNetworkDetectorTest` if they're fixed by then — otherwise note them as known-red so this
feature's own results are readable.

### Phase 3 — Camera scaffolding (1 day)

Manifest permission + `uses-feature ... required="false"`; nav destination; `CardScanFragment`
with `PreviewView` and permission handling via `ActivityResultContracts.RequestPermission`;
`ImplementationMode.COMPATIBLE` (see §9); graceful "no camera" and "permission denied" paths
that both fall through to manual entry.

**Gate:** live preview visible, permission denial lands on the manual form with no crash.

#### Phase 3 results (built 2026-08-28)

**Delivered.** Preview only — no `ImageAnalysis` use case and no OCR yet, both of which are
Phase 4. Files:

| File | State |
|---|---|
| `AndroidManifest.xml` | `CAMERA` + `uses-feature camera`/`camera.autofocus`, both `required="false"` |
| `res/navigation/main_nav.xml` | `cardScanFragment` + 3 actions |
| `ui/scan/CardScanFragment.kt` | new, 270 lines |
| `res/layout/fragment_card_scan.xml` | new |
| `res/drawable/scan_reticle.xml`, `ic_keyboard.xml`, `ic_camera_off.xml` | new |
| `res/values/strings.xml` | +15 (14 scan + `cd_back`) |

**Five states, one screen.** `CardScanFragment` resolves to exactly one of: *camera* (preview
bound), *permission not granted yet* (rationale + "Allow camera"), *permission blocked in
Settings* (`shouldShowRequestPermissionRationale` false after an ask → "Open settings"),
*no camera* (no action button — nothing the user can do), or *camera failed to open*
("Try again"). **"Enter manually" is present in all five**, which is what makes the phase gate's
"falls through to manual entry" true by construction rather than by a specific code path.

**Three lifecycle hazards handled, each worth knowing about:**

1. *Rotation must not re-prompt.* `onViewCreated` only launches the permission request when
   `savedInstanceState == null`. Without that guard a rebuilt fragment re-asks a user who
   already declined — and against a permanent denial it re-asks in a tight loop, because the
   system returns "denied" without showing anything.
2. *The provider future is not lifecycle-aware.* `ProcessCameraProvider.getInstance(...)`'s
   listener can fire after the user has backed out, so it re-checks `isAdded && view != null`
   before touching a view.
3. *Return from Settings reports nothing back.* `onResume` re-checks the permission and binds if
   it has appeared, guarded by a `previewBound` flag so the normal resume path doesn't rebind.

**Two decisions inside the fragment worth flagging:**

- *Front camera as a last resort.* `pickCamera` prefers `DEFAULT_BACK_CAMERA` and falls back to
  front. `hasSystemFeature(FEATURE_CAMERA_ANY)` can be true on a device CameraX still can't
  offer a camera for, so `bindToLifecycle` is additionally wrapped — that case lands on the
  *no camera* panel rather than throwing.
- *"Enter manually" pops instead of navigating when the user came from the add form.* Phase 6
  adds an in-form scan affordance; without this check, backing out of the scanner would stack a
  second empty form on top of the partly-filled one. Hence `action_scan_to_add` carries
  `popUpTo=cardScanFragment inclusive`, and the fragment checks `previousBackStackEntry`.

`ImplementationMode.COMPATIBLE` (TextureView) is set as §9 requires: this app runs with
`FLAG_SECURE`, and a SurfaceView preview punches through the window.

**Verification (this environment):**

| Check | Result |
|---|---|
| `assembleDebug` | clean |
| `assembleRelease` (R8 + resource shrink) | clean |
| `testDebugUnitTest` | 110 tests, **same 3 known-red** `CardNetworkDetectorTest` cases, no new failures |
| Shipped release manifest | `USE_BIOMETRIC`, `USE_FINGERPRINT`, `CAMERA` — **`INTERNET` and `ACCESS_NETWORK_STATE` still absent** |
| Both `uses-feature` entries | present, `required=false` |
| R8 kept `CardScanFragment` unrenamed | yes — `mapping.txt:133270` maps it to itself |
| Release APK size | arm64 14.46 MiB, v7a 10.37 MiB, universal 20.97 MiB (**+0.14 MiB** over the Phase 0 figures) |

The R8 result needed no hand-written rule: AGP's `aapt_rules.txt` already emits
`-keep class com.cardvault.ui.scan.CardScanFragment { <init>(...); }` from the `android:name`
in the nav graph, the same mechanism that keeps the five existing fragments. This is the last
open question against `proguard-rules.pro` — it stays untouched, and the §4 row is struck.

**Not verifiable here — carried into Phase 4's device pass.** Everything above is static; the
gate's "live preview visible" needs hardware. On-device list:

1. Preview actually renders, right way up, in both orientations.
2. Deny the permission → rationale panel; "Allow camera" re-asks; deny twice → "Open settings"
   panel; Settings round-trip grants and `onResume` binds without a manual retry.
3. "Enter manually" from the scanner reaches the add form, and Back from that form returns to
   Home, **not** to the scanner.
4. Rotate in each of the five states — no re-prompt, no crash, no double bind.
5. With `lockOnBackground` on, confirm the *permission dialog* doesn't lock the vault under the
   user. The confirmed lock policy is "entry point only, no code change", so this is a check on
   whether that decision holds in practice, not a change.
6. Watch logcat for the Phase 0 residual: `transport-runtime` consulting `ConnectivityManager`
   without the permission throws `SecurityException`. ML Kit references zero
   `ConnectivityManager` classes so it should stay off the recognition path — Phase 4 is when
   there *is* a recognition path to observe.

**Known gap: nothing reaches this screen yet.** The two entry points (Home chooser sheet,
in-form end icon) are Phase 6, so `cardScanFragment` has three actions and no caller. Checks
1–5 above are blocked on that. Options: pull Phase 6's Home chooser forward to make Phase 3
reviewable on a device now, or review the code now and defer the device pass. Flagged, not
decided.

### Phase 4 — Analyzer + accumulator wiring (1 day)

`MlKitTextSource`; bind `ImageAnalysis` only; feed `OcrFrame`s to the ViewModel's accumulator;
render the 4-field checklist overlay; auto-finish, "Use what you have", and 25 s timeout.

**Gate:** scanning a real card locks PAN + expiry within a few seconds.

### Phase 5 — Result plumbing (½ day)

Return the result through the Navigation result pattern:

```kotlin
// CardScanFragment
findNavController().previousBackStackEntry
    ?.savedStateHandle?.set(KEY_SCAN_RESULT, result)   // Parcelable
findNavController().popBackStack()
```

`AddEditCardFragment` observes it in `onViewCreated` and calls a new `applyScan()` modelled on
`applyInitial` (`:266-292`):

- `suppressWatchers = true` around the `setText` calls, then explicitly refresh
  `detectedNetwork`, `previewNetwork`, `previewPan`, `previewExpiry`, `previewName` — exactly as
  `applyInitial` does, because suppressed watchers don't recompute the preview.
- Apply **only** the fields the scan actually resolved; leave the rest untouched.
- `dirty = true` afterwards — the opposite of `applyInitial`'s `dirty = false` — so the
  unsaved-changes guard protects scanned data.
- **Never touch `cvvInput`.**
- Snapshot the pre-scan field values and show a Snackbar with **Undo**, so a bad scan on an
  existing card is one tap from reverted. This is what makes the end-icon safe in edit mode.
- Move focus to the CVV field, since that is definitionally the one thing still missing.
- `savedStateHandle.remove(KEY_SCAN_RESULT)` after applying, so rotation doesn't re-apply it.

**Gate:** scan → form is populated, CVV empty and focused, Undo restores prior state, rotation
does not re-apply.

### Phase 6 — Entry points (½ day)

- `HomeFragment.onAddCardClicked()`: keep the existing `MAX_CARDS` check **first**, then show
  the chooser sheet (`BottomSheetDialog` + `dialog_add_card_choice.xml`, mirroring
  `showActionsSheet`). "Enter manually" → existing `action_home_to_add`. "Scan card" →
  `action_home_to_scan`.
- `panLayout` gets `app:endIconMode="custom"` + `ic_camera`, wired to
  `action_add_to_scan`, with a `contentDescription`.

**Gate:** both routes reach the scanner; the 30-card cap still blocks both.

### Phase 7 — Torch + low-light hint (½ day)

Torch toggle via `camera.cameraControl.enableTorch()`, hidden when
`cameraInfo.hasFlashUnit()` is false. Derive a "too dark / hold steadier" hint from mean luma
of the `ImageProxy` Y plane, debounced so it doesn't flicker.

**Gate:** torch toggles; hint appears in a dim room and clears in good light.

### Phase 8 — Hardening (1 day)

- ProGuard: `-keep class com.google.mlkit.** { *; }`, `-dontwarn com.google.mlkit.**`,
  `-keep class com.google.android.gms.internal.mlkit_** { *; }`, plus CameraX `-dontwarn`s.
  **Verify against a real `assembleRelease` install**, since R8 is off in debug.
- Grep gate: assert zero `Log.`/`println` occurrences under `com/cardvault/scan/`.
- Zero-persistence audit: confirm no `ImageCapture`/`VideoCapture`/`File`/`OutputStream`
  reference exists in the scan package.
- Accessibility: `contentDescription` on torch, shutter, close and the end-icon; TalkBack
  announcement as each field locks; verify the overlay is readable at large font scales.
- Consider an App Bundle or `abiFilters` so the ML Kit native library isn't shipped for every
  ABI — this is the main lever on the size cost from Phase 0.

### Phase 9 — Docs (½ day)

- `REQUIREMENTS.md`: new §9 specifying the scan flow, the five CVV-suppression mechanisms, and
  the extraction rules. Add the CAMERA permission to §1.4.
- `README.md`: a scan section, and an honest permission disclosure — *camera used only in-app,
  frames never stored, still no network permission.* The README's existing privacy claims are
  a selling point; adding a permission without updating them would undercut it.

**Total: ~8–9 working days.**

---

## 8. Test plan

| Layer | Coverage |
|---|---|
| `LuhnTest` | Known-good PANs per network (Visa/MC/Amex/RuPay/Discover/Diners/JCB), single-digit corruption, transposed adjacent digits, non-digits, empty, and lengths outside 13–19 |
| Add/edit form (manual) | Silent while typing; warns on focus-loss; silent on load for a bad *stored* card; dialog at most once per session; "Save anyway" persists; "Let me check" refocuses; a valid card is completely silent |
| `NumericNormalizerTest` | Each substitution; must **not** mangle genuine letters in a name |
| `BankMatcherTest` | Exact, case-insensitive, `BANK`-suffix-stripped, fuzzy hit, fuzzy miss, and **`HDFC` ≠ `HSBC`** |
| `CardScanParserTest` | Every §7-Phase-2 fixture, plus `amex_frontCid_isNeverEmitted` and `backOfCard_cvvIsNeverEmitted` |
| `ScanAccumulatorTest` | Locks at 3 agreements; disagreement doesn't lock; a Luhn-failing PAN never locks; reset clears |
| Manual device matrix | Embossed vs flat-print; Visa/Mastercard/RuPay/Amex; good light vs dim; permission denied; no-camera device; rotation mid-scan; **release build** |

The parser tests are pure JVM, so they run in the existing `testDebugUnitTest` task with no
new infrastructure. This roughly triples the project's unit-test count.

---

## 9. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Bundled ML Kit needs Play Services after all | High | **Phase 0: retired.** Zero GMS-availability checks across 2,858 ML Kit classes. Device confirmation still open. |
| APK grows ~5–6 MB | Medium | **Phase 0: materialised worse — +12.4 MiB per device.** Resolved with ABI splits + `abiFilters`: 14.32 MiB arm64 / 10.24 MiB v7a. See Phase 0 results (b). |
| ML Kit injects `INTERNET` + `ACCESS_NETWORK_STATE`, breaking the zero-network property | **High** | **Phase 0: found and fixed** via `tools:node="remove"`; verified absent from the shipped APK. Watch logcat on the first device scan — see Phase 0 results (d). |
| `FLAG_SECURE` + `SurfaceView` renders black on some OEMs | Medium | `PreviewView.ImplementationMode.COMPATIBLE` (TextureView) from the start; verify on a real device |
| Embossed silver-on-silver cards read poorly | Medium | Torch, low-light hint, multi-frame voting, always-available manual fallback |
| R8 strips ML Kit reflection in release only | Medium | Keep rules + mandatory release-build verification (debug has `isMinifyEnabled = false`) |
| Permission dialog locks the vault on an OEM skin | Low | Accepted at review. Primary entry point is Home, where no form data exists, so a lock costs only a re-unlock |
| Name/bank OCR is simply wrong sometimes | Low | Every field is user-editable — that's the requested design. Undo Snackbar for re-scans |

---

## 10. Open items deliberately left out

- Phase 1 (Luhn) has **no dependency on anything else in this plan** and is the natural first
  merge — it is useful standalone, and the scanner needs it regardless.
- Card-type detection and nickname auto-suggest — declined at review, easy to add later; the
  parser's scoring structure has room for both.
- Gallery import — blocked by §3.1.
- The pre-existing bugs from the audit (CVV bypass on the edit screen, 3 red tests, missing
  `excludeFromRecents`, `maskPan` last-4 bug) are **not** touched by this plan, per your
  instruction to defer them. Note one interaction: the audit's High-severity **CVV bypass on
  the Edit screen** (`AddEditCardFragment.kt:272`) sits in the same file this plan modifies.
  Fixing it first would avoid touching that region twice.
