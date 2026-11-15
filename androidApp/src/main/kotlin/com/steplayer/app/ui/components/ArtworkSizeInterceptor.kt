package com.steplayer.app.ui.components

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Dimension
import com.steplayer.shared.util.ArtworkUrl

/**
 * Asks the image CDN for the variant that fits the slot (see [ArtworkUrl]) instead of the full-size original:
 * less to download and to decode on TV boxes. Coil has already resolved the slot size in pixels here, so
 * no call site has to know it. If the smaller variant fails for any reason, the original URL is loaded.
 */
class ArtworkSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val sized = ArtworkUrl.sized(url, width)
        if (sized == url) return chain.proceed()
        val result = chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
        return if (result is ErrorResult) chain.proceed() else result
    }
}
