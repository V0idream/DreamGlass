package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.GalleryNativeNodeAccessor
import org.junit.Assert.*
import org.junit.Test

class GalleryNativeNodeAccessorTest {
    private class Node
    private class RuntimeWrapper(private val node:Node) {
        // Simulates an OEM stub with a widened declared return type.
        fun getRenderNode():Any=node
    }
    private class StubView(private val wrapper:Any) {fun getViewWrapper():Any=wrapper}
    private class DirectView(@Suppress("unused") private val mRenderNode:Node)
    private class InvalidView(@Suppress("unused") private val mRenderNode:String) {
        fun getViewWrapper():Any=object {fun getRenderNode():Any="not-a-node"}
    }
    @Test fun privateRuntimeImplementationWithWidenedStubTypeIsSupported() {
        val node=Node();val view=StubView(RuntimeWrapper(node))
        val result=GalleryNativeNodeAccessor(StubView::class.java,Node::class.java).read(view)
        assertSame(node,result?.node);assertEquals("runtime-wrapper",result?.route)
    }
    @Test fun exactNativeFieldWorksWithoutAnyWrapperApi() {
        val node=Node();val view=DirectView(node)
        val result=GalleryNativeNodeAccessor(DirectView::class.java,Node::class.java).read(view)
        assertSame(node,result?.node);assertEquals("typed-View.mRenderNode",result?.route)
    }
    @Test fun sameNamedWrongTypeAndForeignViewAreRejected() {
        val accessor=GalleryNativeNodeAccessor(InvalidView::class.java,Node::class.java)
        assertNull(accessor.read(InvalidView("wrong")))
        assertNull(accessor.read(DirectView(Node())))
    }
}
