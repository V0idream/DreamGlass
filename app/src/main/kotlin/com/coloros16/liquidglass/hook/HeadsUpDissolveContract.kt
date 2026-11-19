package com.coloros16.liquidglass.hook

/** Only the confirmed COS17 user drag acceptance, never timeout/replacement/app opening. */
internal object HeadsUpDissolveContract {
    fun dragEnd(animator: Class<*>, callback: Class<*>): java.lang.reflect.Method {
        require(callback.name == "kotlin.jvm.functions.Function1")
        val methods = animator.declaredMethods.filter {
            it.name == "onEndDrag" && it.returnType == java.lang.Boolean.TYPE
        }
        val velocity = java.lang.Float.TYPE
        return methods.singleOrNull { it.parameterTypes.contentEquals(arrayOf(velocity, velocity, callback)) }
            ?: methods.single { it.parameterTypes.contentEquals(arrayOf(velocity, callback)) }
    }

    fun accepted(animated:Boolean,reason:String?)=animated && reason=="onEndDrag"
    fun managerRelease(fingerUp:Boolean,reason:String?)=
        fingerUp && reason=="OplusHeadsUpTouchHelper.onTouchEvent"
    fun windowRoot(className:String,type:Int,verifiedContainer:Boolean=false)=
        type==2017 && (verifiedContainer || className.contains("HeadsUp",ignoreCase=true))

    // Fast swipes may already have moved the complete row above the visible window.
    // Keep a bounded part of the handoff above the display so the outgoing particles show.
    fun releaseY(y:Float,height:Int):Float = maxOf(y,-height*.45f)

    fun releaseX(x:Float,width:Int,screenWidth:Int):Float =
        x.coerceIn(-width*.45f,maxOf(-width*.45f,screenWidth-width*.55f))
}
