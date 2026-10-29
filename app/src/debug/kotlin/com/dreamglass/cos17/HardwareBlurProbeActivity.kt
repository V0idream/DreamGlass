package com.dreamglass.cos17
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.graphics.*
import android.util.Log
import android.widget.*
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject

/** Isolated emulator fixture, never included in release. CPU readbacks below are assertions only. */
class HardwareBlurProbeActivity:Activity() {
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  val root=LinearLayout(this).apply { orientation=1;setPadding(24,56,24,24);setBackgroundColor(0xff101923.toInt()) }
  val label=TextView(this).apply { text="Hardware blur / CPU F16 comparison…";setTextColor(Color.WHITE);textSize=16f }
  root.addView(label);setContentView(root)
  val generic=FrameLayout(this);val owner=com.oplus.systemui.volume.view.ProbeVolumeView(this)
  root.addView(generic);root.addView(owner)
  check(VolumeMaskGuard.noteWindow(owner))
  check(!VolumeMaskGuard.noteWindow(owner))
  check(VolumeMaskGuard.isVolumeHost(generic))
  check(!VolumeMaskGuard.isVolumeHost(FrameLayout(this)))
  Thread({
   val gpu=HardwareBackdropBlur()
   try {
    val colors=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(0x80402010.toInt()) }
    val first=gpu.render(colors,128,128,8f) { false }
    check(first.config==Bitmap.Config.HARDWARE)
    val before=first.copy(Bitmap.Config.ARGB_8888,false)!!
    val c=before.getPixel(64,64)
    check(kotlin.math.abs(Color.alpha(c)-128)<=2)
    check(kotlin.math.abs(Color.red(c)-64)<=3)
    val blue=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    gpu.render(blue,128,128,8f) { false }
    val retained=first.copy(Bitmap.Config.ARGB_8888,false)!!
    check(retained.getPixel(64,64)==c) // A later render must never modify a published result.
    val source=Bitmap.createBitmap(384,768,Bitmap.Config.ARGB_8888)
    val input=IntArray(source.width*source.height) { i ->
     val x=i%source.width;val y=i/source.width
     Color.rgb((x*255/source.width),(y*255/source.height),if((x/4+y/4)%2==0)220 else 24)
    }
    source.setPixels(input,0,source.width,0,0,source.width,source.height)
    var cpuWall=0L;var cpuThread=0L;var cpuProcess=0L;var gpuWall=0L;var gpuThread=0L;var gpuProcess=0L
    repeat(3) {
     var t=SystemClock.elapsedRealtimeNanos();var cput=Debug.threadCpuTimeNanos();var proc=android.os.Process.getElapsedCpuTime()
     val fp=FloatBackdropBitmap.create(ButtonBackdropBlur.applyPremultiplied(input,source.width,source.height,8f),source.width,source.height)
     cpuWall+=SystemClock.elapsedRealtimeNanos()-t;cpuThread+=Debug.threadCpuTimeNanos()-cput;cpuProcess+=android.os.Process.getElapsedCpuTime()-proc
     fp.recycle()
     t=SystemClock.elapsedRealtimeNanos();cput=Debug.threadCpuTimeNanos();proc=android.os.Process.getElapsedCpuTime()
     val hw=gpu.render(source,source.width,source.height,8f) { false }
     gpuWall+=SystemClock.elapsedRealtimeNanos()-t;gpuThread+=Debug.threadCpuTimeNanos()-cput;gpuProcess+=android.os.Process.getElapsedCpuTime()-proc
     check(hw.config==Bitmap.Config.HARDWARE)
    }
    val raw=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
    for(y in 0 until 256)for(x in 0 until 256)raw.setPixel(x,y,if((x/8+y/8)%2==0)Color.WHITE else Color.BLACK)
    val smooth=gpu.render(raw,256,256,24f) { false }.copy(Bitmap.Config.ARGB_8888,false)!!
    var low=255;var high=0
    for(y in 64..192)for(x in 64..192) { val red=Color.red(smooth.getPixel(x,y));low=minOf(low,red);high=maxOf(high,red) }
    check(high-low<=3)
    val mode=java.util.concurrent.atomic.AtomicInteger(0)
    val worker=ButtonBackdropWorker({}, { throw AssertionError(it) }, { mode.get() })
    val pending=ButtonBackdropBinding.resolve(worker,blue,RectF(0f,0f,128f,128f),RectF(8f,12f,64f,96f),true,
      com.coloros16.liquidglass.liquidglass.LiquidGlassShader.Params(viewportWidth=56f,viewportHeight=84f,blurRadius=8f))
    check(pending.pending)
    fun awaitConfig(config:Bitmap.Config):Bitmap {
      val until=SystemClock.uptimeMillis()+5000L
      while(SystemClock.uptimeMillis()<until) {
        val bound=ButtonBackdropBinding.resolve(worker,blue,RectF(0f,0f,128f,128f),RectF(8f,12f,64f,96f),true,
          com.coloros16.liquidglass.liquidglass.LiquidGlassShader.Params(viewportWidth=56f,viewportHeight=84f,blurRadius=8f))
        if(!bound.pending && bound.bitmap.config==config) {
          check(bound.params.sourcePreblurred && bound.sourceRect==RectF(8f,12f,64f,96f));return bound.bitmap
        }
        Thread.sleep(10)
      }
      error("Production backend binding timeout: $config")
    }
    val hardwareImage=awaitConfig(Bitmap.Config.HARDWARE)
    mode.set(1);awaitConfig(Bitmap.Config.RGBA_F16)
    check(!hardwareImage.isRecycled);worker.clear()
    val result=JSONObject().put("cpuWallMs",cpuWall/3e6).put("hardwareWallMs",gpuWall/3e6)
      .put("cpuWorkerCpuMs",cpuThread/3e6).put("hardwareWorkerCpuMs",gpuThread/3e6)
      .put("cpuProcessCpuMs",cpuProcess/3.0).put("hardwareProcessCpuMs",gpuProcess/3.0)
      .put("pixels",source.width*source.height).put("samples",3).put("hardwareConfig",true)
      .put("translucencyPassed",true).put("publishedBufferRetained",true).put("checkerResidual",high-low)
      .put("genericVolumeWindowOwnership",true).put("productionBackendToggleBinding",true).put("physicalPowerMeasured",false)
    Log.i("DreamGlassHardwareProbe","PASS "+result)
    runOnUiThread { label.text="PASS\n"+result.toString(2) }
   } catch(error:Throwable) { Log.e("DreamGlassHardwareProbe","FAIL",error);runOnUiThread { label.text="FAIL: $error" } }
   finally { gpu.close() }
  },"DreamGlass-hardware-probe").start()
 }
}
