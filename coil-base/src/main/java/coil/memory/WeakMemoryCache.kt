package coil.memory

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import android.graphics.Bitmap
import android.os.Build.VERSION.SDK_INT
import androidx.annotation.VisibleForTesting
import coil.memory.MemoryCache.Key
import coil.memory.MemoryCache.Value
import coil.util.firstNotNullOfOrNullIndices
import coil.util.identityHashCode
import coil.util.removeIfIndices
import java.lang.ref.WeakReference

/**
 * An in-memory cache that holds weak references to [Bitmap]s.
 *
 * This is used as a secondary caching layer for [StrongMemoryCache].
 * [StrongMemoryCache] holds strong references to its bitmaps.
 * Bitmaps are added to this cache when they're removed from [StrongMemoryCache].
 */
internal interface WeakMemoryCache {

    /** Get the value associated with [key]. */
    fun get(key: Key): Value?

    /** Set the value associated with [key]. */
    fun set(key: Key, bitmap: Bitmap, isSampled: Boolean, size: Int)

    /** Remove the value referenced by [key] from this cache. */
    fun remove(key: Key): Boolean

    /** Remove [bitmap] from this cache. */
    fun remove(bitmap: Bitmap): Boolean

    /** Remove all values from this cache. */
    fun clearMemory()

    /** @see ComponentCallbacks2.onTrimMemory */
    fun trimMemory(level: Int)
}

/** A [WeakMemoryCache] implementation that holds no references. */
internal class EmptyWeakMemoryCache : WeakMemoryCache {

    override fun get(key: Key): Value? = null

    override fun set(key: Key, bitmap: Bitmap, isSampled: Boolean, size: Int) {}

    override fun remove(key: Key) = false

    override fun remove(bitmap: Bitmap) = false

    override fun clearMemory() {}

    override fun trimMemory(level: Int) {}
}

/** A [WeakMemoryCache] implementation backed by a [HashMap]. */
internal class RealWeakMemoryCache : WeakMemoryCache {

    private val cache = hashMapOf<Key, ArrayList<InternalValue>>()
    private var operationsSinceCleanUp = 0

    @Synchronized
    override fun get(key: Key): Value? {
        val values = cache[key] ?: return null

        // Find the first bitmap that hasn't been collected.
        val value = values.firstNotNullOfOrNullIndices { value ->
            value.bitmap.get()?.let { Value(it, value.isSampled) }
        }

        cleanUpIfNecessary()
        return value
    }

    @Synchronized
    override fun set(key: Key, bitmap: Bitmap, isSampled: Boolean, size: Int) {
        val values = cache.getOrPut(key) { arrayListOf() }

        // Insert the value into the list sorted descending by size.
        run {
            val identityHashCode = bitmap.identityHashCode
            val newValue = InternalValue(identityHashCode, WeakReference(bitmap), isSampled, size)
            for (index in values.indices) {
                val value = values[index]
                if (size >= value.size) {
                    if (value.identityHashCode == identityHashCode && value.bitmap.get() === bitmap) {
                        values[index] = newValue
                    } else {
                        values.add(index, newValue)
                    }
                    return@run
                }
            }
            values += newValue
        }

        cleanUpIfNecessary()
    }

    @Synchronized
    override fun remove(key: Key): Boolean {
        return cache.remove(key) != null
    }

    @Synchronized
    override fun remove(bitmap: Bitmap): Boolean {
        val identityHashCode = bitmap.identityHashCode

        // Find the bitmap in the cache and remove it.
        val removed = run {
            cache.values.forEach { values ->
                for (index in values.indices) {
                    if (values[index].identityHashCode == identityHashCode) {
                        values.removeAt(index)
                        return@run true
                    }
                }
            }
            return@run false
        }

        cleanUpIfNecessary()
        return removed
    }

    /** Remove all values from this cache. */
    @Synchronized
    override fun clearMemory() {
        operationsSinceCleanUp = 0
        cache.clear()
    }

    /** @see ComponentCallbacks2.onTrimMemory */
    @Synchronized
    override fun trimMemory(level: Int) {
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) {
            cleanUp()
        }
    }

    private fun cleanUpIfNecessary() {
        if (operationsSinceCleanUp++ >= CLEAN_UP_INTERVAL) {
            cleanUp()
        }
    }

    /** Remove any dereferenced bitmaps from the cache. */
    @VisibleForTesting
    internal fun cleanUp() {
        operationsSinceCleanUp = 0

        // Remove all the values whose references have been collected.
        val iterator = cache.values.iterator()
        while (iterator.hasNext()) {
            val list = iterator.next()

            if (list.count() <= 1) {
                // Typically, the list will only contain 1 item. Handle this case in an optimal way here.
                if (list.firstOrNull()?.bitmap?.get() == null) {
                    iterator.remove()
                }
            } else {
                // Iterate over the list of values and delete individual entries that have been collected.
                if (SDK_INT >= 24) {
                    list.removeIf { it.bitmap.get() == null }
                } else {
                    list.removeIfIndices { it.bitmap.get() == null }
                }

                if (list.isEmpty()) {
                    iterator.remove()
                }
            }
        }
    }

    private class InternalValue(
        val identityHashCode: Int,
        val bitmap: WeakReference<Bitmap>,
        val isSampled: Boolean,
        val size: Int
    )

    companion object {
        private const val CLEAN_UP_INTERVAL = 10
    }
}
