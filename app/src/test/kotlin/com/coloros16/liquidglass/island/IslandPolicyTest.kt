package com.coloros16.liquidglass.island

import org.junit.Assert.*
import org.junit.Test

class IslandPolicyTest {
    private data class Event(
        override val id: String,
        override val kind: IslandKind,
        override val changedAt: Long = 50,
        override val expiresAt: Long = Long.MAX_VALUE,
        override val urgent: Boolean = false,
    ) : ScheduledIslandActivity

    @Test fun urgentCallPreemptsSelectionAndCannotBeHidden() {
        val call = Event("call", IslandKind.CALL, urgent = true)
        val timer = Event("timer", IslandKind.TIMER)
        val media = Event("media", IslandKind.MEDIA)
        assertEquals(listOf(call, media, timer),
            IslandPolicy.order(listOf(media, timer, call), setOf("call"), 100, "media"))
    }

    @Test fun dismissAndExpiryReselectAnEligibleActivity() {
        val expired = Event("code", IslandKind.CODE, expiresAt = 100)
        val media = Event("media", IslandKind.MEDIA)
        val timer = Event("timer", IslandKind.TIMER)
        assertEquals(listOf(media),
            IslandPolicy.order(listOf(expired, media, timer), setOf("timer"), 100, "timer"))
    }

    @Test fun nativePresentationFollowsTheSwitchBeforeAnyActivityArrives() {
        assertTrue(IslandPolicy.suppressNative(enabled = true, unlocked = true, hostAttached = true))
        assertFalse(IslandPolicy.suppressNative(enabled = false, unlocked = true, hostAttached = true))
        assertFalse(IslandPolicy.suppressNative(enabled = true, unlocked = false, hostAttached = true))
        assertFalse(IslandPolicy.suppressNative(enabled = true, unlocked = true, hostAttached = false))
    }

    @Test fun playbackActivityDisappearsInItsSourceAppAndReturnsOnExit() {
        val source = "example.player"
        assertTrue(IslandPolicy.isPlaybackApp(IslandKind.MEDIA, source, source))
        assertFalse(IslandPolicy.isPlaybackApp(IslandKind.MEDIA, source, "com.android.launcher"))
        assertFalse(IslandPolicy.isPlaybackApp(IslandKind.CALL, source, source))
        assertFalse(IslandPolicy.isPlaybackApp(IslandKind.MEDIA, source, null))
    }

    @Test fun cameraHeightUsesTheLensEvenWhenTheCutoutStartsAtTheDisplayEdge() {
        assertEquals(IslandPolicy.CapsuleVertical(61, 102), IslandPolicy.capsuleVertical(96, 0, 160, 3, 12, 90))
        assertEquals(IslandPolicy.CapsuleVertical(0, 53), IslandPolicy.capsuleVertical(180, 0, 50, 3, 12, 90))
        assertEquals(IslandPolicy.CapsuleVertical(12, 90), IslandPolicy.capsuleVertical(0, 0, 0, 3, 12, 90))
    }

    @Test fun oldZeroTimeoutUsesTheDefaultAndExplicitTimeoutStaysInRange() {
        assertEquals(5, IslandPrefs.autoCollapseSeconds(0))
        assertEquals(12, IslandPrefs.autoCollapseSeconds(12))
        assertEquals(30, IslandPrefs.autoCollapseSeconds(120))
    }

    @Test fun verificationCodeRequiresAnExplicitCue() {
        assertEquals("123456", IslandPolicy.code("您的验证码为 123456，请勿透露。"))
        assertEquals("80245", IslandPolicy.code("Verification code is: 80245"))
        assertEquals("763834", IslandPolicy.code("763834 是您的验证码"))
        assertNull(IslandPolicy.code("订单号 123456 已配送"))
        assertNull(IslandPolicy.code("Your security code is 18812341234"))
    }

    @Test fun lyricsRestorePositionOnSeekAndHandleOffsetAndTranslation() {
        val lines = IslandLyrics.parse("[offset:-200]\n[00:03.20]First\n[00:03.20]译文\n[00:10][00:15.400]Next")
        val track = IslandLyrics.Track(lines, translated = true, translationFirst = true)
        assertEquals(listOf(3000L, 9800L, 15200L), lines.map { it.time })
        assertEquals(-1, track.index(2900))
        assertEquals("译文 · First", track.display(track.index(3000)))
        assertEquals("Next", track.display(track.index(10_000)))
        assertEquals(0, track.index(5_000))
        assertEquals(IslandLyrics.key("Title", "Artist"), IslandLyrics.key(" title ", "artist"))
        assertNotEquals(IslandLyrics.key("Title", "Artist"), IslandLyrics.key("Title", "Another"))
    }
}
