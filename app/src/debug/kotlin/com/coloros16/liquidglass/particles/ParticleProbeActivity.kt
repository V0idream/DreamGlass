package com.coloros16.liquidglass.particles
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.util.Log
import com.coloros16.liquidglass.hook.NotificationMotionContract
import com.coloros16.liquidglass.hook.ParticleDissolveHook
import java.io.File
import org.json.JSONObject

/** Synthetic images only; readback is restricted to this debug test, never production. */
class ParticleProbeActivity:Activity() {
 private val ui=Handler(Looper.getMainLooper())
 private lateinit var label:TextView
 private lateinit var card:View
 private fun fail(error:Throwable) {
  File(filesDir,"particle-probe").apply {mkdirs()}.resolve("report.json").writeText(JSONObject().put("passed",false).put("error",error.stackTraceToString()).toString(2))
  Log.e("ParticleProbe","FAIL",error);label.text="FAIL: $error"
 }
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  val box=FrameLayout(this).apply { setBackgroundColor(Color.rgb(12,20,28)) }
  label=TextView(this).apply { text="GPU particle regression…";setTextColor(Color.WHITE);textSize=18f;setPadding(25,30,25,0) }
  card=object:View(this) {
   val p=Paint(Paint.ANTI_ALIAS_FLAG)
   override fun onDraw(c:Canvas) {
    p.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),Color.rgb(54,185,162),Color.rgb(40,69,144),Shader.TileMode.CLAMP)
    c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),30f,30f,p);p.shader=null
    p.color=Color.WHITE;p.textSize=27f;c.drawText("DreamGlass",30f,55f,p);p.textSize=20f;c.drawText("Notification deleted",30f,95f,p)
   }
  }
  box.addView(card,FrameLayout.LayoutParams(640,260).apply { leftMargin=60;topMargin=300 })
  box.addView(label,FrameLayout.LayoutParams(-1,220));setContentView(box)
  box.post { try { renderTests() } catch(e:Throwable) { fail(e) } }
 }
 private fun rendered(shader:RuntimeShader,w:Int,h:Int):Bitmap {
  val reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
  val renderer=HardwareRenderer();val node=RenderNode("Particle shader test")
  try {
   renderer.isOpaque=false;renderer.setSurface(reader.surface);renderer.setContentRoot(node);node.setPosition(0,0,w,h)
   val c=node.beginRecording(w,h);c.drawColor(Color.TRANSPARENT,BlendMode.CLEAR);c.drawRect(0f,0f,w.toFloat(),h.toFloat(),Paint().apply { this.shader=shader });node.endRecording()
   renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
   val image=reader.acquireNextImage()!!
   try { val buffer=image.hardwareBuffer!!;try { return Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888,false)!! } finally { buffer.close() } } finally { image.close() }
  } finally { renderer.destroy();reader.close();node.discardDisplayList() }
 }
 private fun renderTests() {
  val source=Bitmap.createBitmap(360,180,Bitmap.Config.ARGB_8888)
  val c=Canvas(source);val p=Paint().apply { shader=LinearGradient(0f,0f,360f,180f,Color.CYAN,Color.BLUE,Shader.TileMode.CLAMP) };c.drawRoundRect(0f,0f,360f,180f,25f,25f,p)
  p.shader=null;p.color=Color.WHITE;p.textSize=24f;c.drawText("Notification",35f,70f,p)
  val shader=RuntimeShader(ParticleDissolveHook.Api33.SHADER)
  shader.setInputShader("uImage",BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) })
  shader.setFloatUniform("uSize",360f,180f);shader.setFloatUniform("uImageScale",1f,1f);shader.setFloatUniform("uPad",100f);shader.setFloatUniform("uCell",2f)
  val counts=ArrayList<Int>();var escaped=0
  val dir=File(filesDir,"particle-probe").apply { mkdirs() }
  for((i,t) in listOf(0f,.25f,.55f,1f).withIndex()) {
   shader.setFloatUniform("uProgress",t);val b=rendered(shader,560,380)
   var count=0
   for(y in 0 until b.height)for(x in 0 until b.width) {
    if(Color.alpha(b.getPixel(x,y))>8) { count++;if(t==.55f && (x<100 || x>=460 || y<100 || y>=280))escaped++ }
   }
   counts.add(count);File(dir,"phase-$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
   if(t==0f)check(b.getPixel(200,200)==source.getPixel(100,100))
  }
  check(counts[0]>60000 && counts[2] in 1000 until counts[0] && counts[3]==0 && escaped>0) { "Coverage $counts / escaped=$escaped" }
  shader.setFloatUniform("uVertical",1f);shader.setFloatUniform("uDirection",-1f);shader.setFloatUniform("uProgress",.55f)
  val up=rendered(shader,560,380);var above=0
  for(y in 0 until 100)for(x in 100 until 460)if(Color.alpha(up.getPixel(x,y))>8)above++
  check(above>0) {"Upward heads-up dissolve did not escape above the card"}
  File(dir,"heads-up-upward.png").outputStream().use {up.compress(Bitmap.CompressFormat.PNG,100,it)}
  shader.setFloatUniform("uProgress",1f)
  val end=rendered(shader,560,380)
  for(y in 0 until 380 step 5)for(x in 0 until 560 step 5)check(Color.alpha(end.getPixel(x,y))==0)
  shader.setFloatUniform("uProgress",0f)
  val half=Bitmap.createScaledBitmap(source,180,90,true)
  shader.setInputShader("uImage",BitmapShader(half,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) })
  shader.setFloatUniform("uImageScale",.5f,.5f)
  val scaled=rendered(shader,560,380)
  val old=source.getPixel(200,100);val actual=scaled.getPixel(300,200)
  check(kotlin.math.abs(Color.green(actual)-Color.green(old))<5 && kotlin.math.abs(Color.blue(actual)-Color.blue(old))<5)
  lifecycle(JSONObject().put("headsUpUpwardParticles",above).put("scaledSnapshotCoordinates",true).put("coverage",counts).put("escapedPixels",escaped).put("shaderCompiled",true).put("zeroProgressIdentity",true).put("endTransparent",true))
 }
 private fun lifecycle(report:JSONObject) {
  val api=ParticleDissolveHook.Api33
  val type=api.javaClass
  fun method(name:String)=type.getDeclaredMethod(name,View::class.java).apply {isAccessible=true}
  val prepare=method("prepare");val cancel=method("cancel")
  api.onGesture(card,NotificationMotionContract.Event.PREPARE)
  api.onGesture(card,NotificationMotionContract.Event.REQUEST)
  val effects=type.getDeclaredField("effects").apply {isAccessible=true}.get(api) as Map<*,*>
  val captures=type.getDeclaredField("captures").apply {isAccessible=true}.get(api) as Map<*,*>
  check(effects.isEmpty())
  api.onGesture(card,NotificationMotionContract.Event.CANCEL);check(captures.isEmpty())
  val cap=prepare.invoke(api,card)!!
  val field=cap.javaClass.getDeclaredField("bitmap").apply {isAccessible=true}
  var tries=0
  fun waitReady() {
   try {
    if(field.get(cap)==null) {check(tries++<60);ui.postDelayed({waitReady()},50);return}
    val captured=field.get(cap) as Bitmap
    check(captured.config==Bitmap.Config.HARDWARE)
    val copy=captured.copy(Bitmap.Config.ARGB_8888,false)!!
    check(Color.alpha(copy.getPixel(copy.width/2,copy.height/2))>200);copy.recycle()
    cancel.invoke(api,card);check(captures.isEmpty());api.stopAll();check(effects.isEmpty())
    // libxposed is compileOnly and absent in the AOSP app process. Exercise the
    // production Dust overlay directly; do not add a debug bypass to its member gate.
    var completed=0
    val root=card.rootView
    val dust=ParticleDissolveHook.Api33.Dust(root,captured,60f,300f,card.width,card.height,1f,100,
      direction=-1f,vertical=true) {completed++}
    dust.start()
    ui.postDelayed({try {
     check(completed==0)
     ui.postDelayed({try {
      check(completed==1)
      var stopped=0
      val aborted=ParticleDissolveHook.Api33.Dust(root,captured,0f,0f,card.width,card.height,1f,100) {stopped++}
      aborted.start();aborted.stop();aborted.stop();check(stopped==1)
      var detached=0
      val removal=ParticleDissolveHook.Api33.Dust(card,captured,0f,0f,card.width,card.height,1f,100) {detached++}
      removal.start();(card.parent as android.view.ViewGroup).removeView(card);check(detached==1)
      report.put("passed",true).put("hardwareSnapshot",true).put("attemptDoesNotEmitParticles",true)
       .put("cancelBeforeCommitReleasesCapture",true).put("completionReleasesOverlay",true)
       .put("explicitStopIsIdempotent",true).put("detachReleasesOverlay",true)
       .put("physicalDeviceTested",false).put("vendorHeadsUpHookExecuted",false)
       .put("frameworkCommitLifecycleTested",false)
      File(filesDir,"particle-probe/report.json").writeText(report.toString(2))
      Log.i("ParticleProbe","PASS "+report);label.text="PASS\n"+report.toString(2)
     }catch(e:Throwable) {fail(e)}},1300)
    }catch(e:Throwable) {fail(e)}},100)
   }catch(e:Throwable) {fail(e)}
  }
  waitReady()
 }
}
