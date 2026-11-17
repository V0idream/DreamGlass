package com.coloros16.liquidglass.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.coloros16.liquidglass.icons.IconLibrary

/** Worker-only reads from the module provider; no host network, frame-time IO or polling. */
internal object IconGlassLibraryRuntime {
    @Volatile var revision="";private set
    private var loadedRevision:String?=null
    private var entries=emptyMap<String,IconLibrary.Entry>()
    private val images=HashMap<String,Bitmap?>()
    @Synchronized fun configure(value:String):Boolean {
        if(value==revision)return false
        revision=value;loadedRevision=null;images.clear();entries=emptyMap();return true
    }
    @Synchronized fun create(context:Context,pkg:String?,size:Int):IconGlassMasks.Prepared? {
        if(revision.isEmpty() || pkg==null)return null
        if(loadedRevision!=revision) {
            entries=runCatching {IconLibrary.parse(context.contentResolver.call(
                Uri.parse("content://com.dreamglass.cos17.icons"),"catalog",null,null)?.getString("catalog") ?: "")
                .associateBy {it.pkg}}.getOrDefault(emptyMap())
            loadedRevision=revision
        }
        val entry=entries[pkg] ?: return null
        val image=if(images.containsKey(pkg))images[pkg] else runCatching {
            val bytes=context.contentResolver.openInputStream(Uri.parse("content://com.dreamglass.cos17.icons/$pkg"))!!.use {
                val buffer=ByteArray(262145);var used=0
                while(used<buffer.size) {val n=it.read(buffer,used,buffer.size-used);if(n<0)break;used+=n}
                require(used<=262144);buffer.copyOf(used)
            }
            require(IconLibrary.digest(bytes)==entry.sha256)
            val o=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,o)
            require(o.outWidth in 32..512 && o.outHeight in 32..512 && o.outMimeType=="image/png")
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        }.getOrNull().also {images[pkg]=it}
        image ?: return null
        val target=if(entry.source=="upload")image.width else if(entry.render=="engraved")192 else size
        val scaled=Bitmap.createScaledBitmap(image,target,target,true)
        val copy=scaled.copy(Bitmap.Config.ARGB_8888,true) ?: return null
        if(scaled!==image)scaled.recycle()
        if(entry.source!="upload")IconGlassDynamicIcons.paint(copy,pkg,entry.dynamic)
        return IconGlassMasks.Prepared(copy,if(entry.source=="upload")"user-image" else "downloaded",
            relief=entry.source!="upload",passthrough=entry.source=="upload",detailed=entry.source!="upload" && entry.render=="engraved")
    }
}
