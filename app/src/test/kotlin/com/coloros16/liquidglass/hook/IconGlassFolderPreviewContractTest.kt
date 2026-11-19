package com.coloros16.liquidglass.hook

import android.graphics.Canvas
import java.util.ArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Test

@Suppress("UNUSED_PARAMETER")
class IconGlassFolderPreviewContractTest {
    private class LegacyManager {
        fun drawParams(canvas: Canvas, page: Int, items: ArrayList<Any>, scale: Float, snapshot: Boolean?) = Unit
    }

    private class OtaManager {
        fun drawParams(canvas: Canvas, items: ArrayList<Any>, snapshot: Boolean?) = Unit
        fun drawParams(canvas: Canvas, page: Int, items: ArrayList<Any>, snapshot: Boolean?) = Unit
    }

    @Test fun selectsLegacyScaledPage() {
        assertArrayEquals(
            arrayOf(Canvas::class.java, Integer.TYPE, ArrayList::class.java, java.lang.Float.TYPE, java.lang.Boolean::class.java),
            IconGlassFolderPreviewContract.pageEntry(LegacyManager::class.java).parameterTypes,
        )
    }

    @Test fun selectsOtaPageOverListOnlyDrawing() {
        assertArrayEquals(
            arrayOf(Canvas::class.java, Integer.TYPE, ArrayList::class.java, java.lang.Boolean::class.java),
            IconGlassFolderPreviewContract.pageEntry(OtaManager::class.java).parameterTypes,
        )
    }
}
