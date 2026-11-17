package com.coloros16.liquidglass.icons

import android.graphics.*
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Imports data only. No entry is extracted to a caller-controlled filesystem path. */
object IconImport {
    const val MAX_IMAGE=8*1024*1024
    const val MAX_TOTAL=32*1024*1024
    fun packageForEntry(name:String):String {
        require(name.length<=200 && '/' !in name && '\\' !in name) {"压缩包内只允许顶层图片"}
        val ext=name.substringAfterLast('.').lowercase()
        require(ext in setOf("png","jpg","jpeg","webp")) {"不支持的图片：$name"}
        return name.substringBeforeLast('.').also {require(IconLibrary.validPackage(it)) {"图片名称不是有效包名：$name"}}
    }
    fun read(input:InputStream,limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {val count=input.read(buffer);if(count<0)break
            require(output.size()+count<=limit) {"图片或压缩包超过大小限制"};output.write(buffer,0,count)}
        return output.toByteArray()
    }
    fun normalize(bytes:ByteArray):ByteArray {
        require(bytes.isNotEmpty() && bytes.size<=MAX_IMAGE)
        // ImageDecoder applies EXIF orientation before the centered square crop.
        val source=ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        val image=ImageDecoder.decodeBitmap(source) {decoder,info,_ ->
            val w=info.size.width;val h=info.size.height
            require(w in 1..8192 && h in 1..8192 && w.toLong()*h<=32_000_000L) {"图片尺寸过大"}
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val ratio=minOf(1f,512f/maxOf(w,h));decoder.setTargetSize(maxOf(1,(w*ratio).toInt()),maxOf(1,(h*ratio).toInt()))
        }
        val result=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888)
        try {
            val size=minOf(image.width,image.height);val x=(image.width-size)/2;val y=(image.height-size)/2
            Canvas(result).drawBitmap(image,Rect(x,y,x+size,y+size),Rect(0,0,240,240),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return java.io.ByteArrayOutputStream().use {result.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()}
        } finally {image.recycle();result.recycle()}
    }
    fun zip(input:InputStream):Map<String,ByteArray> {
        val images=LinkedHashMap<String,ByteArray>();var total=0
        ZipInputStream(input).use {zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && images.size<500) {"最多导入 500 张顶层图片"}
                val pkg=packageForEntry(entry.name);require(pkg !in images) {"重复包名：$pkg"}
                val bytes=read(zip,minOf(MAX_IMAGE,MAX_TOTAL-total));total+=bytes.size
                images[pkg]=normalize(bytes);zip.closeEntry()
            }
        }
        require(images.isNotEmpty()) {"压缩包中没有图标图片"}
        return images
    }
}
