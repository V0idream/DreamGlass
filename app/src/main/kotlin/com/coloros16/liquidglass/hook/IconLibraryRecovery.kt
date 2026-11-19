package com.coloros16.liquidglass.hook

/** A transient read failure never replaces last-known data. Retries run only for pending work. */
internal class IconLibraryRecovery<T : Any> {
    @Volatile var snapshot: T? = null
        private set
    private var nextAttempt = 0

    fun accept(value: T?): Boolean {
        if (value == null || snapshot == value) return false
        snapshot = value
        return true
    }

    fun resetBudget() { nextAttempt = 0 }

    fun retryDelay(hasPendingWork: Boolean): Long? =
        if (hasPendingWork) retryDelays.getOrNull(nextAttempt)?.also { nextAttempt++ } else null

    companion object {
        private val retryDelays = longArrayOf(250, 750, 1500, 3000, 5000)
    }
}
