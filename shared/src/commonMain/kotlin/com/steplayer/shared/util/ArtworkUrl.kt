package com.steplayer.shared.util

/**
 * Lists often point artwork at a public image CDN in its full-size variant ("…/t/p/original/abc.jpg",
 * 2000–3000 px). Showing a 300 px tile from that means downloading ~1 MB and decoding a huge JPEG,
 * which is slow on TV boxes and, on Samsung TVs, rasterised in software. The same CDN serves fixed smaller
 * variants of the very same file at "…/t/p/w342/abc.jpg": ask for the smallest one that still covers
 * the tile. Any other URL is returned untouched.
 */
object ArtworkUrl {
    /** Widths the CDN serves for every image (poster and backdrop size lists combined). */
    private val WIDTHS = intArrayOf(92, 154, 185, 300, 342, 500, 780, 1280)

    private val PATTERN = Regex("""^(https?://image\.tmdb\.org/t/p/)(original|w\d+)(/[^?#]+.*)$""", RegexOption.IGNORE_CASE)

    /** [url] resized for a slot [widthPx] physical pixels wide; [widthPx] <= 0 means unknown (unchanged). */
    fun sized(url: String, widthPx: Int): String {
        if (widthPx <= 0) return url
        val m = PATTERN.matchEntire(url) ?: return url
        val (prefix, current, rest) = m.destructured
        val target = WIDTHS.firstOrNull { it >= widthPx } ?: return url // slot wider than any variant
        val currentWidth = if (current.equals("original", true)) Int.MAX_VALUE else current.drop(1).toIntOrNull() ?: return url
        // Never ask for a larger variant than the list gave.
        return if (target < currentWidth) "${prefix}w$target$rest" else url
    }
}
