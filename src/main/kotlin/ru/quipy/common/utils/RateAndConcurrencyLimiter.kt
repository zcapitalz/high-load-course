package ru.quipy.common.utils

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.math.min

class RateAndConcurrencyLimiter(
    private val rateLimit: Int,
    private val rateLimitWindowMs: Long,
    private val concurrencyLimit: Long,
) {
    private val tickTimestamps = LongArray(rateLimit)
    private val tickTimestampsRwLock = ReentrantReadWriteLock()
    private var head = 0

    private var concurrencyCount : Long = 0
    private var concurrencyRwLock = ReentrantReadWriteLock()

    private var blockingLoopDelayMs = min(1, rateLimitWindowMs / concurrencyLimit / 2)

    fun execute(f: () -> Unit) {
        lease()
        try {
            f()
        } finally {
            release()
        }
    }

    fun lease() {
        while (true) {
            while (!(canAddRate() && canAddConcurrent())) {
                Thread.sleep(blockingLoopDelayMs)
            }
            if (!addRate()) {
                continue
            }
            if (!addConcurrent()) {
                continue
            }

            return
        }
    }

    fun release() {
        concurrencyRwLock.writeLock().withLock {
            concurrencyCount--
        }
    }

    private fun canAddConcurrent(): Boolean {
        concurrencyRwLock.readLock().withLock {
            return concurrencyCount < concurrencyLimit
        }
    }

    private fun addConcurrent(): Boolean {
        concurrencyRwLock.writeLock().withLock {
            if (concurrencyCount >= concurrencyLimit) {
                return false
            }
            // Read-write lock is used for concurrencyCount to have a strict guarantee
            // that concurrencyCount has not changed here since it was read in above comparison.
            // It may be acceptable to use atomic counter instead in case of big throughput and
            // if we can allow concurrencyCount to get bigger than concurrencyLimit sometimes.
            concurrencyCount++
            return true
        }
    }

    private fun canAddRate(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - rateLimitWindowMs
        tickTimestampsRwLock.readLock().withLock {
            if (tickTimestamps[head] < windowStart) {
                return true
            }
            return false
        }
    }

    private fun addRate(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - rateLimitWindowMs
        tickTimestampsRwLock.writeLock().withLock {
            if (tickTimestamps[head] < windowStart) {
                tickTimestamps[head] = now
                head = (head + 1) % rateLimit
                return true
            }
            return false
        }
    }
}