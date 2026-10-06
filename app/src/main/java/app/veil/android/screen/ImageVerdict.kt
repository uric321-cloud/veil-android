package app.veil.android.screen

/**
 * Turns the image model's five scores into cover / don't cover. Plain Kotlin so
 * it can be unit tested on a JVM (.localcheck/test/ImageTest.kt).
 *
 * Scores are softmax probabilities in the model's class order:
 * Drawing, Hentai, Neutral, Porn, Sexy (nsfwjs MobileNetV2, MIT, Infinite Red).
 */
object ImageVerdict {
    const val DRAWING = 0
    const val HENTAI = 1
    const val NEUTRAL = 2
    const val PORN = 3
    const val SEXY = 4

    /**
     * "low" covers only clearly explicit images; "high" also covers suggestive
     * ones; "max" is fail-closed - it covers anything the model is not clearly
     * confident is a safe (neutral or plain-drawing) image, so borderline and
     * uncertain images are covered rather than shown. "max" trades more
     * false covers for the strongest guarantee that nothing explicit slips by.
     */
    fun shouldCover(scores: FloatArray, strictness: String): Boolean {
        if (scores.size < 5) return false
        val explicit = scores[PORN] + scores[HENTAI]
        val sexy = scores[SEXY]
        val safe = scores[NEUTRAL] + scores[DRAWING]
        return when (strictness) {
            "low" -> explicit > 0.85f
            "max" -> safe < 0.90f
            "high" -> explicit > 0.40f || sexy > 0.60f || explicit + sexy > 0.70f
            else -> explicit > 0.60f || sexy > 0.85f
        }
    }
}
