package com.converter.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages coroutine dispatchers for the conversion engine.
 *
 * Centralizes dispatcher configuration for easier testing
 * and consistent threading behavior.
 */
class CoroutineManager(
    /**
     * Dispatcher for I/O-bound operations (file reading, parsing).
     */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    /**
     * Dispatcher for CPU-bound operations (layout, text measurement).
     */
    val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,

    /**
     * Dispatcher for UI updates (progress reporting).
     */
    val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    /**
     * Execute block on IO dispatcher.
     */
    suspend fun <T> withIO(block: suspend () -> T): T =
        withContext(ioDispatcher) { block() }

    /**
     * Execute block on compute dispatcher.
     */
    suspend fun <T> withCompute(block: suspend () -> T): T =
        withContext(computeDispatcher) { block() }

    /**
     * Execute block on main dispatcher.
     */
    suspend fun <T> withMain(block: suspend () -> T): T =
        withContext(mainDispatcher) { block() }

    companion object {
        /**
         * Default instance using standard dispatchers.
         */
        val default = CoroutineManager()

        /**
         * Create instance for testing (uses unconfined dispatchers).
         */
        fun forTesting(): CoroutineManager = CoroutineManager(
            ioDispatcher = Dispatchers.Unconfined,
            computeDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined
        )
    }
}
