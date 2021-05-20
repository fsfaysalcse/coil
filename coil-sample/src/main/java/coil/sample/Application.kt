@file:Suppress("unused")

package coil.sample

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import androidx.viewbinding.BuildConfig
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import coil.util.DebugLogger
import coil.util.buildForImageLoader
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File

class Application : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val memoryCache = MemoryCache.Builder(this)
            .maxSizePercent(0.25) // Use 25% of the application's available memory.
            .build()
        return ImageLoader.Builder(this)
            .memoryCache(memoryCache)
            .crossfade(true) // Show a short crossfade when loading images from network or disk.
            .componentRegistry {
                // GIFs
                if (SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // SVGs
                add(SvgDecoder.Factory())
                // Video frames
                add(VideoFrameDecoder.Factory())
            }
            .okHttpClient {
                // Create a disk cache with "unlimited" size. Don't do this in production.
                // To create the an optimized Coil disk cache, use CoilUtils.createDiskCache(context).
                val cacheDirectory = File(filesDir, "image_cache").apply { mkdirs() }
                val diskCache = Cache(cacheDirectory, Long.MAX_VALUE)

                // Rewrite the Cache-Control header to cache all responses for a year.
                val cacheControlInterceptor = ResponseHeaderInterceptor("Cache-Control", "max-age=31536000,public")

                // Don't limit concurrent network requests by host.
                val dispatcher = Dispatcher().apply { maxRequestsPerHost = maxRequests }

                // Lazily create the OkHttpClient that is used for network operations.
                OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .addNetworkInterceptor(cacheControlInterceptor)
                    .buildForImageLoader(this, diskCache = diskCache)
            }
            .apply {
                // Enable logging to the standard Android log if this is a debug build.
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger(Log.VERBOSE))
                }
            }
            .build()
    }
}
