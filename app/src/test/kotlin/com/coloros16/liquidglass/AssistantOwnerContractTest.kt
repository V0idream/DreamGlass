package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.AssistantOwnerContract as Contract
import org.junit.Assert.*
import org.junit.Test

class AssistantOwnerContractTest {
    open class Host
    class CanvasToken
    class PathToken
    interface Painter
    companion object {var delegateCalls=0}
    private val spec=Contract.Spec("current-owner","body","enabled",alpha="alpha",blocked="blocked")
    class Card:Host() {
        @JvmField var body=PathToken()
        @JvmField var enabled=true
        @JvmField var alpha=.5f
        @JvmField var blocked=false
        fun onDraw(canvas:CanvasToken) {}
        fun draw(canvas:CanvasToken) {}
        private fun getMHighlightDelegate():Painter {delegateCalls++;error("Do not initialize native painter while matching")}
    }
    class ImageOwner:Host() {
        @JvmField var body=PathToken()
        @JvmField var enabled=true
        @JvmField var blocked=false
        fun onDraw(canvas:CanvasToken) {}
        private fun getMHighlightDelegate():Painter=error("unused")
    }
    class ReusedNameWrongGeometry:Host() {
        @JvmField var body=Any()
        @JvmField var enabled=true
        @JvmField var alpha=.5f
        @JvmField var blocked=false
        fun onDraw(canvas:CanvasToken) {}
        fun draw(canvas:CanvasToken) {}
        private fun getMHighlightDelegate():Painter=error("unused")
    }
    class WrongDelegate:Host() {
        @JvmField var body=PathToken()
        @JvmField var enabled=true
        @JvmField var alpha=.5f
        @JvmField var blocked=false
        fun onDraw(canvas:CanvasToken) {}
        fun draw(canvas:CanvasToken) {}
        private fun getMHighlightDelegate():Any=Any()
    }
    class DecorationOnly {
        @JvmField var body=PathToken()
        @JvmField var enabled=true
        @JvmField var alpha=.5f
        @JvmField var blocked=false
        fun draw(canvas:CanvasToken) {}
        private fun getMHighlightDelegate():Painter=error("unused")
    }
    private fun resolve(type:Class<*>,requested:Contract.Spec=spec)=Contract.resolve(type,requested,
        Host::class.java,CanvasToken::class.java,PathToken::class.java,Painter::class.java)

    @Test fun realOwnerRequiresTypedFillGeometryAndActualDrawingEntryPoints() {
        val found=resolve(Card::class.java)!!
        assertEquals(PathToken::class.java,found.path.type)
        assertEquals(Boolean::class.javaPrimitiveType,found.enabled.type)
        assertEquals(Float::class.javaPrimitiveType,found.alpha!!.type)
        assertNotNull(found.draw)
    }
    @Test fun matchingDoesNotInitializeTheKoinDelegateOrInvokeNativeDrawing() {
        delegateCalls=0
        assertNotNull(resolve(Card::class.java))
        assertEquals(0,delegateCalls)
    }
    @Test fun foregroundOnlyDecorationCannotMasqueradeAsACardBackground() {
        assertNull(resolve(DecorationOnly::class.java))
    }
    @Test fun reusedObfuscatedNameWithWrongPathFieldIsRejected() {
        assertNull(resolve(ReusedNameWrongGeometry::class.java))
    }
    @Test fun sameMethodNameWithWrongDelegateReturnTypeIsRejected() {
        assertNull(resolve(WrongDelegate::class.java))
    }
    @Test fun imageOwnerKeepsItsNativeOnDrawRatherThanRequiringAWholeDrawReplacement() {
        val image=spec.copy(alpha=null,drawsDecoration=false,card=false)
        val found=resolve(ImageOwner::class.java,image)!!
        assertNull(found.draw);assertNull(found.alpha);assertNotNull(found.onDraw)
        assertFalse(found.spec.card)
    }
    @Test fun aMissingRequiredDrawOrStateFieldFallsBackInsteadOfPartlyMatching() {
        assertNull(resolve(ImageOwner::class.java))
        assertNull(resolve(Card::class.java,spec.copy(enabled="missing")))
        assertNull(resolve(Card::class.java,spec.copy(alpha="blocked")))
    }
}
