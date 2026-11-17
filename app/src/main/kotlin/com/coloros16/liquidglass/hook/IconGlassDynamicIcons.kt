package com.coloros16.liquidglass.hook

import android.graphics.*
import java.time.Instant
import java.time.ZoneId

/** System time events refresh these glyphs; never starts a clock, alarm or screenshot loop. */
internal object IconGlassDynamicIcons {
    fun kind(pkg:String?)=when(pkg) {
        "com.coloros.alarmclock","com.android.deskclock","com.google.android.deskclock"->"clock"
        "com.coloros.calendar","com.android.calendar","com.google.android.calendar","com.youloft.calendar"->"calendar"
        else->""
    }
    fun bucket(pkg:String?,millis:Long=System.currentTimeMillis(),zone:ZoneId?=null):String {
        val kind=kind(pkg);if(kind.isEmpty())return "static"
        val date=Instant.ofEpochMilli(millis).atZone(zone ?: ZoneId.systemDefault())
        return when(kind) {
            "clock"->"${date.toLocalDate()}/${date.hour}/${date.minute}/${date.offset}"
            "calendar"->date.toLocalDate().toString()
            else->"static"
        }
    }
    fun variant(identity:String?)=(identity?.substringBefore('/') ?: "")+"|"+
        bucket(identity?.substringBefore('/'))+"|"+IconGlassLibraryRuntime.revision
    fun create(pkg:String?,size:Int):IconGlassMasks.Prepared? {
        val kind=kind(pkg);if(kind.isEmpty())return null
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=size*.025f}
        if(kind=="clock") {
            canvas.drawCircle(size*.5f,size*.5f,size*.34f,paint)
            for(i in 0 until 12) {val save=canvas.save();canvas.rotate(i*30f,size*.5f,size*.5f)
                canvas.drawLine(size*.5f,size*.19f,size*.5f,size*.24f,paint);canvas.restoreToCount(save)}
        } else canvas.drawRoundRect(RectF(size*.16f,size*.16f,size*.84f,size*.84f),size*.055f,size*.055f,paint)
        paint(bitmap,pkg,kind)
        return IconGlassMasks.Prepared(bitmap,"dynamic-$kind",relief=true)
    }
    fun paint(bitmap:Bitmap,pkg:String?,override:String="",millis:Long=System.currentTimeMillis()) {
        val kind=override.ifEmpty {kind(pkg)};if(kind.isEmpty())return
        val date=Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val canvas=Canvas(bitmap);val s=bitmap.width.toFloat();val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.WHITE;strokeWidth=s*.032f;strokeCap=Paint.Cap.ROUND;style=Paint.Style.STROKE
        }
        if(kind=="clock") {
            fun hand(degrees:Float,length:Float,width:Float) {
                val save=canvas.save();canvas.rotate(degrees,s*.5f,s*.5f);paint.strokeWidth=width
                canvas.drawLine(s*.5f,s*.54f,s*.5f,s*(.5f-length),paint);canvas.restoreToCount(save)
            }
            hand((date.hour%12)*30f+date.minute*.5f,.18f,s*.038f)
            hand(date.minute*6f,.27f,s*.024f)
            paint.style=Paint.Style.FILL;canvas.drawCircle(s*.5f,s*.5f,s*.035f,paint)
        } else {
            paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            paint.textSize=s*.16f;paint.color=0xffb8c9dc.toInt()
            val day=date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT,java.util.Locale.getDefault())
            canvas.drawText(day,s*.5f,s*.35f,paint)
            paint.textSize=s*.40f;paint.color=Color.WHITE
            canvas.drawText(date.dayOfMonth.toString(),s*.5f,s*.74f,paint)
        }
    }
}
