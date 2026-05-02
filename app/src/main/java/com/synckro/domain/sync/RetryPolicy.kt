package com.synckro.domain.sync

import com.synckro.domain.provider.CloudProviderException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Wraps a suspending [block] with an exponential-backoff retry policy suitable
 * for cloud-provider calls.
 *
 * **Policy:**
 * - Maximum [maxAttempts] total attempts (including the first).
 * - Base delay doubles after each failure: 1 s → 2 s → 4 s → 8 s → 16 s (capped at [maxDelayMs]).
 * - ±[jitterFraction] random jitter is applied to each computed delay so that
 *   a burst of parallel workers doesn't hammer the provider in lock-step.
 * - If the provider throws [CloudProviderException.RateLimited], the delay
 *   from [CloudProviderException.RateLimited.retryAfterMs] is used instead of
 *   the computed backoff (but the computed backoff is used as a floor so
 *   subsequent attempts still back off normally).
 * - [CloudProviderException.AuthenticationRequired], [CloudProviderException.AuthenticationFailed],
 *   and [CloudProviderException.NotConfigured] are non-retriable and rethrown immediately.
 * - Any other exception is retried up to [maxAttempts] times.
 *
 * @param maxAttempts    Total number of attempts before giving up (default 5).
 * @param initialDelayMs Delay after the first failure in milliseconds (default 1 000).
 * @param maxDelayMs     Upper bound on computed backoff in milliseconds (default 32 000).
 * @param jitterFraction Fraction of the delay to add/subtract as random jitter (default 0.2).
 * @param onRetry        Optional callback invoked before each retry with the attempt index
 *                       (1-based) and the exception that caused the retry.
 * @param block          The suspending operation to execute.
 * @return The result of [block] on its first successful invocation.
 * @throws Throwable The last exception thrown by [block] after all attempts are exhausted.
 */
suspend fun <T> withRetry(
    maxAttempts: Int = MAX_ATTEMPTS,
    initialDelayMs: Long = INITIAL_DELAY_MS,
    maxDelayMs: Long = MAX_DELAY_MS,
    jitterFraction: Double = JITTER_FRACTION,
    onRetry: ((attempt: Int, cause: Throwable) -> Unit)? = null,
    block: suspend () -> T,
): T {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    var lastException: Throwable? = null
    var delayMs = initialDelayMs

    for (attempt in 1..maxAttempts) {
        try {
            return block()
        } catch (e: CloudProviderException.AuthenticationRequired) {
            throw e
        } catch (e: CloudProviderException.AuthenticationFailed) {
            throw e
        } catch (e: CloudProviderException.NotConfigured) {
            throw e
        } catch (e: Throwable) {
            lastException = e
            if (attempt == maxAttempts) break

            val baseDelay = when (e) {
                is CloudProviderException.RateLimited ->
                    // Honour Retry-After, but don't let it fall below our backoff floor.
                    maxOf(e.retryAfterMs, delayMs)
                else -> delayMs
            }

            val jitter = (baseDelay * jitterFraction * (Random.nextDouble() * 2 - 1)).toLong()
            val actualDelay = (baseDelay + jitter).coerceAtLeast(0L)

            onRetry?.invoke(attempt, e)
            delay(actualDelay)

            // Advance the base delay for the next attempt (exponential backoff).
            delayMs = min(delayMs * 2, maxDelayMs)
        }
    }

    throw lastException!!
}

// Default policy constants (matches the spec: max 5, 1 s → 32 s).
const val MAX_ATTEMPTS = 5
const val INITIAL_DELAY_MS = 1_000L
const val MAX_DELAY_MS = 32_000L
const val JITTER_FRACTION = 0.20
