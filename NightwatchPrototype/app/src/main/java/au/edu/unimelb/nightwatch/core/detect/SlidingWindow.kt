package au.edu.unimelb.nightwatch.core.detect

/**
 * Fixed-size circular buffer for a stream of sensor samples.
 *
 * Sensor callbacks fire ~50 times a second, so allocating a new list per window
 * would thrash the garbage collector. This overwrites one slot per sample and
 * computes statistics in a single pass over the backing array.
 */
class SlidingWindow(val capacity: Int) {

    private val values = FloatArray(capacity)
    private var writeIndex = 0
    private var filled = 0

    val size: Int get() = filled
    val isFull: Boolean get() = filled == capacity

    fun add(value: Float) {
        values[writeIndex] = value
        writeIndex = (writeIndex + 1) % capacity
        if (filled < capacity) filled++
    }

    fun clear() {
        writeIndex = 0
        filled = 0
    }

    fun mean(): Float {
        if (filled == 0) return 0f
        var sum = 0f
        for (i in 0 until filled) sum += values[i]
        return sum / filled
    }

    /** Population variance. Used as the main gait/stillness feature. */
    fun variance(): Float {
        if (filled < 2) return 0f
        val m = mean()
        var acc = 0f
        for (i in 0 until filled) {
            val d = values[i] - m
            acc += d * d
        }
        return acc / filled
    }

    fun max(): Float {
        if (filled == 0) return 0f
        var m = values[0]
        for (i in 1 until filled) if (values[i] > m) m = values[i]
        return m
    }

    fun min(): Float {
        if (filled == 0) return 0f
        var m = values[0]
        for (i in 1 until filled) if (values[i] < m) m = values[i]
        return m
    }
}
