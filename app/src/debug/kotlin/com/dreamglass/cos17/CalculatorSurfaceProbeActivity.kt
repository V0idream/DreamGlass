package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.coloros16.liquidglass.hook.CalculatorBackgroundLease
import com.coloros16.liquidglass.hook.CalculatorForegroundDraw
import java.io.File
import org.json.JSONObject

/** Synthetic GPU regression for the specific calculator bug: operators are
 * backgrounds, digits are text. Does not establish OEM backdrop coordinates.
 */
class CalculatorSurfaceProbeActivity:Activity() {
    private val controller=CalculatorForegroundDraw()
    private var active=true
    private var nativeCalls=0

    private inner class NativeButton(operator:Boolean):TextView(this@CalculatorSurfaceProbeActivity) {
        var nativeFill=true
        init {
            gravity=Gravity.CENTER;setTextColor(Color.WHITE);textSize=48f
            text=if(operator)"" else "7"
            if(operator)background=object:Drawable() {
                private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;strokeWidth=8f;strokeCap=Paint.Cap.ROUND}
                override fun draw(canvas:Canvas) {
                    val cx=bounds.exactCenterX();val cy=bounds.exactCenterY()
                    canvas.drawLine(cx-25,cy,cx+25,cy,p);canvas.drawLine(cx,cy-25,cx,cy+25,p)
                }
                override fun setAlpha(alpha:Int) {p.alpha=alpha}
                override fun setColorFilter(filter:ColorFilter?) {p.colorFilter=filter}
                @Deprecated("Deprecated") override fun getOpacity()=PixelFormat.TRANSLUCENT
            }
        }
        override fun onDraw(canvas:Canvas) {
            controller.draw(this,active && canvas.isHardwareAccelerated,{nativeFill},{nativeFill=it}) {
                nativeCalls++
                if(nativeFill)canvas.drawCircle(width/2f,height/2f,width*.48f,Paint().apply {color=Color.argb(100,30,30,30)})
                super.onDraw(canvas)
                if(!controller.suppressCoat(this)) {
                    val coat=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.LTGRAY;style=Paint.Style.STROKE;strokeWidth=3f}
                    canvas.drawCircle(width/2f,height/2f,width*.47f,coat)
                }
            }
        }
    }

    private inner class SurfaceParent:FrameLayout(this@CalculatorSurfaceProbeActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader=LinearGradient(0f,0f,160f,160f,Color.rgb(39,125,165),Color.rgb(25,67,105),Shader.TileMode.CLAMP)
        }
        init {setWillNotDraw(false);background=ColorDrawable(Color.BLACK)}
        override fun onDraw(canvas:Canvas) {canvas.drawCircle(80f,80f,78f,paint);super.onDraw(canvas)}
    }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Calculator foreground / opacity GPU regression…";setPadding(24,60,24,0)}
        setContentView(label)
        label.post {try {label.text=probe().toString(2)} catch(e:Throwable) {
            label.text="FAIL $e";android.util.Log.e("CalculatorSurfaceProbe","FAIL",e)
        }}
    }

    private fun render(root:View):Bitmap {
        val reader=ImageReader.newInstance(440,240,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Calculator foreground fixture")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,440,240)
            val canvas=node.beginRecording(440,240);canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);root.draw(canvas);node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image=reader.acquireNextImage()!!
            try {val buffer=image.hardwareBuffer!!
                try {val hardware=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!
                    try {return hardware.copy(Bitmap.Config.ARGB_8888,false)!!} finally {hardware.recycle()}
                } finally {buffer.close()}
            } finally {image.close()}
        } finally {renderer.destroy();reader.close();node.discardDisplayList()}
    }

    private fun probe():JSONObject {
        val backdrop=Color.rgb(69,22,71)
        val root=FrameLayout(this).apply {setBackgroundColor(backdrop)}
        val opParent=SurfaceParent();val digitParent=SurfaceParent()
        val op=NativeButton(true);val digit=NativeButton(false)
        val glyph=op.background
        opParent.addView(op,FrameLayout.LayoutParams(-1,-1));digitParent.addView(digit,FrameLayout.LayoutParams(-1,-1))
        root.addView(opParent,FrameLayout.LayoutParams(160,160).apply {leftMargin=40;topMargin=40})
        root.addView(digitParent,FrameLayout.LayoutParams(160,160).apply {leftMargin=240;topMargin=40})
        root.measure(View.MeasureSpec.makeMeasureSpec(440,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY))
        root.layout(0,0,440,240)
        val original=opParent.background
        check(opParent.isOpaque)
        check(CalculatorBackgroundLease.sync(opParent,true));check(CalculatorBackgroundLease.sync(digitParent,true))
        check(!opParent.isOpaque && op.background===glyph)
        check(!CalculatorBackgroundLease.sync(opParent,true)) // no repeated background setter/invalidation loop
        val good=render(root)
        check(nativeCalls==2 && op.nativeFill && digit.nativeFill)
        val white=good.getPixel(120,120)
        check(Color.red(white)>245 && Color.green(white)>245 && Color.blue(white)>245)
        check(good.getPixel(43,43)==backdrop && good.getPixel(243,43)==backdrop)
        var digitPixels=0
        for(y in 70..170)for(x in 260..380) {
            val c=good.getPixel(x,y);if(Color.red(c)>235 && Color.green(c)>235 && Color.blue(c)>235)digitPixels++
        }
        check(digitPixels>50)
        op.background=null;val lost=render(root);check(lost.getPixel(120,120)!=white);op.background=glyph
        check(CalculatorBackgroundLease.sync(opParent,false) && opParent.background===original && opParent.isOpaque)
        val replacement=ColorDrawable(Color.BLUE);opParent.background=replacement
        check(!CalculatorBackgroundLease.sync(opParent,false) && opParent.background===replacement)
        check(CalculatorBackgroundLease.sync(opParent,true));check(CalculatorBackgroundLease.sync(opParent,false))
        check(opParent.background===replacement)
        val result=JSONObject().put("operatorSvgBackgroundPreserved",true).put("nativeDigitTextPreserved",true)
            .put("parentOpacityMetadataCleared",true).put("noBlackRectangularBackingInFixture",true)
            .put("nonzeroOffsetGlyphAligned",true).put("fillFlagRestored",true)
            .put("nativeReplacementSurvivesRestore",true).put("noRepeatedBackgroundSetter",true)
            .put("syntheticGpuFixtureOnly",true).put("vendorHookTested",false).put("physicalDeviceTested",false)
        val folder=File(filesDir,"calculator-surface-probe").apply {mkdirs()}
        File(folder,"report.json").writeText(result.toString(2))
        File(folder,"foreground-preserved.png").outputStream().use {good.compress(Bitmap.CompressFormat.PNG,100,it)}
        good.recycle();lost.recycle();android.util.Log.i("CalculatorSurfaceProbe","PASS $result")
        return result
    }
}
