package com.dreamglass.cos17

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import android.widget.TextView
import com.coloros16.liquidglass.hook.BoundedGlassDrawable
import java.io.File
import org.json.JSONObject

class MenuBackgroundProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Menu GPU geometry / fallback regression…";setPadding(24,60,24,0)}
        setContentView(label)
        label.post {try {label.text=probe().toString(2)} catch(e:Throwable) {label.text="FAIL $e";android.util.Log.e("MenuBackgroundProbe","FAIL",e)}}
    }
    private fun render(drawable:BoundedGlassDrawable):Bitmap {
        val reader=ImageReader.newInstance(300,200,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val renderer=HardwareRenderer();val node=RenderNode("Menu background geometry")
        try {
            renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,300,200)
            val canvas=node.beginRecording(300,200);canvas.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);drawable.draw(canvas);node.endRecording()
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
        val host=View(this).apply {layout(0,0,300,200)}
        val native=object:ColorDrawable(Color.GREEN) {
            override fun getPadding(rect:Rect):Boolean {rect.set(13,17,19,23);return true}
        }.apply {bounds=Rect(7,8,9,10)}
        var enabled=true;var available=true;var fail=false;var calls=0;var painted=0
        var passed:Rect?=null
        val drawable=BoundedGlassDrawable(host,native,{enabled},{canvas,_,rect ->
            calls++;passed=Rect(rect)
            if(fail)throw IllegalStateException("synthetic missing source")
            if(available) {canvas.drawColor(Color.RED);true} else false
        },{painted++})
        val area=Rect(40,30,240,150);drawable.bounds=area
        val actual=render(drawable)
        check(passed==area && painted==1 && actual.getPixel(150,90)==Color.RED)
        check(Color.alpha(actual.getPixel(20,90))==0 && Color.alpha(actual.getPixel(150,180))==0)
        available=false;val missing=render(drawable)
        check(missing.getPixel(150,90)==Color.GREEN && native.bounds==Rect(7,8,9,10) && painted==1)
        fail=true;val fallback=render(drawable)
        check(fallback.getPixel(150,90)==Color.GREEN && calls==3 && painted==1)
        enabled=false;val disabled=render(drawable)
        check(disabled.getPixel(150,90)==Color.GREEN && calls==3)
        drawable.alpha=128;val faded=render(drawable)
        check(Color.alpha(faded.getPixel(150,90)) in 127..129)
        drawable.alpha=0;val hidden=render(drawable);check(Color.alpha(hidden.getPixel(150,90))==0 && calls==3)
        val padding=Rect();check(drawable.getPadding(padding) && padding==Rect(13,17,19,23))
        val result=JSONObject().put("offsetBoundsPassedToMaterial",true).put("canvasClippedToBounds",true)
            .put("missingSourceKeepsNativeBackground",true).put("renderFailureKeepsNativeBackground",true)
            .put("constantStateBackgroundIsolated",true).put("nativePaddingPreserved",true)
            .put("alphaAndDisabledStatesPreserved",true).put("nativeCallbackNotReplayed",true)
            .put("physicalDeviceTested",false).put("vendorHookTested",false)
        val folder=File(filesDir,"menu-background-probe").apply {mkdirs()}
        File(folder,"report.json").writeText(result.toString(2))
        for(b in listOf(actual,missing,fallback,disabled,faded,hidden))b.recycle()
        android.util.Log.i("MenuBackgroundProbe","PASS $result");return result
    }
}
