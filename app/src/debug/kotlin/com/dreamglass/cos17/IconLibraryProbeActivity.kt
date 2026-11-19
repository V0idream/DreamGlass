package com.dreamglass.cos17

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.widget.TextView
import com.coloros16.liquidglass.icons.*
import com.coloros16.liquidglass.hook.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconLibraryProbeActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);val label=TextView(this).apply {text="Icon import and library verification…"};setContentView(label)
        Thread {
            val result=runCatching {probe()}.getOrElse {JSONObject().put("passed",false).put("error",it.stackTraceToString())}
            File(filesDir,"icon-library-probe.json").writeText(result.toString(2))
            runOnUiThread {label.text=result.toString(2)}
        }.start()
    }
    private fun probe():JSONObject {
        val original=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
        Canvas(original).apply {drawColor(Color.RED);drawRect(100f,0f,300f,200f,Paint().apply {color=Color.GREEN})}
        val source=ByteArrayOutputStream().also {original.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val normalized=IconImport.normalize(source);val decoded=BitmapFactory.decodeByteArray(normalized,0,normalized.size)
        check(decoded.width==240 && decoded.height==240 && decoded.getPixel(120,120)==Color.GREEN)
        check(decoded.getPixel(1,120)==Color.GREEN) {"Square crop not centered"}
        fun archive(entries:List<Pair<String,ByteArray>>)=ByteArrayOutputStream().also {out ->ZipOutputStream(out).use {zip ->
            entries.forEach {(name,data)->zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}
        }}.toByteArray()
        val imported=IconImport.zip(archive(listOf("com.example.one.png" to source,"com.example.two.webp" to source)).inputStream())
        check(imported.size==2)
        check(runCatching {IconImport.zip(archive(listOf("../com.test.app.png" to source)).inputStream())}.isFailure)
        check(runCatching {IconImport.zip(archive(listOf("com.test.app.png" to source,"com.test.app.jpg" to source)).inputStream())}.isFailure)
        check(runCatching {IconImport.zip(archive(listOf("com.test.app.png" to byteArrayOf(1,2))).inputStream())}.isFailure)
        val pkg="com.dreamglass.probe.fake"
        val before=IconLibrary.local(this).map {it.pkg}.toSet()
        check(pkg !in before)
        fun awaitIcon(name: String): IconGlassMasks.Prepared {
            val deadline = android.os.SystemClock.uptimeMillis() + 7_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                IconGlassLibraryRuntime.create(this, name, 128)?.let { return it }
                Thread.sleep(45)
            }
            error("Local provider did not restore $name")
        }
        try {
            IconLibrary.saveUploads(this,mapOf(pkg to normalized))
            check(IconLibrary.local(this).first {it.pkg==pkg}.source=="upload")
            val bundle=contentResolver.call(android.net.Uri.parse("content://com.dreamglass.cos17.icons"),"catalog",null,null)!!
            check(IconLibrary.parse(bundle.getString("catalog")!!).any {it.pkg==pkg})
            val file=contentResolver.openInputStream(android.net.Uri.parse("content://com.dreamglass.cos17.icons/$pkg"))!!.use {it.readBytes()}
            check(file.contentEquals(normalized))
            // A fresh renderer process must restore imports even with no LSPosed revision hint.
            IconGlassLibraryRuntime.observe(this) {}
            IconGlassLibraryRuntime.setEnabled(true)
            check(awaitIcon(pkg).passthrough)
        } finally {
            IconLibrary.removeUpload(this,pkg)
            contentResolver.notifyChange(IconLibrary.URI, null)
        }
        check(IconLibrary.local(this).map {it.pkg}.toSet()==before)
        val transparent=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        val clock=checkNotNull(IconGlassDynamicIcons.create("com.coloros.alarmclock",128))
        val result=JSONObject().put("passed",true).put("centeredCrop",true).put("zipImport",true)
            .put("rejectsTraversalDuplicateAndCorruptImages",true).put("providerPrivateStorageRoundtrip",true)
            .put("restoredWithoutFrameworkRevision",true).put("existingIconsPreserved",true).put("dynamicClockGenerated",clock.foreground.getPixel(64,64)!=transparent.getPixel(64,64))
        if(intent.getBooleanExtra("download",false)) {
            val catalog=IconLibrary.fetchCatalog();val matches=catalog.filter {it.pkg in IconLibrary.installed(this)}
            check(matches.isNotEmpty());val revision=IconLibrary.download(this,matches) {_,_->}
            IconLibrary.publish(this,revision)
            for(e in matches)awaitIcon(e.pkg)
            result.put("downloadedCount",matches.size).put("catalogCount",catalog.size).put("allDownloadedArtworkDecoded",true)
        }
        return result
    }
}
