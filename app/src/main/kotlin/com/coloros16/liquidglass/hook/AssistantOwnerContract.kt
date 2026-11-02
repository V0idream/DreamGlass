/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/** COS17 Shelf's fill owners, not its foreground-only HighlightDrawable. Validate
 * every captured paint/geometry/state field before trusting an obfuscated name.
 */
internal object AssistantOwnerContract {
    data class Spec(
        val name:String,val path:String,val enabled:String,
        val alpha:String?=null,val blocked:String?=null,val drawsDecoration:Boolean=true,
        val card:Boolean=true,
    )
    val specs=listOf(
        Spec("bc.e","B","x"),
        Spec("bc.f","h","d"),
        Spec("com.coloros.common.widget.HighlightShadowConstraintLayout","i","d",alpha="g"),
        Spec("com.coloros.common.widget.HighlightShadowImageView","n","d",blocked="g",drawsDecoration=false,card=false),
    )
    data class Resolved(
        val spec:Spec,val type:Class<*>,val onDraw:Method,val draw:Method?,
        val painter:Method,val path:Field,val enabled:Field,val alpha:Field?,val blocked:Field?,
    )
    fun resolve(type:Class<*>,spec:Spec,view:Class<*>,canvas:Class<*>,path:Class<*>,painter:Class<*>):Resolved? =
        runCatching {
            check(view.isAssignableFrom(type))
            fun field(name:String,expected:Class<*>)=type.getDeclaredField(name).apply {
                check(this.type==expected);isAccessible=true
            }
            val nativeOnDraw=type.getDeclaredMethod("onDraw",canvas).apply {check(returnType==Void.TYPE);isAccessible=true}
            val nativeDraw=if(spec.drawsDecoration)type.getDeclaredMethod("draw",canvas).apply {
                check(returnType==Void.TYPE);isAccessible=true
            } else null
            val nativePainter=type.getDeclaredMethod("getMHighlightDelegate").apply {
                check(returnType==painter);isAccessible=true
            }
            Resolved(spec,type,nativeOnDraw,nativeDraw,nativePainter,field(spec.path,path),
                field(spec.enabled,Boolean::class.javaPrimitiveType!!),
                spec.alpha?.let {field(it,Float::class.javaPrimitiveType!!)},
                spec.blocked?.let {field(it,Boolean::class.javaPrimitiveType!!)},
            )
        }.getOrNull()
}
