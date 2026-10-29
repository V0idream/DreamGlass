package com.coloros16.liquidglass.hook

/** Background motion is independent of panel gesture motion. No screen-content storage. */
class BackgroundCadence {
    @Volatile private var dynamicUntil=0L
    @Volatile private var eventUntil=0L
    @Volatile private var unchanged=0
    @Synchronized fun observe(now:Long, changed:Boolean) {
        if(changed) { dynamicUntil=now+350L;unchanged=0 } else unchanged=(unchanged+1).coerceAtMost(16)
    }
    fun event(now:Long) { if(unchanged<4)eventUntil=now+120L }
    fun reveal(now:Long) { unchanged=0;eventUntil=now+120L }
    fun intervalMs(now:Long,hz:Int,moving:Boolean,immediate:Boolean):Long {
        if(immediate)return RuntimeWorkPolicy.captureIntervalMs(hz,moving,true)
        if(moving || now<dynamicUntil || now<eventUntil)return 1000L/hz.coerceIn(1,30)
        return if(unchanged>=4)167L else 83L
    }
}
