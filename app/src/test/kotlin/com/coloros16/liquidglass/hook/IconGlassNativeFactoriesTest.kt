package com.coloros16.liquidglass.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class IconGlassNativeFactoriesTest {
    class Bitmap
    class Context
    open class Drawable
    class LegacyDrawable(val bitmap: Bitmap) : Drawable()
    class CurrentDrawable(val bitmap: Bitmap, val color: Int) : Drawable()

    class LegacyInfo {
        fun newIcon(context: Context) = LegacyDrawable(Bitmap())
        fun newIcon(context: Context, flags: Int) = LegacyDrawable(Bitmap())
    }

    class CurrentInfo {
        fun newIcon(flags: Int, context: Context) = CurrentDrawable(Bitmap(), flags)
        fun newIcon(context: Context, flags: Int): String = "unrelated overload"
    }

    @Test fun choosesTheActualDrawableConstructor() {
        val image = Bitmap()
        val old = IconGlassNativeFactories.drawableConstructor(LegacyDrawable::class.java, Bitmap::class.java)
        val current = IconGlassNativeFactories.drawableConstructor(CurrentDrawable::class.java, Bitmap::class.java)
        assertEquals(image, (old.newInstance(image) as LegacyDrawable).bitmap)
        val replacement = current.newInstance(image, 0x123456) as CurrentDrawable
        assertEquals(image, replacement.bitmap)
        assertEquals(0x123456, replacement.color)
    }

    @Test fun acceptsBothFactoryOrdersWithoutPickingUnrelatedOverloads() {
        val old = IconGlassNativeFactories.bitmapInfoFactories(LegacyInfo::class.java, Context::class.java, Drawable::class.java)
        val current = IconGlassNativeFactories.bitmapInfoFactories(CurrentInfo::class.java, Context::class.java, Drawable::class.java).single()
        assertEquals(setOf(1, 2), old.map { it.parameterCount }.toSet())
        assertEquals(LegacyDrawable::class.java, old.single { it.parameterCount == 1 }.invoke(LegacyInfo(), Context()).javaClass)
        assertEquals(LegacyDrawable::class.java, old.single { it.parameterCount == 2 }.invoke(LegacyInfo(), Context(), 0).javaClass)
        val drawable = current.invoke(CurrentInfo(), 7, Context()) as CurrentDrawable
        assertEquals(7, drawable.color)
    }
}
