/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.config

import android.content.SharedPreferences

/** Coordinates module-local intent and framework runtime state. Store commits
 * are separate transactions, so failures are reported rather than pretending
 * that two stores were atomically updated. No UI or service dependency here.
 */
object MemberStoreCoordinator {
    fun synchronize(local: SharedPreferences, remote: SharedPreferences): Boolean =
        synchronize(local, remote, MemberFeaturePolicy.currentVersion)

    @Synchronized
    internal fun synchronize(local: SharedPreferences, remote: SharedPreferences, version: String): Boolean {
        val localState = confirmation(local, version)
        if (localState.getOrNull() != true) {
            // Even a failed local read must attempt remote revocation. Do not
            // short-circuit the lock just because the read already failed.
            val locked = tryLock(remote)
            return localState.isSuccess && locked
        }
        val remoteState = confirmation(remote, version)
        if (remoteState.isFailure) { tryLock(remote); return false }
        if (remoteState.getOrNull() == true) return true // Preserve selected switches.

        // A never-confirmed/revoked remote store: clear switches BEFORE copying local
        // confirmation. Neither legacy flags nor missing-default-true options
        // should silently activate after reconnection or upgrade.
        if (!tryLock(remote)) return false
        if (!runCatching { MemberFeaturePolicy.confirmExplicitly(remote, version) }.getOrDefault(false)) {
            tryLock(remote); return false
        }
        val complete = confirmation(local, version).getOrNull() == true &&
            confirmation(remote, version).getOrNull() == true
        if (!complete) tryLock(remote) // A concurrent local revocation cannot grant remote access.
        return complete
    }

    /** Always attempt BOTH stores, even if either commit throws or returns false.
     * A false result means the caller must report/retry persistence failure. */
    @Synchronized
    fun revoke(local: SharedPreferences, remote: SharedPreferences): Boolean {
        val localLocked = tryLock(local)
        val remoteLocked = tryLock(remote)
        return localLocked && remoteLocked
    }

    private fun tryLock(raw: SharedPreferences): Boolean =
        runCatching { MemberFeaturePolicy.lock(raw) }.getOrDefault(false)

    private fun confirmation(raw: SharedPreferences, version: String): Result<Boolean> = runCatching {
        MemberFeaturePolicy.tokenConfirmed(MemberFeaturePolicy.unfiltered(raw).getString(MemberFeaturePolicy.KEY_CONFIRMED_VERSION, null),version)
    }
}
