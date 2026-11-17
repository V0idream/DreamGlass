package com.coloros16.liquidglass.hook

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Match the framework's actual default resource, never an application/package allowlist. */
internal object IconDefaultPolicy {
    fun matches(candidate:IntArray,reference:IntArray):Boolean {
        if(candidate.size!=reference.size || candidate.size<64)return false
        var difference=0L
        var substantial=0
        for(i in candidate.indices) {
            val a=candidate[i];val b=reference[i]
            difference+=kotlin.math.abs((a ushr 24)-(b ushr 24))
            if((a ushr 24)>32 || (b ushr 24)>32) {
                substantial++
                difference+=kotlin.math.abs((a shr 16 and 255)-(b shr 16 and 255))
                difference+=kotlin.math.abs((a shr 8 and 255)-(b shr 8 and 255))
                difference+=kotlin.math.abs((a and 255)-(b and 255))
            }
        }
        return substantial>candidate.size/4 && difference<=candidate.size*8L
    }
}

/** One procedural robot glyph for all default Android icons; user overrides still win. */
internal object IconGlassDefaultIcon {
    private var configuration=Int.MIN_VALUE
    private var reference:IntArray?=null
    private fun pixels(drawable:Drawable,context:Context):IntArray?=runCatching {
        val clone=drawable.constantState?.newDrawable(context.resources)?.mutate() ?: return@runCatching null
        if(clone===drawable)return@runCatching null
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        try {
            clone.setBounds(0,0,32,32);clone.draw(Canvas(bitmap))
            IntArray(1024).also {bitmap.getPixels(it,0,32,0,0,32,32)}
        } finally {bitmap.recycle()}
    }.getOrNull()
    fun create(context:Context,drawable:Drawable?,size:Int):IconGlassMasks.Prepared? {
        drawable ?: return null
        val current=context.resources.configuration.hashCode()
        if(current!=configuration || reference==null) {
            reference=pixels(context.packageManager.defaultActivityIcon,context);configuration=current
        }
        val expected=reference ?: return null
        if(!IconDefaultPolicy.matches(pixels(drawable,context) ?: return null,expected))return null
        return glyph(size)
    }
    fun glyph(size:Int):IconGlassMasks.Prepared {
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(size/100f,size/100f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;strokeWidth=3.5f;strokeCap=Paint.Cap.ROUND}
        canvas.drawLine(32f,30f,25f,19f,paint);canvas.drawLine(68f,30f,75f,19f,paint)
        canvas.drawArc(RectF(21f,25f,79f,76f),180f,180f,true,paint)
        canvas.drawRoundRect(RectF(21f,52f,79f,72f),5f,5f,paint)
        paint.color=0xffa5b2c3.toInt();canvas.drawCircle(37f,42f,3.1f,paint);canvas.drawCircle(63f,42f,3.1f,paint)
        return IconGlassMasks.Prepared(bitmap,"android-default",relief=true)
    }
}
