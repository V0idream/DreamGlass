package com.coloros16.liquidglass.icons

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Data-only artwork. Downloads happen only after an explicit action in the module UI. */
object IconLibrary {
    const val HOME="https://github.com/V0idream/DreamGlass-Icons"
    const val ROOT="https://raw.githubusercontent.com/V0idream/DreamGlass-Icons/main/"
    const val REVISION="custom_icon_library_revision"
    private const val INDEX_LIMIT=262144
    private const val ENTRIES_PER_INDEX=500
    val URI: android.net.Uri by lazy { android.net.Uri.parse("content://${com.coloros16.liquidglass.BuildConfig.APPLICATION_ID}.icons") }
    fun publish(context:Context,revision:String) {
        // The catalog files are already committed. The launcher can refresh even when LSPosed's binder has not arrived.
        runCatching { context.contentResolver.notifyChange(URI, null) }
        com.coloros16.liquidglass.config.Prefs.write(context).putString(REVISION,revision).commit()
        com.coloros16.liquidglass.App.serviceRef?.let {syncRevision(context,it)}
    }
    fun syncRevision(context:Context,service:io.github.libxposed.service.XposedService) {
        val value=com.coloros16.liquidglass.config.Prefs.read(context).getString(REVISION,null) ?: return
        com.coloros16.liquidglass.config.Prefs.remote(service).edit().putString(REVISION,value).apply()
    }
    data class Entry(val pkg:String,val label:String,val path:String,val sha256:String,val dynamic:String="",val source:String="library",val render:String="") {
        fun json()=JSONObject().put("package",pkg).put("label",label).put("path",path)
            .put("sha256",sha256).put("dynamic",dynamic).put("source",source).put("render",render)
    }
    fun validPackage(pkg:String)=pkg.length<=180 && pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
    fun validPath(path:String)=path.matches(Regex("icons/[a-z0-9-]{1,80}\\.png"))
    fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun parse(raw:String):List<Entry> = parse(raw, INDEX_LIMIT, ENTRIES_PER_INDEX)

    private fun parse(raw:String, limit:Int, maxEntries:Int):List<Entry> {
        require(raw.length<=limit)
        val json=JSONObject(raw);require(json.getInt("schema")==1)
        val array=json.getJSONArray("icons");require(array.length()<=maxEntries)
        val entries=(0 until array.length()).map {i ->
            val e=array.getJSONObject(i)
            Entry(e.getString("package"),e.getString("label").take(80),e.getString("path"),
                e.getString("sha256"),e.optString("dynamic"),e.optString("source","library"),e.optString("render")).also {
                require(validPackage(it.pkg) && validPath(it.path) && it.sha256.matches(Regex("[0-9a-f]{64}")))
                require(it.dynamic in setOf("","clock","calendar"))
                require(it.source in setOf("library","upload"))
                require(it.render in setOf("","engraved"))
            }
        }
        require(entries.map {it.pkg}.distinct().size==entries.size)
        return entries
    }
    internal fun runtimeCatalog(entries:List<Entry>):Bundle = Bundle().apply {
        val raw=encode(entries)
        val bytes=raw.toByteArray(Charsets.UTF_8)
        if (bytes.size<=INDEX_LIMIT && entries.size<=ENTRIES_PER_INDEX) {
            putString("catalog",raw)
        } else {
            require(bytes.size<=INDEX_LIMIT*2)
            val output=java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(output).use {it.write(bytes)}
            putByteArray("catalog_gzip",output.toByteArray())
        }
    }
    internal fun parseRuntimeCatalog(bundle:Bundle):List<Entry> {
        val compressed=bundle.getByteArray("catalog_gzip") ?: return parse(requireNotNull(bundle.getString("catalog")))
        require(compressed.size<=INDEX_LIMIT*2)
        val raw=java.util.zip.GZIPInputStream(compressed.inputStream()).use {
            IconImport.read(it,INDEX_LIMIT*2).toString(Charsets.UTF_8)
        }
        return parse(raw,INDEX_LIMIT*2,ENTRIES_PER_INDEX*2)
    }
    fun encode(entries:List<Entry>)=JSONObject().put("schema",1)
        .put("icons",JSONArray().apply {entries.forEach {put(it.json())}}).toString()
    fun folder(context:Context)=File(context.filesDir,"custom-icons").apply {mkdirs()}
    fun installed(context:Context):Set<String> {
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent,0).map {it.activityInfo.packageName}.toSet()
    }
    private fun index(context:Context,name:String)=runCatching {
        val file=File(folder(context),name)
        if(!file.isFile || file.length()>262144)emptyList() else parse(file.readText())
    }.getOrDefault(emptyList())
    fun local(context:Context)=(index(context,"installed.json")+index(context,"uploads.json")).associateBy {it.pkg}.values.toList()

    /** Do not turn a storage/parse error into an authoritative empty catalog. */
    internal fun forProvider(context: Context): List<Entry> {
        check(context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true) {
            "Icon library is unavailable until the user unlocks"
        }
        val root = File(context.filesDir, "custom-icons").toPath()
        fun readIndex(name: String): List<Entry> {
            val path = root.resolve(name)
            val attrs = try {
                java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
            } catch (_: java.nio.file.NoSuchFileException) {
                return emptyList()
            }
            require(attrs.isRegularFile && attrs.size() in 1..262144)
            val bytes = java.nio.file.Files.newInputStream(path).use { it.readNBytes(262145) }
            require(bytes.size <= 262144)
            return parse(bytes.toString(Charsets.UTF_8))
        }
        return (readIndex("installed.json") + readIndex("uploads.json")).associateBy { it.pkg }.values.toList()
    }
    fun fetchCatalog():List<Entry> =parse(read("index.json",262144).toString(Charsets.UTF_8))
    fun file(context:Context,e:Entry)=File(folder(context),e.sha256+".png")
    fun downloaded(context:Context,e:Entry)=file(context,e).let {it.isFile && it.length() in 1..262144}

    /** Keep the previous applied index intact on any failed request. Orphan hash files are safe. */
    fun download(context:Context,entries:List<Entry>,progress:(Int,Int)->Unit):String {
        require(entries.isNotEmpty())
        val root=folder(context)
        for((i,e) in entries.distinctBy {it.sha256}.withIndex()) {
            val target=file(context,e)
            val cached=if(target.isFile && target.length() in 1..262144)target.readBytes() else null
            val validCache=cached!=null && digest(cached)==e.sha256
            val bytes=if(validCache)cached!! else read(e.path,262144)
            require(digest(bytes)==e.sha256) {"图标校验失败：${e.label}"}
            val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outMimeType=="image/png" && options.outWidth in 32..512 && options.outHeight in 32..512)
            if(!validCache) {
                val temp=File(root,e.sha256+".tmp");temp.writeBytes(bytes)
                java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            progress(i+1,entries.distinctBy {it.sha256}.size)
        }
        require(entries.all {it.source=="library"})
        val combined=(index(context,"installed.json")+entries).associateBy {it.pkg}.values.sortedBy {it.pkg}
        writeIndex(context,"installed.json",combined)
        return revision(context)
    }
    fun revision(context:Context)=digest(encode(local(context).sortedBy {it.pkg}).toByteArray())
    @Synchronized fun saveUploads(context:Context,images:Map<String,ByteArray>):String {
        require(images.isNotEmpty() && images.size<=500)
        val entries=images.map {(pkg,bytes) ->
            require(validPackage(pkg));val sha=digest(bytes)
            val entry=Entry(pkg,runCatching {context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString()}.getOrDefault(pkg),
                "icons/user.png",sha,source="upload")
            val target=file(context,entry)
            if(!target.exists()) {val temp=File(folder(context),sha+".tmp");temp.writeBytes(bytes);check(temp.renameTo(target))}
            entry
        }
        writeIndex(context,"uploads.json",(index(context,"uploads.json")+entries).associateBy {it.pkg}.values.toList())
        return revision(context)
    }
    @Synchronized fun removeUpload(context:Context,pkg:String):String {
        writeIndex(context,"uploads.json",index(context,"uploads.json").filterNot {it.pkg==pkg})
        return revision(context)
    }
    private fun writeIndex(context:Context,name:String,entries:List<Entry>) {
        require(entries.size<=ENTRIES_PER_INDEX) {"图标数量超过 ${ENTRIES_PER_INDEX} 个"}
        val target=File(folder(context),name);val temp=File(folder(context),name+".tmp")
        val bytes=encode(entries).toByteArray(Charsets.UTF_8)
        require(bytes.size<=INDEX_LIMIT) {"图标目录超过大小限制"}
        temp.writeBytes(bytes);java.nio.file.Files.move(temp.toPath(),target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun read(path:String,limit:Int):ByteArray {
        require(path=="index.json" || validPath(path))
        return try {request(ROOT+path,limit,false)} catch(first:java.io.IOException) {
            try {request("https://api.github.com/repos/V0idream/DreamGlass-Icons/contents/"+path,limit,true)}
            catch(second:Exception) {second.addSuppressed(first);throw second}
        }
    }
    private fun request(url:String,limit:Int,api:Boolean):ByteArray {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=12000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
        connection.setRequestProperty("User-Agent","DreamGlass-IconLibrary")
        connection.setRequestProperty("Accept",if(api)"application/vnd.github.raw+json" else "application/octet-stream")
        try {
            if(connection.responseCode!=200)throw java.io.IOException("GitHub 返回 ${connection.responseCode}")
            require(connection.contentLengthLong<=limit)
            return connection.inputStream.use {input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val count=input.read(buffer);if(count<0)break
                    require(output.size()+count<=limit);output.write(buffer,0,count)}
                output.toByteArray()
            }
        } finally {connection.disconnect()}
    }
}
