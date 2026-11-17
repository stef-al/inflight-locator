package inflight.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Small deterministic random generator (SplitMix64), so a seeded run repeats on every device.
 * Not for cryptography.
 */
class Rng(seed: Long) {
    private var state = seed
    private var spare = 0.0
    private var hasSpare = false

    fun nextLong(): Long {
        state += 0x9E3779B97F4A7C15uL.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun uniform(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun uniform(a: Double, b: Double): Double = a + (b - a) * uniform()

    /** Standard normal (polar Box-Muller). */
    fun normal(): Double {
        if (hasSpare) {
            hasSpare = false
            return spare
        }
        while (true) {
            val u = 2.0 * uniform() - 1.0
            val v = 2.0 * uniform() - 1.0
            val s = u * u + v * v
            if (s > 0.0 && s < 1.0) {
                val f = sqrt(-2.0 * ln(s) / s)
                spare = v * f
                hasSpare = true
                return u * f
            }
        }
    }

    fun normal(mean: Double, sd: Double): Double = mean + sd * normal()

    fun poisson(lambda: Double): Int {
        if (lambda <= 0.0) return 0
        if (lambda > 40.0) return maxOf(0, normal(lambda, sqrt(lambda)).roundToInt())
        val limit = exp(-lambda)
        var k = 0
        var p = uniform()
        while (p > limit) {
            k++
            p *= uniform()
        }
        return k
    }

    /** Index drawn with probability proportional to the weights. */
    fun choice(weights: DoubleArray): Int {
        var u = uniform() * weights.sum()
        for (i in weights.indices) {
            u -= weights[i]
            if (u < 0.0) return i
        }
        return weights.size - 1
    }
}
