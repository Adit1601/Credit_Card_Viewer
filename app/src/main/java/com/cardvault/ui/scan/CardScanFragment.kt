package com.cardvault.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.cardvault.MainActivity
import com.cardvault.R
import com.cardvault.scan.ScanCandidate
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live camera screen for card scanning.
 *
 * The screen is built around the idea that scanning can always fail — no permission, no hardware,
 * a camera another app is holding, a card too worn or too dark to read — and that failing must
 * never be a dead end. **"Enter manually" is on screen in every state**, so the worst outcome is
 * that the user types the card in, which is what they would have done without this feature.
 *
 * Only [ImageAnalysis] and [Preview] are ever bound. `ImageCapture` and `VideoCapture` are never
 * constructed, so no code path exists that could write a frame anywhere (§6, mechanism 5). There
 * are no `Log` calls in this file for the same reason.
 *
 * `FLAG_SECURE` is inherited from [com.cardvault.MainActivity], which sets it before
 * `super.onCreate`, so the preview is excluded from screenshots and the recents thumbnail without
 * anything extra here.
 */
class CardScanFragment : Fragment(R.layout.fragment_card_scan) {

    private val viewModel: CardScanViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var cameraGroup: View
    private lateinit var previewView: PreviewView
    private lateinit var scanHint: TextView
    private lateinit var checklist: View
    private lateinit var torchButton: MaterialButton
    private lateinit var fallbackPanel: View
    private lateinit var fallbackTitle: TextView
    private lateinit var fallbackBody: TextView
    private lateinit var fallbackAction: MaterialButton
    private lateinit var useResultButton: MaterialButton
    private lateinit var manualButton: MaterialButton

    /** tick view, label view, label string — indexed in the order the checklist is drawn. */
    private lateinit var checkRows: List<Triple<ImageView, TextView, Int>>

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var textSource: MlKitTextSource? = null
    private var analysisExecutor: ExecutorService? = null

    private var previewBound: Boolean = false
    private var resultDelivered: Boolean = false

    /**
     * Set while [startCamera] is waiting on the `ProcessCameraProvider` future.
     *
     * [previewBound] cannot stand in for this. It only flips inside [bindUseCases], which runs
     * from a listener *posted* to the main looper, whereas `onViewCreated` and [onResume] both run
     * within a single main-looper message — so on every fresh entry [onResume] saw
     * `previewBound == false` and started the camera a second time. That meant two binds, two
     * analysis executors and two ML Kit recognizers (the first pair built and immediately shut
     * down again), a redundant `unbindAll()` mid-open, and two timeout clocks. This flag is what
     * makes "a start is already in flight" visible to [onResume].
     */
    private var cameraStarting: Boolean = false

    /**
     * The one timeout clock, belonging to whichever camera is currently bound.
     *
     * Held so it can be cancelled. Every rebind used to arm another clock with no way to stop the
     * previous one, and an orphan from an earlier bind stays armed across paths that release the
     * camera without destroying the view — [showCameraError] being the reachable one. Retrying
     * from that panel a couple of seconds in would then be torn down by the old clock some 23
     * seconds early, panel and all.
     */
    private var timeoutJob: Job? = null

    /** Last wall-clock ms at which a frame was actually handed to OCR. See [MIN_FRAME_INTERVAL_MS]. */
    @Volatile
    private var lastAnalyzedAt: Long = 0L

    /** Frames handed to OCR since the camera bound, for the "hold steady" hint. */
    @Volatile
    private var framesAnalyzed: Int = 0

    /** Exponential moving average of frame luma, 0-255. Analyzer thread only. */
    @Volatile
    private var lumaAverage: Float = -1f

    /** Debounced low-light verdict, so the hint doesn't flicker frame to frame. */
    @Volatile
    private var lowLight: Boolean = false
    private var lowLightStreak: Int = 0

    /** Which fields have already been announced to TalkBack, so each announces once. */
    private val announced = HashSet<Int>()

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Disarm first, and unconditionally: the launch may never have raised a dialog at all (an
        // auto-denial under device policy, or the permission having been granted in between), in
        // which case no onUserLeaveHint arrived to consume the flag and it would sit armed and
        // swallow the user's next real Home press instead. See launchCameraPermissionRequest.
        (activity as? MainActivity)?.endInAppExcursion()
        if (granted) startCamera() else showPermissionDenied()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)

        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        manualButton.setOnClickListener { goToManualEntry() }
        useResultButton.setOnClickListener { deliverResult(viewModel.currentResult()) }
        torchButton.setOnClickListener { toggleTorch() }

        viewModel.progress.observe(viewLifecycleOwner) { renderProgress(it) }
        viewModel.autoFinished.observe(viewLifecycleOwner) { candidate ->
            if (candidate == null) return@observe
            // Consumed, so a rotation that happens in the same instant as completion cannot
            // replay it to the rebuilt fragment and navigate twice. Same one-shot-event idiom as
            // AddEditCardViewModel.savedEvent.
            viewModel.autoFinished.value = null
            deliverResult(candidate)
        }

        when {
            !deviceHasCamera() -> showNoCamera()
            // Before the permission checks, and before starting anything: a scan that has already
            // given up must come back as the panel that says so, not as a fresh camera.
            viewModel.timedOut -> showTimeoutPanel()
            hasCameraPermission() -> startCamera()
            // Only prompt on a genuinely fresh entry. After a rotation the fragment is rebuilt,
            // and re-launching the request here would re-prompt a user who has already declined —
            // or worse, loop against a "don't ask again" that returns denied instantly.
            savedInstanceState == null -> launchCameraPermissionRequest()
            else -> showPermissionDenied()
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted the permission in the Settings app we sent them to. Nothing
        // reports that back, so re-check on the way in rather than leaving a stale panel up.
        //
        // [CardScanViewModel.timedOut] is what keeps this narrow. `previewBound` is false both
        // when the camera was never opened and when it was deliberately released, and the timeout
        // path is the second — so without the flag, backgrounding the app with "Couldn't read the
        // card" up and returning replaced the explanation with a live camera the user never asked
        // to restart. The camera-*error* panel is deliberately not covered: that failure is often
        // another app holding the camera, and coming back is exactly when it may have let go.
        if (!previewBound && !resultDelivered && !viewModel.timedOut &&
            deviceHasCamera() && hasCameraPermission()
        ) {
            startCamera()
        }
    }

    override fun onDestroyView() {
        stopAnalysis()
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        previewBound = false
        // The fragment instance outlives its view on the back stack, so a future that never
        // resolved would otherwise leave this set and stop the next view starting the camera.
        cameraStarting = false
        super.onDestroyView()
    }

    private fun bindViews(v: View) {
        toolbar = v.findViewById(R.id.toolbar)
        cameraGroup = v.findViewById(R.id.cameraGroup)
        previewView = v.findViewById(R.id.previewView)
        scanHint = v.findViewById(R.id.scanHint)
        checklist = v.findViewById(R.id.checklist)
        torchButton = v.findViewById(R.id.torchButton)
        fallbackPanel = v.findViewById(R.id.fallbackPanel)
        fallbackTitle = v.findViewById(R.id.fallbackTitle)
        fallbackBody = v.findViewById(R.id.fallbackBody)
        fallbackAction = v.findViewById(R.id.fallbackAction)
        useResultButton = v.findViewById(R.id.useResultButton)
        manualButton = v.findViewById(R.id.manualButton)

        checkRows = listOf(
            Triple(v.findViewById(R.id.tickPan), v.findViewById(R.id.labelPan), R.string.scan_field_pan),
            Triple(v.findViewById(R.id.tickExpiry), v.findViewById(R.id.labelExpiry), R.string.scan_field_expiry),
            Triple(v.findViewById(R.id.tickName), v.findViewById(R.id.labelName), R.string.scan_field_name),
            Triple(v.findViewById(R.id.tickBank), v.findViewById(R.id.labelBank), R.string.scan_field_bank),
        )
        renderProgress(ScanProgress())
    }

    // ---- camera ------------------------------------------------------------------------------

    private fun startCamera() {
        // Re-entrant by nature — onViewCreated, onResume, the camera-error retry and the timeout
        // retry all land here, and the first two do so inside the same main-looper message, before
        // any of the work below has had a chance to record that it started.
        if (cameraStarting) return
        cameraStarting = true

        showCamera()
        // COMPATIBLE (TextureView) rather than the default PERFORMANCE (SurfaceView). A
        // SurfaceView preview punches a hole through the window, and this app runs with
        // FLAG_SECURE; TextureView composites normally, which keeps the preview visible and still
        // covered by the secure-window screenshot block.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            // Cleared before anything that can return, so no exit path leaves the flag set and
            // wedges every later attempt to start the camera.
            cameraStarting = false

            // The listener runs on the main thread but is not lifecycle-aware: it can fire after
            // the user has already backed out, so re-check the view before touching anything.
            if (!isAdded || view == null) return@addListener

            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                showCameraError()
                return@addListener
            }
            cameraProvider = provider
            bindUseCases(provider)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        // Release whatever the last attempt left behind. Two paths re-enter here without having
        // gone through stopAnalysis first — a camera-open failure, and onResume after one — and
        // without this each attempt would strand a thread and an ML Kit recognizer.
        stopAnalysis()

        val selector = pickCamera(provider)
        if (selector == null) {
            // hasSystemFeature said there was a camera but CameraX cannot offer one — a
            // provisioning quirk on some devices. Same user-facing outcome as no hardware.
            showNoCamera()
            return
        }

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        // 1280x720 rather than ImageAnalysis's 640x480 default. At VGA a 16-digit PAN spans
        // roughly 300 px, which is around 18 px per digit — below what text recognition reads
        // reliably on embossed, low-contrast plastic. CLOSEST_HIGHER_THEN_LOWER means a device
        // that cannot do exactly 720p gets the nearest thing rather than failing to bind.
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            // Always analyse the newest frame and drop anything queued behind it. Analysing stale
            // frames would spend CPU on a view of the card the user has already moved.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val executor = Executors.newSingleThreadExecutor()
        val guarded = DropAfterShutdown(executor)
        val source = MlKitTextSource()
        analysisExecutor = executor
        textSource = source
        analysis.setAnalyzer(executor) { proxy -> onFrame(proxy, source, guarded) }

        val bound = runCatching {
            // Unbind first: onResume and the timeout retry can both re-enter this path, and
            // binding the same use case twice throws instead of replacing.
            provider.unbindAll()
            provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
        }
        val boundCamera = bound.getOrNull()
        if (boundCamera == null) {
            showCameraError()
            return
        }

        camera = boundCamera
        imageAnalysis = analysis
        previewBound = true
        setUpTorch(boundCamera)
        startTimeout()
    }

    /**
     * Back camera for real use; front only as a last resort so a device without a rear camera
     * still gets something usable rather than an error panel.
     */
    private fun pickCamera(provider: ProcessCameraProvider): CameraSelector? = runCatching {
        when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> null
        }
    }.getOrNull()

    /**
     * Releases everything on the analysis path. Called on the way out and before the timeout
     * panel, so a stopped scan is not still burning battery on OCR behind a static screen.
     *
     * That includes the timeout clock: a scan that has stopped has nothing left to time out, and
     * leaving one armed is how it ends up firing on the *next* scan instead.
     */
    private fun stopAnalysis() {
        timeoutJob?.cancel()
        timeoutJob = null
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        // Order matters: clear the analyzer first so no new frame can enter, then close the
        // recognizer, then shut the executor down. Closing the recognizer while a frame is still
        // in flight is what produces "MlKitException: Waiting for the model" noise on exit.
        textSource?.close()
        textSource = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    // ---- frame handling (analyzer thread) ----------------------------------------------------

    private fun onFrame(proxy: ImageProxy, source: MlKitTextSource, executor: Executor) {
        val now = System.currentTimeMillis()
        // Throttle. With KEEP_ONLY_LATEST the pipeline would otherwise run OCR as fast as the
        // device can manage, which on a modern phone is 10-15 fps — several times more than
        // voting needs, and it shows up as heat and battery rather than as a better scan.
        if (now - lastAnalyzedAt < MIN_FRAME_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastAnalyzedAt = now

        // Read luma BEFORE handing the proxy over: MlKitTextSource closes it, after which the
        // planes are invalid.
        updateLuma(proxy)

        source.process(proxy, executor) { frame ->
            framesAnalyzed++
            if (frame != null) viewModel.onFrame(frame)
            // The hint is the only thing the analyzer thread drives directly, and it is pure
            // presentation, so it is posted rather than routed through the ViewModel.
            view?.post { updateHint() }
        }
    }

    /**
     * Mean luminance of a sparse sample of the Y plane, smoothed.
     *
     * Sampled every 64th byte on every 8th row rather than read in full: a 720p Y plane is
     * ~920 KB and this runs several times a second, while "is this room dark" needs nothing like
     * that precision. Y is the first plane in YUV_420_888, so no colour conversion is involved.
     *
     * The row is walked to `width`, not to `rowStride`. Those are equal on plenty of devices and
     * this looked correct there, but a padded Y plane — 1536 bytes a row for a 1280-wide frame is
     * a real configuration — puts 256 bytes of padding past the end of every row. Padding is
     * typically zero, so sampling it mixed 4 black pixels into every 24 and pulled the mean down
     * by about a sixth: enough to hold an adequately-lit scene under [LUMA_DARK_ENTER] and show
     * "too dark, turn on the torch" permanently, on exactly those devices and no others.
     */
    private fun updateLuma(proxy: ImageProxy) {
        val plane = proxy.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        if (rowStride <= 0 || buffer.capacity() == 0) return

        // Pixel stride is guaranteed to be 1 on the Y plane of YUV_420_888, so a column index is
        // a byte offset within the row.
        val width = minOf(proxy.width, rowStride)

        var total = 0L
        var count = 0
        var row = 0
        while (row < proxy.height) {
            val rowStart = row * rowStride
            var col = 0
            while (col < width) {
                val index = rowStart + col
                if (index >= buffer.capacity()) break
                // Y is unsigned; Kotlin's Byte is signed, so mask back into 0-255.
                total += (buffer.get(index).toInt() and 0xFF)
                count++
                col += 64
            }
            row += 8
        }
        if (count == 0) return

        val mean = total.toFloat() / count
        lumaAverage = if (lumaAverage < 0f) mean else lumaAverage * 0.7f + mean * 0.3f

        // Hysteresis, not a single threshold: a room sitting right at the boundary would
        // otherwise flip the hint on and off several times a second.
        val dark = lumaAverage < LUMA_DARK_ENTER
        val bright = lumaAverage > LUMA_DARK_EXIT
        when {
            dark && !lowLight -> if (++lowLightStreak >= LOW_LIGHT_STREAK) { lowLight = true; lowLightStreak = 0 }
            bright && lowLight -> if (++lowLightStreak >= LOW_LIGHT_STREAK) { lowLight = false; lowLightStreak = 0 }
            else -> lowLightStreak = 0
        }
    }

    // ---- overlay -----------------------------------------------------------------------------

    private fun renderProgress(progress: ScanProgress) {
        if (view == null) return
        val locked = listOf(
            progress.panLocked, progress.expiryLocked, progress.nameLocked, progress.bankLocked
        )
        checkRows.forEachIndexed { index, (tick, label, labelRes) ->
            val isLocked = locked[index]
            tick.alpha = if (isLocked) 1f else 0.28f
            tick.imageTintList = ContextCompat.getColorStateList(
                requireContext(),
                if (isLocked) R.color.accent else R.color.text_disabled
            )
            label.setTextColor(
                requireContext().getColor(if (isLocked) R.color.text_primary else R.color.text_secondary)
            )
            val name = getString(labelRes)
            label.contentDescription = getString(
                if (isLocked) R.string.scan_cd_field_locked else R.string.scan_cd_field_pending,
                name
            )
            // Announce each field once, as it lands. A user who cannot see the ticks otherwise
            // has no way to know the scan is progressing, or when to stop holding the card up.
            if (isLocked && announced.add(labelRes)) {
                announceLocked(getString(R.string.scan_a11y_field_locked, name))
            }
        }

        // Only offered once there is a PAN. Everything else on a card is either printed on the
        // user's statement or already in their head; the number is not.
        useResultButton.visibility =
            if (progress.hasSomethingWorthKeeping && !resultDelivered) View.VISIBLE else View.GONE
        updateHint()
    }

    private fun updateHint() {
        if (view == null) return
        scanHint.setText(
            when {
                lowLight -> R.string.scan_hint_low_light
                framesAnalyzed >= FRAMES_BEFORE_NUDGE && viewModel.currentResult().panDigits == null ->
                    R.string.scan_hint_hold_steady
                else -> R.string.scan_hint
            }
        )
    }

    /**
     * Speaks a one-off message.
     *
     * Announced from the root view rather than from the tick itself: the checklist is decorative,
     * `importantForAccessibility="no"` on the icons, and an announcement from a view TalkBack has
     * been told to ignore does not reliably reach the user.
     */
    private fun announceLocked(text: String) {
        view?.announceForAccessibility(text)
    }

    // ---- torch -------------------------------------------------------------------------------

    private fun setUpTorch(boundCamera: Camera) {
        // Front-facing cameras and a fair number of tablets have no flash unit at all. Showing a
        // torch button that silently does nothing is worse than not showing one.
        if (!boundCamera.cameraInfo.hasFlashUnit()) {
            torchButton.visibility = View.GONE
            return
        }
        torchButton.visibility = View.VISIBLE
        // Driven from CameraX's own torch state rather than a local boolean, so the icon stays
        // truthful if the torch is turned off underneath us — which is what happens when the
        // camera is unbound on the timeout path.
        boundCamera.cameraInfo.torchState.observe(viewLifecycleOwner) { state ->
            val on = state == TorchState.ON
            torchButton.setIconResource(if (on) R.drawable.ic_torch_on else R.drawable.ic_torch_off)
        }
    }

    private fun toggleTorch() {
        val active = camera ?: return
        if (!active.cameraInfo.hasFlashUnit()) return
        val on = active.cameraInfo.torchState.value == TorchState.ON
        // Fire and forget: the returned future completing tells us nothing the torchState
        // observer will not, and a failure means the torch simply did not come on.
        runCatching { active.cameraControl.enableTorch(!on) }
    }

    // ---- timeout -----------------------------------------------------------------------------

    /**
     * Gives up on the camera after [TIMEOUT_MS] rather than leaving it running indefinitely.
     *
     * Uses `viewLifecycleOwner.lifecycleScope`, so the clock is cancelled with the view and
     * restarted on rotation. Restarting is the forgiving direction — a user who rotates mid-scan
     * gets more time, not less, and nothing accumulated is lost because the votes live in the
     * ViewModel.
     *
     * Single-flight: any clock still running belongs to a previous bind, not to the camera just
     * bound, so it is cancelled rather than left to fire alongside this one.
     */
    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(TIMEOUT_MS)
            if (resultDelivered || view == null) return@launch
            showTimedOut()
        }
    }

    // ---- states ------------------------------------------------------------------------------

    private fun showCamera() {
        cameraGroup.visibility = View.VISIBLE
        fallbackPanel.visibility = View.GONE
    }

    private fun showFallback(titleRes: Int, bodyRes: Int, actionRes: Int?, onAction: (() -> Unit)?) {
        cameraGroup.visibility = View.GONE
        fallbackPanel.visibility = View.VISIBLE
        fallbackTitle.setText(titleRes)
        fallbackBody.setText(bodyRes)
        if (actionRes == null || onAction == null) {
            fallbackAction.visibility = View.GONE
            fallbackAction.setOnClickListener(null)
        } else {
            fallbackAction.visibility = View.VISIBLE
            fallbackAction.setText(actionRes)
            fallbackAction.setOnClickListener { onAction() }
        }
    }

    /**
     * The system permission dialog does not stop MainActivity, but it does trigger
     * onUserLeaveHint — which is where "lock immediately on background" fires. Without this the
     * vault locked the first time anyone opened the scanner, part-way through granting.
     *
     * The carve-out covers `onUserLeaveHint` only, never `onStop`. That is deliberate: it rests on
     * the grant dialog leaving MainActivity started-but-paused, which is what Android does, and if
     * some device stops it instead the vault locks and the user lands on the lock screen rather than
     * back in the scanner. Inconvenient there, never unlocked-when-it-should-be-locked.
     */
    private fun launchCameraPermissionRequest() {
        (activity as? MainActivity)?.beginInAppExcursion()
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionDenied() {
        // shouldShowRequestPermissionRationale is false after a permanent denial, and also false
        // before the first ask — but we only reach here having asked, so false means blocked.
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            showFallback(
                R.string.scan_permission_title,
                R.string.scan_permission_body,
                R.string.scan_permission_action
            ) { launchCameraPermissionRequest() }
        } else {
            showFallback(
                R.string.scan_permission_blocked_title,
                R.string.scan_permission_blocked_body,
                R.string.scan_permission_blocked_action
            ) { openAppSettings() }
        }
    }

    private fun showNoCamera() {
        // No action button: there is nothing the user can do about missing hardware, and offering
        // a button that cannot help is worse than offering none. "Enter manually" stays visible.
        showFallback(R.string.scan_no_camera_title, R.string.scan_no_camera_body, null, null)
    }

    private fun showCameraError() {
        previewBound = false
        showFallback(
            R.string.scan_error_title,
            R.string.scan_error_body,
            R.string.scan_error_action
        ) { startCamera() }
    }

    /**
     * Timed out. The camera is released and the panel explains why, but the scan is **not**
     * abandoned: whatever locked is still in the ViewModel, so if a PAN was found the
     * "Use what was found" button is still there in the bottom bar underneath this panel.
     *
     * Deliberately not auto-navigating with a partial result. Silently jumping to a half-filled
     * form 25 seconds in, while the user is still holding the card up to the lens, would be a
     * worse surprise than a panel that says what happened.
     */
    private fun showTimedOut() {
        // On the ViewModel, so it survives the rotation that rebuilds this fragment. See
        // [CardScanViewModel.timedOut].
        viewModel.timedOut = true
        stopAnalysis()
        cameraProvider?.unbindAll()
        previewBound = false
        showTimeoutPanel()
    }

    /**
     * The timeout panel on its own, with nothing to release.
     *
     * Separate from [showTimedOut] for the view rebuilt into an already-given-up scan, where there
     * is no camera to unbind and no analysis to stop — only a state to redisplay.
     */
    private fun showTimeoutPanel() {
        showFallback(
            R.string.scan_timeout_title,
            R.string.scan_timeout_body,
            R.string.scan_timeout_action
        ) { retryScan() }
    }

    private fun retryScan() {
        viewModel.timedOut = false
        framesAnalyzed = 0
        lumaAverage = -1f
        lowLight = false
        lowLightStreak = 0
        // Votes already banked are intentionally kept — a retry is more evidence for the same
        // card, not a different one. If the user is scanning a different card they will have
        // left this screen, which clears the ViewModel.
        startCamera()
    }

    // ---- navigation --------------------------------------------------------------------------

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        // Guard rather than assume: the Settings activity is present on every normal build, but a
        // stripped-down ROM without it would otherwise crash the vault on a tap.
        runCatching { startActivity(intent) }
    }

    /**
     * Hands the scan to the add/edit form and leaves.
     *
     * Two routes, because the scanner has two entry points and they need opposite treatment:
     *
     *  - **From the add/edit form** (the PAN field's camera icon): the form is already on the
     *    stack, possibly half filled in. Set the result on its back-stack entry and pop, so the
     *    user's existing input survives.
     *  - **From Home**: there is no form yet. Navigate to a fresh one and pass the result as
     *    navigation arguments. Setting it on `previousBackStackEntry` here would put it on
     *    *Home's* entry, where nothing reads it.
     */
    private fun deliverResult(candidate: ScanCandidate) {
        if (resultDelivered) return
        resultDelivered = true
        viewModel.stopAccepting()
        stopAnalysis()

        val payload = ScanResultBridge.toBundle(candidate)
        val nav = findNavController()
        val previous = nav.previousBackStackEntry

        if (previous?.destination?.id == R.id.addEditCardFragment) {
            previous.savedStateHandle[ScanResultBridge.KEY_SCAN_RESULT] = payload
            nav.popBackStack()
        } else {
            nav.navigate(
                R.id.action_scan_to_add,
                bundleOf(ScanResultBridge.KEY_SCAN_RESULT to payload)
            )
        }
    }

    /**
     * Leaves the scanner for the add form with nothing filled in.
     *
     * If the user reached the scanner *from* the add form, navigating forward would stack a
     * second, empty form on top of the one they were already filling in. Pop back to it instead
     * so their partial input survives.
     */
    private fun goToManualEntry() {
        if (resultDelivered) return
        resultDelivered = true
        val nav = findNavController()
        if (nav.previousBackStackEntry?.destination?.id == R.id.addEditCardFragment) {
            nav.popBackStack()
        } else {
            nav.navigate(R.id.action_scan_to_add)
        }
    }

    private fun deviceHasCamera(): Boolean =
        requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Wraps the analysis executor so a callback arriving after shutdown is dropped, not thrown.
     *
     * [stopAnalysis] shuts the pool down while an ML Kit recognition may still be in flight. When
     * that recognition completes, ML Kit submits its listener to this executor; a bare
     * `ExecutorService` answers with `RejectedExecutionException` on an ML Kit internal thread,
     * where nothing catches it. The scan is over by then, so dropping the parse is the correct
     * outcome — and the frame's `close()` is registered separately, on the main thread, precisely
     * so it is never the thing that gets dropped.
     */
    private class DropAfterShutdown(private val delegate: Executor) : Executor {
        override fun execute(command: Runnable) {
            runCatching { delegate.execute(command) }
        }
    }

    private companion object {
        /** ~4 analysed frames a second. Voting locks a field at 3 agreements, so this is ~1 s. */
        const val MIN_FRAME_INTERVAL_MS = 250L

        /** Long enough that a difficult card is not cut off; short enough not to cook the phone. */
        const val TIMEOUT_MS = 25_000L

        /** Roughly 2 s of analysis with nothing to show for it before nudging the user. */
        const val FRAMES_BEFORE_NUDGE = 8

        const val LUMA_DARK_ENTER = 62f
        const val LUMA_DARK_EXIT = 82f

        /** Consecutive samples on the far side of the threshold before the hint changes. */
        const val LOW_LIGHT_STREAK = 3
    }
}
