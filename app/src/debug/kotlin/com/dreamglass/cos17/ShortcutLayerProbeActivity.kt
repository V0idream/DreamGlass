package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.*
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import com.coloros16.liquidglass.hook.*
import java.io.File

/** Exercises the actual shortcut compositor against a native-style direct-drawn grey circle. */
class ShortcutLayerProbeActivity:Activity() {
    private lateinit var root:FrameLayout
    private lateinit var button:NativeButton
    private lateinit var label:TextView
    private val ui=Handler(Looper.getMainLooper())
    class NativeButton(context:android.content.Context):ImageView(context) {
        var launchScale=1f
        fun getImageScale()=launchScale
        fun getCircleRadius()=90f
        override fun onDraw(c:Canvas) {
            // Matches the vendor's alternate onDraw branch: no background Drawable.draw call.
            drawable.setBounds(0,0,180,180)
            c.drawCircle(90f,90f,90f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(54,54,54) })
            (drawable as LayerDrawable).getDrawable(2).draw(c)
        }
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);root=FrameLayout(this);setContentView(root)
        root.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(10,80,170),Color.rgb(150,30,75)))
        label=TextView(this).apply { text="Testing native shortcut layers";setTextColor(Color.WHITE) };root.addView(label)
        button=NativeButton(this)
        val icon=object:Drawable() {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE }
            override fun draw(c:Canvas) { c.drawRoundRect(57f,68f,123f,112f,8f,8f,p);c.drawCircle(90f,90f,15f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK }) }
            override fun setAlpha(a:Int) {p.alpha=a};override fun setColorFilter(f:ColorFilter?) {}
            @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
        button.setImageDrawable(LayerDrawable(arrayOf(ColorDrawable(Color.GRAY),ColorDrawable(Color.TRANSPARENT),icon)))
        root.addView(button,FrameLayout.LayoutParams(180,180).apply { leftMargin=90;topMargin=240 })
        root.postDelayed({try { runProbe() } catch(error:Throwable) { fail(error) }},300)
    }
    private fun fail(error:Throwable) {Log.e("ShortcutLayerProbe","FAIL",error);label.text="FAIL $error"}
    private fun capture():Bitmap {
        val node=RenderNode("shortcut probe");node.setPosition(0,0,root.width,root.height)
        val c=node.beginRecording(root.width,root.height);root.draw(c);node.endRecording()
        return try { HardwareBackdropBlur().use { it.render(node,root.width,root.height,root.width,root.height,.01f) { false }.copy(Bitmap.Config.ARGB_8888,false)!! } }
        finally { node.discardDisplayList() }
    }
    private fun runProbe() {
        val native=capture();val layers=button.drawable
        val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        Canvas(image).drawColor(Color.rgb(15,70,140))
        val material=KeyguardGlassMaterial.Background()
        val map=floatArrayOf(1f,0f,90f,0f,1f,240f,0f,0f,1f)
        check(KeyguardLayeredShortcut.bindLayers(button,material) { rect ->
            material.material.update(image,map,rect.width().toFloat(),rect.height().toFloat(),root.width,root.height,1f,90f)
        })
        val glass=capture()
        check(button.drawable===layers)
        check(glass.getPixel(160,320)==native.getPixel(160,320)) { "Native foreground icon changed" }
        val old=native.getPixel(180,370);val changed=glass.getPixel(180,370)
        check(kotlin.math.abs(Color.blue(changed)-Color.blue(old))>50) { "Grey native material still visible" }
        button.launchScale=1.2f;button.invalidate()
        val launch=capture();check(launch.getPixel(180,370)==old) { "Native launch transition not retained" }
        button.launchScale=1f;KeyguardLayeredShortcut.unbind(button)
        val restored=capture();check(restored.getPixel(180,370)==old)
        val dir=File(filesDir,"shortcut-probe").apply {mkdirs()}
        File(dir,"glass.png").outputStream().use {glass.compress(Bitmap.CompressFormat.PNG,100,it)}
        Log.i("ShortcutLayerProbe","PASS: actual native-layer compositor draws glass; foreground and native objects retained; launch fallback; disable restores native")
        label.text="PASS · native shortcut GPU compositor"
    }
    override fun onDestroy() {KeyguardLayeredShortcut.unbind(button);ui.removeCallbacksAndMessages(null);super.onDestroy()}
}
