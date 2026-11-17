package com.coloros16.liquidglass.hook

/** Only the confirmed COS17 user drag acceptance, never timeout/replacement/app opening. */
internal object HeadsUpDissolveContract {
    fun accepted(animated:Boolean,reason:String?)=animated && reason=="onEndDrag"
    fun windowRoot(className:String,type:Int)=type==2017 && className.contains("HeadsUp",ignoreCase=true)
}
