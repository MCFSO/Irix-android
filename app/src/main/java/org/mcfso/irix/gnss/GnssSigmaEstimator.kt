package org.mcfso.irix.gnss

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 当厂商把硬件 σ 上报成 >1s 的“未知”哨兵时，用观测到的 UTC/单调节拍差估计
 * 实际抖动。
 *
 * 对相邻样本计算
 *
 *     delta = (utc_n - utc_{n-1}) - (mono_n - mono_{n-1})
 *
 * 它消掉了绝对偏移和固定频差；delta 的 MAD 就是时间戳噪声的稳健估计。
 * 8 帧以内用 20ms 先验，之后用经验值；不做“恒定 2ms”的假精度。
 */
class GnssSigmaEstimator(private val window: Int = 30) {
    init {
        require(window >= 8)
    }

    private val deltas = ArrayDeque<Double>(window)
    private var lastUtcNs: Long? = null
    private var lastMonoNs: Long? = null

    data class Estimate(val sigmaNs: Long, val source: String)

    fun reset() {
        deltas.clear()
        lastUtcNs = null
        lastMonoNs = null
    }

    fun add(utcNs: Long, monoNs: Long): Estimate {
        val prevUtc = lastUtcNs
        val prevMono = lastMonoNs
        if (prevUtc != null && prevMono != null) {
            val delta = (utcNs - prevUtc).toDouble() - (monoNs - prevMono).toDouble()
            if (!delta.isFinite() || abs(delta) > 1_000_000_000.0) {
                // 时钟跳变/步进：旧差值不再代表抖动。
                deltas.clear()
            } else {
                if (deltas.size == window) deltas.removeFirst()
                deltas.addLast(delta)
            }
        }
        lastUtcNs = utcNs
        lastMonoNs = monoNs

        if (deltas.size < 8) {
            return Estimate(PRIOR_SIGMA_NS, "prior")
        }
        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        // 两个样本差分的噪声是单样本的 sqrt(2) 倍；1.4826 把 MAD 转成 σ。
        val sigma = (1.4826 * mad / sqrt(2.0)).roundToLong()
            .coerceIn(MIN_SIGMA_NS, MAX_SIGMA_NS)
        return Estimate(sigma, "empirical")
    }

    private companion object {
        const val PRIOR_SIGMA_NS = 20_000_000L
        const val MIN_SIGMA_NS = 100L
        const val MAX_SIGMA_NS = 50_000_000L
    }
}
