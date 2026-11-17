package com.coloros16.liquidglass.hook

internal object IconGlassMode {
    const val PREFAB="prefab"
    const val LIVE="live"
    fun parse(value:String?)=if(value==LIVE)LIVE else PREFAB
    fun preferLive(value:String?)=parse(value)==LIVE
}
