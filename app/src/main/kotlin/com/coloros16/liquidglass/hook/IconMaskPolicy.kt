/* DreamGlass contributors, 2026. GPL-3.0. Initial icon analysis only; no CPU blur. */
package com.coloros16.liquidglass.hook

/** Conservative, platform-independent foreground extraction. Never mutates caller pixels. */
internal object IconMaskPolicy {
    data class Result(val pixels:IntArray, val separated:Boolean, val kind:String)

    fun hasUsefulAlpha(pixels:IntArray,width:Int,height:Int):Boolean {
        if(width<2 || height<2 || pixels.size!=width*height)return false
        val visible=pixels.count {it ushr 24>24}
        val coverage=visible.toFloat()/pixels.size
        if(coverage !in .025f.. .78f)return false
        var edge=0;var opaque=0
        for(y in 0 until height)for(x in 0 until width) {
            if(x==0 || y==0 || x==width-1 || y==height-1) {
                edge++;if(pixels[y*width+x] ushr 24>24)opaque++
            }
        }
        if(opaque.toFloat()/edge>=.28f)return false
        // A rounded opaque plate has transparent outer corners too. Do not confuse
        // that background with a clean glyph just because its coverage is below 78%.
        val inset=(minOf(width,height)*.12f).toInt().coerceAtLeast(1)
        var band=0;var solid=0
        for(y in inset until height-inset)for(x in inset until width-inset) {
            if(x==inset || y==inset || x==width-inset-1 || y==height-inset-1) {
                band++;if(pixels[y*width+x] ushr 24>200)solid++
            }
        }
        return band>0 && solid.toFloat()/band<.60f
    }

    fun extract(pixels:IntArray,width:Int,height:Int):Result {
        require(width>1 && height>1 && pixels.size==width*height)
        if(hasUsefulAlpha(pixels,width,height))return Result(pixels.copyOf(),true,"legacy-alpha")
        // Find the first visible pixel on each side, including icons with transparent corners.
        val edge=ArrayList<Int>(2*(width+height))
        for(y in 0 until height) {
            (0 until width).firstOrNull {pixels[y*width+it] ushr 24>200}?.let {edge.add(pixels[y*width+it])}
            (width-1 downTo 0).firstOrNull {pixels[y*width+it] ushr 24>200}?.let {edge.add(pixels[y*width+it])}
        }
        for(x in 0 until width) {
            (0 until height).firstOrNull {pixels[it*width+x] ushr 24>200}?.let {edge.add(pixels[it*width+x])}
            (height-1 downTo 0).firstOrNull {pixels[it*width+x] ushr 24>200}?.let {edge.add(pixels[it*width+x])}
        }
        fun fallback()=Result(pixels.copyOf(),false,"flat-preserved")
        if(edge.size<(width+height)/2)return fallback()
        fun bucket(c:Int)=((c shr 19 and 31) shl 10) or ((c shr 11 and 31) shl 5) or (c shr 3 and 31)
        val counts=HashMap<Int,Int>()
        for(c in edge) {val b=bucket(c);counts[b]=(counts[b]?:0)+1}
        val winner=counts.maxByOrNull {it.value}?.key ?: return fallback()
        val candidate=edge.first {bucket(it)==winner}
        fun distance(a:Int,b:Int):Int = maxOf(kotlin.math.abs((a shr 16 and 255)-(b shr 16 and 255)),
            kotlin.math.abs((a shr 8 and 255)-(b shr 8 and 255)),kotlin.math.abs((a and 255)-(b and 255)))
        val matching=edge.filter {distance(it,candidate)<=26}
        if(matching.size.toFloat()/edge.size<.72f)return fallback()
        var r=0L;var g=0L;var b=0L
        for(c in matching) {r+=c shr 16 and 255;g+=c shr 8 and 255;b+=c and 255}
        val color=(0xff shl 24) or ((r/matching.size).toInt() shl 16) or ((g/matching.size).toInt() shl 8) or (b/matching.size).toInt()
        val removed=BooleanArray(pixels.size);val queue=IntArray(pixels.size)
        var start=0;var end=0
        fun add(i:Int) {
            if(!removed[i] && (pixels[i] ushr 24<25 || distance(pixels[i],color)<=28)) {
                removed[i]=true;queue[end++]=i
            }
        }
        for(x in 0 until width) {add(x);add((height-1)*width+x)}
        for(y in 0 until height) {add(y*width);add(y*width+width-1)}
        while(start<end) {
            val i=queue[start++];val x=i%width;val y=i/width
            if(x>0)add(i-1);if(x<width-1)add(i+1);if(y>0)add(i-width);if(y<height-1)add(i+width)
        }
        val output=pixels.copyOf()
        for(i in output.indices)if(removed[i])output[i]=0
        val count=output.count {it ushr 24>24}
        val fraction=count.toFloat()/output.size
        if(fraction !in .025f.. .78f || end.toFloat()/pixels.size<.12f)return fallback()
        return Result(output,true,"flat-edge-separated")
    }
}
