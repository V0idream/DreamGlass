package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.widget.TextView
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic ABI fixtures exercise the real replacement/restore manager, not vendor Xposed. */
class IconGlassNativeCacheProbeActivity:Activity() {
    class Info(@JvmField val icon:Bitmap,@JvmField val color:Int) {
        @JvmField var flags=0
        @JvmField var badgeInfo:Info?=null
        @JvmField var mUserHandle:UserHandle?=null
        fun newIcon()=Fast(icon)
    }
    class Item(@JvmField var bitmap:Info,private val packageName:String="com.dreamglass.cache.fixture") {
        @JvmField val user:UserHandle=android.os.Process.myUserHandle()
        fun getTargetComponent()=android.content.ComponentName(packageName,"Main")
    }
    class Fast(@JvmField var mBitmap:Bitmap):Drawable() {
        @JvmField val mPaint=Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas:Canvas) {canvas.drawBitmap(mBitmap,null,bounds,mPaint)}
        override fun setAlpha(alpha:Int) {mPaint.alpha=alpha}
        override fun setColorFilter(filter:ColorFilter?) {mPaint.colorFilter=filter}
        @Deprecated("Deprecated") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    open class Preview(@JvmField var drawable:Drawable?,@JvmField var item:Item?,
        @JvmField var transX:Float,@JvmField var transY:Float,@JvmField var scale:Float)
    class Stack(val children:List<Preview>):Preview(null,null,20f,30f,.5f) {
        fun getCurrentStackedParams()=children
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Generated native icon cache / restore…";setPadding(20,60,20,20)}
        setContentView(label)
        LauncherIconGlassAssets.configure(Fast::class.java,Info::class.java,Item::class.java)
        LauncherIconGlassAssets.setActive(true)
        Thread({
            val result=try {probe()}catch(t:Throwable) {JSONObject().put("passed",false).put("error",t.stackTraceToString())}
            result.put("physicalDeviceTested",false).put("vendorLauncherHookExecuted",false)
            File(filesDir,"icon-glass-native-cache-probe").apply {mkdirs()}.resolve("report.json").writeText(result.toString(2))
            runOnUiThread {label.text=result.toString(2)}
        },"DreamGlass-native-cache-probe").start()
    }
    private fun probe():JSONObject {
        val source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        val sourceGeneration=source.generationId
        val mask=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawCircle(64f,64f,34f,Paint().apply {color=Color.WHITE})
        }
        val wall=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff327087.toInt())}
        val ready:()->Unit={}
        val params=IconGlassMaterial.Params();val crop=RectF(0f,0f,128f,128f)
        val end=System.currentTimeMillis()+10000L
        var cached:Bitmap?=null
        while(cached==null && System.currentTimeMillis()<end) {
            cached=LauncherIconGlassAssets.request(source,mask,wall,crop,params,ready)
            Thread.sleep(4)
        }
        val generated=checkNotNull(cached) {"Generated cache result missing"}
        val done=CountDownLatch(1);var failure:Throwable?=null
        runOnUiThread {
            try {
                val info=Info(source,0xff445566.toInt()).apply {flags=7;mUserHandle=android.os.Process.myUserHandle();badgeInfo=Info(mask,0)}
                val item=Item(info);val native=Fast(source);val host=View(this)
                LauncherIconGlassAssets.apply(host,native,item,source,generated)
                check(native.mBitmap===generated && item.bitmap.icon===generated)
                val generatedInfo=item.bitmap
                LauncherIconGlassAssets.apply(host,native,item,source,generated)
                check(item.bitmap===generatedInfo) {"Live redraw reallocated native metadata"}
                val copiedSource=source.copy(Bitmap.Config.ARGB_8888,false)!!
                val copiedInfo=Info(copiedSource,3);val rebound=Item(copiedInfo);val reboundDrawable=Fast(copiedSource)
                LauncherIconGlassAssets.bind(View(this),reboundDrawable,rebound)
                check(reboundDrawable.mBitmap===generated && rebound.bitmap.icon===generated) {"Page rebind exposed a copied native bitmap"}
                val unrelated=Item(Info(copiedSource,4),"com.dreamglass.unrelated.fixture")
                check(LauncherIconGlassAssets.prefabFor(unrelated)==null) {"Prefab reused across different applications"}
                check(item.bitmap.flags==7 && item.bitmap.badgeInfo===info.badgeInfo && item.bitmap.mUserHandle==info.mUserHandle)
                // A new native consumer obtains the generated image from ItemInfo itself.
                val transitionIcon=item.bitmap.newIcon().apply {setBounds(0,0,128,128)}
                val image=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
                transitionIcon.draw(Canvas(image))
                check(image.getPixel(64,64)==generated.getPixel(64,64))
                check(LauncherIconGlassAssets.request(source,mask,wall,crop,params,ready)===generated)
                val imageView=android.widget.ImageView(this).apply {scaleType=android.widget.ImageView.ScaleType.CENTER}
                val originalImage=android.graphics.drawable.BitmapDrawable(resources,source)
                imageView.setImageDrawable(originalImage)
                LauncherIconGlassAssets.apply(imageView,originalImage,null,source,generated)
                check(imageView.drawable!==originalImage)
                check(imageView.drawable.intrinsicWidth==originalImage.intrinsicWidth &&
                    imageView.drawable.intrinsicHeight==originalImage.intrinsicHeight) {"ImageView density/centering dimensions changed"}
                val firstImage=imageView.drawable
                LauncherIconGlassAssets.apply(imageView,firstImage,null,source,generated)
                check(imageView.drawable===firstImage) {"Live redraw rebound its ImageView"}
                // Non-square recommendation view: real content slot + pre-draw binder.
                val recommended=android.widget.ImageView(this).apply {
                    scaleType=android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding(8,8,8,8);setImageBitmap(source)
                    measure(View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(112,View.MeasureSpec.EXACTLY));layout(0,0,96,112)
                }
                val recommendedOriginal=recommended.drawable
                val recommendationBounds=checkNotNull(IconGlassImageGeometry.bounds(recommended))
                check(recommendationBounds==RectF(8f,16f,88f,96f)) {"Recommendation image bounds incorrect: $recommendationBounds"}
                val recommendationPrefab=IconGlassImageBinding.prepare(recommended,source,null,0,params,ready,
                    IconGlassWallpaper.Frame(wall,crop))
                check(recommendationPrefab===generated)
                check((recommended.drawable as android.graphics.drawable.BitmapDrawable).bitmap===generated)
                val recommendationOutput=Bitmap.createBitmap(96,112,Bitmap.Config.ARGB_8888)
                recommended.draw(Canvas(recommendationOutput)) // no onDraw hook in this process.
                check(recommendationOutput.getPixel(48,56)!=Color.MAGENTA)
                // Closed folder + lower-right composite: replace each actual child slot.
                val miniOriginal=android.graphics.drawable.BitmapDrawable(resources,source).apply {setBounds(0,0,128,128)}
                val leaf=Preview(Fast(source).apply {setBounds(0,0,128,128)},Item(Info(source,1)),4f,6f,.25f)
                val mini=Preview(miniOriginal,Item(Info(source,2)),8f,10f,.5f)
                val stack=Stack(listOf(leaf,mini))
                val standalone=Preview(Fast(source),Item(Info(source,3)),1f,2f,1f)
                val previewType=Preview::class.java
                val walker=IconGlassPreviewSlots(previewType.getField("drawable"),previewType.getField("item"),
                    previewType.getField("transX"),previewType.getField("transY"),previewType.getField("scale"),
                    Stack::class.java,Stack::class.java.getMethod("getCurrentStackedParams"))
                val visited=ArrayList<IconGlassPreviewSlots.Slot>()
                walker.visit(listOf(standalone,stack)) {slot ->
                    visited.add(slot)
                    LauncherIconGlassAssets.applySlot(host,slot.owner,slot.field,slot.drawable,slot.item,source,generated)
                }
                check(visited.size==3 && visited.none {it.owner===stack}) {"Composite treated as one icon"}
                check(visited[1].x==22f && visited[1].y==33f && visited[1].scale==.125f)
                check(visited[2].x==24f && visited[2].y==35f && visited[2].scale==.25f)
                check((leaf.drawable as Fast).mBitmap===generated && (mini.drawable as Fast).mBitmap===generated)
                check(mini.item!!.bitmap.icon===generated && standalone.item!!.bitmap.icon===generated)
                val miniature=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
                val miniatureCanvas=Canvas(miniature);miniatureCanvas.scale(.25f,.25f)
                mini.drawable!!.draw(miniatureCanvas)
                check(miniature.getPixel(16,16)!=Color.MAGENTA) {"Miniature native consumer still uses original"}
                val parent=android.widget.FrameLayout(this);val child=View(this)
                parent.addView(child,android.widget.FrameLayout.LayoutParams(64,64))
                parent.layout(0,0,320,320);child.layout(100,120,164,184)
                fun globalBounds():RectF {val m=Matrix();child.transformMatrixToGlobal(m);return RectF(0f,0f,64f,64f).also {m.mapRect(it)}}
                val before=globalBounds();parent.translationX=-24f;val after=globalBounds()
                check(child.left==100 && child.translationX==0f)
                check(IconGlassMotionPolicy.changed(before.left,before.top,before.right,before.bottom,
                    after.left,after.top,after.right,after.bottom)) {"Parent scroll does not update live sampling"}
                LauncherIconGlassAssets.cancelPending()
                check(LauncherIconGlassAssets.isGenerated(generated))
                check(LauncherIconGlassAssets.request(source,mask,wall,crop,params,ready)===generated)
                val otherInfo=Info(source,1);val other=Item(otherInfo);val otherDrawable=Fast(source)
                LauncherIconGlassAssets.apply(host,otherDrawable,other,source,generated)
                val external=Info(mask,2);other.bitmap=external;otherDrawable.mBitmap=mask
                LauncherIconGlassAssets.setActive(false)
                check(native.mBitmap===source && item.bitmap===info)
                check(mini.drawable===miniOriginal && (leaf.drawable as Fast).mBitmap===source)
                check(mini.item!!.bitmap.icon===source && standalone.item!!.bitmap.icon===source)
                check(reboundDrawable.mBitmap===copiedSource && rebound.bitmap===copiedInfo)
                check(imageView.drawable===originalImage)
                check(recommended.drawable===recommendedOriginal)
                check(other.bitmap===external && otherDrawable.mBitmap===mask)
                check(!generated.isRecycled && !source.isRecycled && source.generationId==sourceGeneration)
            } catch(t:Throwable) {failure=t} finally {done.countDown()}
        }
        check(done.await(10,TimeUnit.SECONDS)) {"UI cache fixture timed out"};failure?.let {throw it}
        return JSONObject().put("passed",true).put("nativeDrawableBitmapReplaced",true)
            .put("itemCacheUsedByNewNativeConsumer",true).put("profileFlagsAndBadgesPreserved",true)
            .put("screenOffKeepsGeneratedCache",true).put("disableRestoresExactOriginals",true)
            .put("nativeChangesNotOverwrittenOnRestore",true).put("borrowedBitmapsNotMutatedOrRecycled",true)
            .put("imageViewDensityAndCenteringPreserved",true).put("imageViewOriginalRestored",true)
            .put("pageRebindUsesComponentPrefabBeforeDraw",true).put("differentApplicationsNeverSharePrefab",true)
            .put("repeatedLiveDrawReusesMetadataAndDrawable",true)
            .put("closedFolderAndStackedSlotsBoundAndRestored",true)
            .put("nestedPreviewCoordinatesPreserved",true).put("parentOnlyTransformTriggersLiveSampling",true)
            .put("recommendationNonSquareSlotGeometry",true).put("recommendationNativeDrawWithoutHook",true)
    }
}
