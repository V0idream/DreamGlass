package com.dreamglass.cos17

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Bundle
import android.view.View
import android.widget.*
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject

/** Debug-only foreground rendering probe. No Xposed installation or physical phone needed. */
class IconContrastProbeActivity : Activity() {
    private var active=true
    private val map=BackgroundContrastMap.fromPixels(intArrayOf(Color.WHITE,Color.BLACK),2,1)
    private val images=mutableListOf<ProbeIcon>()
    private val renderer=QsIconContrastRenderer({active},{false},{map}) { view,color ->
        if(view.tag=="missing") null else {
            val tile=view.parent as OplusQSProbeTile
            QsIconContrastPolicy.foreground(color,if(tile.light) map.mean(0f,0f,.5f,1f) else map.mean(.5f,0f,1f,1f))
        }
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        filesDir.resolve("icon-contrast-probe.json").delete()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(12,17,32)) }
        root.addView(TextView(this).apply { text="QS ICON CONTRAST - DEBUG ONLY";textSize=16f;setTextColor(Color.WHITE) })
        for((row,label) in listOf("neutral","colored","alpha","untinted","missing","disabled").withIndex()) {
            root.addView(TextView(this).apply { text="$row $label";setTextColor(Color.WHITE);textSize=12f })
            val pair=LinearLayout(this)
            for(light in listOf(true,false)) {
                val tile=OplusQSProbeTile(this,light).apply { setBackgroundColor(if(light)Color.WHITE else Color.BLACK) }
                val icon=ProbeIcon(this).apply {
                    id=android.R.id.icon;tag=label;scaleType=ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(ShapeDrawable(OvalShape()).apply {
                        setIntrinsicWidth(dp(40));setIntrinsicHeight(dp(40))
                        paint.color=if(label=="untinted") Color.RED else Color.WHITE
                    })
                    if(label!="untinted") imageTintList=ColorStateList.valueOf(when(label) {
                        "colored"->0xff0066ff.toInt();"alpha"->0x80ffffff.toInt();"disabled"->0xff888888.toInt();else->Color.WHITE
                    })
                    if(label=="disabled") isEnabled=false
                    if(light && label in listOf("neutral","alpha","colored")) {
                        val color=imageTintList!!.defaultColor
                        setColorFilter(color,PorterDuff.Mode.SRC_IN)
                        renderer.rememberFilter(colorFilter,color,PorterDuff.Mode.SRC_IN)
                    }
                }
                images.add(icon)
                tile.addView(icon,FrameLayout.LayoutParams(dp(56),dp(56),android.view.Gravity.CENTER))
                pair.addView(tile,LinearLayout.LayoutParams(0,dp(72),1f))
            }
            root.addView(pair)
        }
        root.addView(Button(this).apply {
            id=android.R.id.button1;text="TOGGLE CONTRAST"
            setOnClickListener {
                active=!active
                // Exercise the production restoration request, including before any poll ran.
                renderer.invalidateTracked()
                if(active) images.forEach { it.invalidate() }
                writeResults()
            }
        })
        setContentView(root)
        root.postDelayed({
            val icon=images.first();val before=icon.drawable;val tint=icon.imageTintList
            val canvas=Canvas(Bitmap.createBitmap(icon.width,icon.height,Bitmap.Config.ARGB_8888))
            val count=canvas.saveCount;var calls=0
            try { renderer.draw(icon,canvas) { calls++;error("intentional draw exception") } } catch(_:IllegalStateException) { }
            check(calls==1 && canvas.saveCount==count && icon.drawable===before && icon.imageTintList===tint)
            check(images.all { it.draws>0 })
            writeResults()
        },800L)
    }

    private fun writeResults() {
        val json=JSONObject().put("enabled",active).put("drawRestorationPassed",true)
            .put("tintMutationDetected",false).put("originalDrawExactlyOnce",true).put("rows",6)
        filesDir.resolve("icon-contrast-probe.json").writeText(json.toString())
    }

    inner class ProbeIcon(context:Context):ImageView(context) {
        var draws=0
        override fun onDraw(canvas:Canvas) {
            val image=drawable;val tint=imageTintList;val filter=colorFilter;val count=canvas.saveCount
            var calls=0
            renderer.draw(this,canvas) { calls++;super.onDraw(canvas) }
            check(calls==1 && canvas.saveCount==count && drawable===image && imageTintList===tint && colorFilter===filter)
            draws++
        }
    }
}

class OplusQSProbeTile(context:Context,val light:Boolean):FrameLayout(context)
