package com.coloros16.liquidglass

import com.coloros16.liquidglass.config.MemberFeaturePolicy
import com.coloros16.liquidglass.config.MemberStoreCoordinator
import org.junit.Assert.*
import org.junit.Test

class MemberStoreCoordinatorTest {
    private val version = MemberFeaturePolicy.versionToken("1.1.9-debug1", 1_001_009)
    private val feature = "notification_dissolve_enabled"
    private fun store() = MemberFeaturePolicyTest.MemoryPreferences()
    private fun confirm(p: MemberFeaturePolicyTest.MemoryPreferences, token: String = version) {
        assertTrue(MemberFeaturePolicy.confirmExplicitly(p, token))
    }
    private fun sync(local: MemberFeaturePolicyTest.MemoryPreferences, remote: MemberFeaturePolicyTest.MemoryPreferences) =
        MemberStoreCoordinator.synchronize(local, remote, version)

    @Test fun unconfirmedLocalAlwaysLocksRemoteEvenWhenRemoteCurrentAndEnabled() {
        val local = store(); val remote = store(); confirm(remote)
        remote.edit().putBoolean(feature, true).putBoolean("card_glass_enable", true).commit()
        assertTrue(sync(local, remote))
        assertFalse(MemberFeaturePolicy.confirmed(remote, version))
        MemberFeaturePolicy.memberBooleanKeys.forEach { assertEquals(false, remote.all[it]) }
        assertTrue(remote.getBoolean("card_glass_enable", false))
    }

    @Test fun matchingCurrentStoresKeepChosenSwitchesWithoutAnyWrite() {
        val local = store(); val remote = store(); confirm(local); confirm(remote)
        remote.edit().putBoolean(feature, true).putFloat("blur_radius", 19f).commit()
        val localBefore = local.commitAttempts; val remoteBefore = remote.commitAttempts
        assertTrue(sync(local, remote)); assertTrue(remote.getBoolean(feature, false))
        assertEquals(localBefore, local.commitAttempts); assertEquals(remoteBefore, remote.commitAttempts)
        assertEquals(19f, remote.getFloat("blur_radius", 0f), 0f)
    }

    @Test fun olderConfirmedRemotePreservesChosenSwitchesAcrossUpgrade() {
        val local = store(); val remote = store(); confirm(local); confirm(remote, "1001008@1.1.8")
        MemberFeaturePolicy.memberBooleanKeys.forEach { remote.edit().putBoolean(it, true).commit() }
        remote.edit().putBoolean("glass_enable", true).commit()
        val before = remote.commitAttempts
        assertTrue(sync(local, remote)); assertEquals(before, remote.commitAttempts)
        assertTrue(MemberFeaturePolicy.confirmed(remote, version))
        MemberFeaturePolicy.memberBooleanKeys.forEach { assertEquals(true, remote.all[it]) }
        assertTrue(remote.getBoolean("glass_enable", false))
    }

    @Test fun failedRemoteLockDoesNotProceedToConfirmOrReportSuccess() {
        val local = store(); val remote = store(); confirm(local)
        remote.failCommitNumbers += 1
        assertFalse(sync(local, remote)); assertEquals(1, remote.commitAttempts)
        assertFalse(MemberFeaturePolicy.confirmed(remote, version))
    }

    @Test fun failedRemoteConfirmationIsReportedAndLeftLocked() {
        val local = store(); val remote = store(); confirm(local)
        remote.failCommitNumbers += 2
        assertFalse(sync(local, remote)); assertEquals(3, remote.commitAttempts)
        assertFalse(MemberFeaturePolicy.confirmed(remote, version))
        MemberFeaturePolicy.memberBooleanKeys.forEach { assertEquals(false, remote.all[it]) }
    }

    @Test fun revokeAttemptsRemoteEvenWhenLocalCommitReturnsFalse() {
        val local = store(); val remote = store(); confirm(local); confirm(remote)
        local.failCommitNumbers += local.commitAttempts + 1
        val remoteBefore = remote.commitAttempts
        assertFalse(MemberStoreCoordinator.revoke(local, remote))
        assertEquals(remoteBefore + 1, remote.commitAttempts)
        assertFalse(MemberFeaturePolicy.confirmed(remote, version))
    }

    @Test fun revokeAttemptsRemoteEvenWhenLocalCommitThrowsAndReportsRemoteFailure() {
        val local = store(); val remote = store(); confirm(local); confirm(remote)
        local.throwOnCommit = true
        remote.failCommitNumbers += remote.commitAttempts + 1
        val remoteBefore = remote.commitAttempts
        assertFalse(MemberStoreCoordinator.revoke(local, remote))
        assertEquals(remoteBefore + 1, remote.commitAttempts)
    }

    @Test fun localReadFailureStillLocksRemoteButCannotBeReportedAsSuccess() {
        val local = store(); val remote = store(); confirm(remote)
        local.failStringReads = true
        assertFalse(sync(local, remote)); assertFalse(MemberFeaturePolicy.confirmed(remote, version))
    }

    @Test fun remoteReadFailureAttemptsLockAndNeverConfirmsUnreadableStore() {
        val local = store(); val remote = store(); confirm(local); confirm(remote)
        remote.failStringReads = true
        val before = remote.commitAttempts
        assertFalse(sync(local, remote)); assertEquals(before + 1, remote.commitAttempts)
        assertFalse(remote.values.containsKey(MemberFeaturePolicy.KEY_CONFIRMED_VERSION))
    }
}
