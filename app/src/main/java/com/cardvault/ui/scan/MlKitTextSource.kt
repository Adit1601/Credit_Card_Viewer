package com.cardvault.ui.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.cardvault.scan.OcrFrame
import com.cardvault.scan.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * The only class in the app that knows what ML Kit is.
 *
 * Its whole job is to turn an [ImageProxy] into a framework-free [OcrFrame] and hand it on, so
 * that [com.cardvault.scan.CardScanParser] and everything behind it stays pure Kotlin and
 * JVM-testable. If ML Kit were ever swapped out, this file is the only one that changes.
 *
 * It also owns the two resource obligations of the analysis path, in one place each:
 *  - **every** [ImageProxy] is closed exactly once, on every path including failure — CameraX
 *    stalls permanently if one is leaked;
 *  - the recognizer is [close]d by the caller when the camera goes away.
 *
 * Deliberately absent: any `Log` call, any `File`/`OutputStream`, and any use case other than
 * analysis. Frames exist only as the `Image` CameraX already holds and are released immediately
 * (§6, mechanism 5).
 */
class MlKitTextSource : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var closed = false

    /**
     * Recognises text in [proxy] and delivers an [OcrFrame], or `null` if the frame yielded
     * nothing usable.
     *
     * [onResult] is invoked on [callbackExecutor], exactly once per call, and always — a
     * recognition failure delivers `null` rather than silently dropping the frame, so the
     * caller's throttle and timeout logic can rely on getting an answer back.
     *
     * **[callbackExecutor] is not optional.** ML Kit's `Task` listeners default to the main
     * thread, so omitting it would run `CardScanParser.parse` — row grouping, scoring and
     * Levenshtein over every line — on the UI thread several times a second. Passing the
     * analysis executor keeps parsing off the main thread and is what makes
     * [CardScanViewModel]'s single-threaded accumulator contract true.
     */
    // The opt-in stops here rather than propagating up through onFrame to the analyzer lambda:
    // `proxy.image` on the next line is the only experimental API the scanner touches, and lint
    // (UnsafeOptInUsageError) fails the build on the unmarked call site otherwise. androidx's
    // @OptIn, not Kotlin's — ExperimentalGetImage is a Java @RequiresOptIn marker.
    @OptIn(markerClass = [ExperimentalGetImage::class])
    fun process(proxy: ImageProxy, callbackExecutor: Executor, onResult: (OcrFrame?) -> Unit) {
        val media = proxy.image
        if (media == null || closed) {
            proxy.close()
            onResult(null)
            return
        }

        val rotation = proxy.imageInfo.rotationDegrees

        // ML Kit reports bounding boxes in the coordinate space of the *rotated* (upright) image,
        // not the sensor's. At 90/270 that means width and height are swapped relative to the
        // proxy. This is not cosmetic: CardScanParser scores an expiry candidate higher when it
        // sits in the bottom third of the frame (`frame.height * 2 / 3`), so passing the sensor's
        // dimensions here would apply that bonus to a sideways band of the card.
        val upright = rotation == 90 || rotation == 270
        val frameWidth = if (upright) proxy.height else proxy.width
        val frameHeight = if (upright) proxy.width else proxy.height

        val input = runCatching { InputImage.fromMediaImage(media, rotation) }.getOrNull()
        if (input == null) {
            proxy.close()
            onResult(null)
            return
        }

        recognizer.process(input)
            .addOnSuccessListener(callbackExecutor) { text ->
                onResult(toFrame(text, frameWidth, frameHeight))
            }
            .addOnFailureListener(callbackExecutor) {
                // A single failed frame is normal — the pipeline is fed ~4 frames a second and
                // any of them can arrive mid-refocus. Report the miss and let the next one try.
                onResult(null)
            }
            .addOnCompleteListener {
                // Closes on success, failure and cancellation alike. This is the one place the
                // proxy is released, which is what makes "closed exactly once" checkable.
                //
                // Deliberately NOT on callbackExecutor. That executor is shut down when the
                // camera goes away, and a recognition still in flight at that moment would have
                // its close() rejected — leaking a camera buffer, and throwing on an ML Kit
                // internal thread where nothing catches it. The main-thread default is never
                // rejected, so the close always happens.
                //
                // Safe despite running on a different thread from onSuccess: the success listener
                // only reads the materialised `Text`, which ML Kit owns independently of the
                // `Image`. The one real rule is not to close before the task completes, and
                // onComplete fires exactly at completion.
                proxy.close()
            }
    }

    private fun toFrame(text: Text, width: Int, height: Int): OcrFrame? {
        val lines = ArrayList<OcrLine>()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                // A line with no bounding box carries no positional evidence, and every one of
                // the parser's heuristics (glyph height, row grouping, bottom-third bonus) is
                // positional. Dropping it is better than feeding in a line at (0,0,0,0), which
                // would read as zero-height text at the top of the card.
                val box = line.boundingBox ?: return@forEach
                lines.add(
                    OcrLine(
                        text = line.text,
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                    )
                )
            }
        }
        if (lines.isEmpty()) return null
        return OcrFrame(lines = lines, width = width, height = height)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { recognizer.close() }
    }
}
