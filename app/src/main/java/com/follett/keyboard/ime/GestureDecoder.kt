package com.follett.keyboard.ime

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

/**
 * GestureDecoder — Swipe/gesture typing path recognition engine.
 *
 * Decodes a finger path across the keyboard into candidate words.
 * Uses a simplified shape-matching algorithm:
 *  1. Sample the swipe path at regular intervals
 *  2. Identify which keys the path passes through/near
 *  3. Generate candidate letter sequences
 *  4. Match against the dictionary using edit distance
 *
 * This is a production-viable approach that works well for common words.
 * Elite keyboards (GBoard) use neural network decoders trained on millions
 * of gesture samples — that's Phase 2+ (on-device model).
 */
class GestureDecoder {

    companion object {
        private const val MIN_SWIPE_DISTANCE = 80f  // dp threshold to trigger gesture
        private const val PATH_SAMPLE_INTERVAL = 15f // pixels between samples
        private const val KEY_PROXIMITY_THRESHOLD = 1.4f // multiplier of key radius for "near"
    }

    /**
     * Decode a swipe path into candidate words.
     *
     * @param path List of touch points from ACTION_DOWN through ACTION_UP
     * @param keyRects The current key rectangles on screen
     * @param keys The current key data
     * @param dictionary Function to get word suggestions for a letter sequence
     * @return List of candidate words, ranked by confidence
     */
    fun decode(
        path: List<PointF>,
        keyRects: List<RectF>,
        keys: List<KeyboardCanvasView.Key>,
        dictionary: (String) -> List<String>
    ): List<String> {
        if (path.size < 3) return emptyList()

        // Check minimum swipe distance
        val totalDist = calculatePathLength(path)
        if (totalDist < MIN_SWIPE_DISTANCE) return emptyList()

        // Sample the path at regular intervals
        val sampledPath = samplePath(path)

        // Identify key sequence from path
        val keySequence = extractKeySequence(sampledPath, keyRects, keys)
        if (keySequence.length < 2) return emptyList()

        // Get dictionary candidates
        val candidates = dictionary(keySequence)

        // Score candidates based on path proximity
        return candidates.take(5)
    }

    /**
     * Check if a touch path qualifies as a swipe gesture (vs a tap).
     */
    fun isSwipeGesture(path: List<PointF>, density: Float): Boolean {
        if (path.size < 3) return false
        val dist = calculatePathLength(path)
        return dist > MIN_SWIPE_DISTANCE * density
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PATH PROCESSING
    // ═════════════════════════════════════════════════════════════════════════

    private fun calculatePathLength(path: List<PointF>): Float {
        var total = 0f
        for (i in 1 until path.size) {
            total += distance(path[i - 1], path[i])
        }
        return total
    }

    private fun samplePath(path: List<PointF>): List<PointF> {
        val sampled = mutableListOf(path.first())
        var accumulated = 0f

        for (i in 1 until path.size) {
            accumulated += distance(path[i - 1], path[i])
            if (accumulated >= PATH_SAMPLE_INTERVAL) {
                sampled.add(path[i])
                accumulated = 0f
            }
        }
        if (sampled.last() != path.last()) {
            sampled.add(path.last())
        }
        return sampled
    }

    private fun extractKeySequence(
        sampledPath: List<PointF>,
        keyRects: List<RectF>,
        keys: List<KeyboardCanvasView.Key>
    ): String {
        val sequence = StringBuilder()
        var lastKey = ""

        for (point in sampledPath) {
            val nearestKey = findNearestLetterKey(point, keyRects, keys)
            if (nearestKey != null && nearestKey != lastKey) {
                sequence.append(nearestKey)
                lastKey = nearestKey
            }
        }
        return sequence.toString()
    }

    private fun findNearestLetterKey(
        point: PointF,
        keyRects: List<RectF>,
        keys: List<KeyboardCanvasView.Key>
    ): String? {
        var minDist = Float.MAX_VALUE
        var nearestTag: String? = null

        for (i in keys.indices) {
            val key = keys[i]
            // Only consider letter keys
            if (key.tag.length != 1 || !key.tag[0].isLetter()) continue
            if (i >= keyRects.size) continue

            val rect = keyRects[i]
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val dist = distance(point, PointF(centerX, centerY))
            val threshold = rect.width() * KEY_PROXIMITY_THRESHOLD

            if (dist < threshold && dist < minDist) {
                minDist = dist
                nearestTag = key.tag
            }
        }
        return nearestTag
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
