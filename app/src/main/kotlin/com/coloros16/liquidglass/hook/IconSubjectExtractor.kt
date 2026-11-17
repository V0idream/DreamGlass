/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import kotlin.math.*

/** One-time, small-icon analysis. Edge-colour prediction and connected saliency isolate the
 * principal elements; highly photographic inputs become tonal/edge relief, never a colour tile.
 * This does not infer semantic identities or run on each desktop frame. */
internal object IconSubjectExtractor {
    data class Result(val pixels:IntArray,val kind:String)
    fun extract(pixels:IntArray,width:Int,height:Int):Result {
        require(width>1 && height>1 && pixels.size==width*height)
        val n=pixels.size
        val r=FloatArray(n);val g=FloatArray(n);val b=FloatArray(n);val alpha=FloatArray(n)
        for(i in pixels.indices) {
            val c=pixels[i];alpha[i]=(c ushr 24)/255f
            r[i]=(c shr 16 and 255).toFloat();g[i]=(c shr 8 and 255).toFloat();b[i]=(c and 255).toFloat()
        }
        // Suppress pixel noise in the analysis only; visual backdrop blur remains GPU-only.
        fun smooth(source:FloatArray):FloatArray {
            val out=FloatArray(n)
            for(y in 0 until height)for(x in 0 until width) {
                var sum=0f;var weight=0f
                for(dy in -1..1)for(dx in -1..1) {
                    val j=(y+dy).coerceIn(0,height-1)*width+(x+dx).coerceIn(0,width-1)
                    val a=alpha[j];sum+=source[j]*a;weight+=a
                }
                out[y*width+x]=if(weight>.01f)sum/weight else 0f
            }
            return out
        }
        val red=smooth(r);val green=smooth(g);val blue=smooth(b)
        val luma=FloatArray(n){red[it]*.2126f+green[it]*.7152f+blue[it]*.0722f}
        fun corner(right:Boolean,bottom:Boolean,channel:FloatArray):Float {
            var value=0f;var weight=0f
            val padX=maxOf(1,width/5);val padY=maxOf(1,height/5)
            for(y in 0 until padY)for(x in 0 until padX) {
                val px=if(right)width-1-x else x;val py=if(bottom)height-1-y else y
                val i=py*width+px;val a=alpha[i];value+=channel[i]*a;weight+=a
            }
            return if(weight>.01f)value/weight else channel[n/2]
        }
        val corners=arrayOf(red,green,blue).map {c ->floatArrayOf(corner(false,false,c),corner(true,false,c),corner(false,true,c),corner(true,true,c))}
        // Multi-stop/diagonal icon gradients are not bilinear. A boundary-colour palette
        // rejects their interior colours too, avoiding false triangular foreground patches.
        val borderColours=HashMap<Int,FloatArray>()
        for(y in 0 until height step 2)for(x in 0 until width step 2) {
            // Use the actual outer edge. A wider band can swallow a small, off-centre
            // logo into the background palette before normalization can centre it.
            if(x>=2 && y>=2 && x<width-2 && y<height-2)continue
            val i=y*width+x;if(alpha[i]<.5f)continue
            val key=(red[i].toInt()/20)*169+(green[i].toInt()/20)*13+blue[i].toInt()/20
            val bucket=borderColours.getOrPut(key) {FloatArray(4)}
            bucket[0]+=red[i];bucket[1]+=green[i];bucket[2]+=blue[i];bucket[3]++
        }
        val palette=borderColours.values.sortedByDescending {it[3]}.take(48).map {
            floatArrayOf(it[0]/it[3],it[1]/it[3],it[2]/it[3])
        }
        val residual=FloatArray(n)
        val histogram=IntArray(256)
        var borderTotal=0f;var borderCount=0
        for(y in 0 until height)for(x in 0 until width) {
            val i=y*width+x;if(alpha[i]<.1f)continue
            val u=x.toFloat()/(width-1);val v=y.toFloat()/(height-1)
            fun predicted(c:FloatArray)=(c[0]*(1-u)+c[1]*u)*(1-v)+(c[2]*(1-u)+c[3]*u)*v
            val dr=red[i]-predicted(corners[0]);val dg=green[i]-predicted(corners[1]);val db=blue[i]-predicted(corners[2])
            val predictedDistance=sqrt((dr*dr+dg*dg+db*db)/3f)
            var nearest=Float.MAX_VALUE
            for(colour in palette) {
                val pr=red[i]-colour[0];val pg=green[i]-colour[1];val pb=blue[i]-colour[2]
                nearest=minOf(nearest,(pr*pr+pg*pg+pb*pb)/3f)
            }
            residual[i]=if(palette.isEmpty())predictedDistance else minOf(predictedDistance,sqrt(nearest)*1.15f)
            if(x<width/10 || y<height/10 || x>=width-width/10 || y>=height-height/10) {
                borderTotal+=residual[i];borderCount++
            }
            val central=1f-.25f*minOf(1f,hypot((u-.5f)*2f,(v-.5f)*2f))
            histogram[(residual[i]*central).toInt().coerceIn(0,255)]++
        }
        var total=histogram.sum();var accumulated=0;var percentile=255
        for(i in histogram.indices) {accumulated+=histogram[i];if(accumulated>=total*.70f) {percentile=i;break}}
        val backgroundNoise=if(borderCount>0)borderTotal/borderCount else 0f
        val high=maxOf(22f,percentile.toFloat(),backgroundNoise*1.15f).coerceAtMost(105f)
        val low=maxOf(15f,high*.55f,backgroundNoise*.70f)
        val visited=BooleanArray(n);val queue=IntArray(n)
        data class Component(val indices:IntArray,val score:Float)
        val components=ArrayList<Component>()
        for(seed in 0 until n) {
            if(visited[seed] || alpha[seed]<.1f || residual[seed]<low)continue
            var start=0;var end=1;queue[0]=seed;visited[seed]=true
            var strength=0f;var hasSeed=false;var central=0f;var boundary=0
            while(start<end) {
                val i=queue[start++];val x=i%width;val y=i/width
                if(residual[i]>=high)hasSeed=true
                val d=hypot(x.toFloat()/(width-1)-.5f,y.toFloat()/(height-1)-.5f)
                central+=maxOf(.05f,1f-d*1.6f);strength+=residual[i]
                if(x<width/12 || y<height/12 || x>=width-width/12 || y>=height-height/12)boundary++
                fun add(j:Int) {if(!visited[j] && alpha[j]>=.1f && residual[j]>=low) {visited[j]=true;queue[end++]=j}}
                if(x>0)add(i-1);if(x<width-1)add(i+1);if(y>0)add(i-width);if(y<height-1)add(i+width)
            }
            if(hasSeed && end>=maxOf(4,n/600)) {
                val rank=strength*(central/end).pow(2)/(1f+4f*boundary/end)
                components.add(Component(queue.copyOf(end),rank))
            }
        }
        val ranked=components.sortedByDescending {it.score}
        val keep=BooleanArray(n)
        val best=ranked.firstOrNull()?.score ?: 0f
        var selected=0
        for(component in ranked.take(12)) {
            if(component.score<best*.055f)continue
            if(selected+component.indices.size>n*.76f)continue
            for(i in component.indices) {keep[i]=true;selected++}
        }
        val reliable=selected in maxOf(4,(n*.0015f).toInt())..(n*.76f).toInt()
        val sorted=luma.filterIndexed {i,_ ->alpha[i]>.1f}.sorted()
        val lower=sorted.getOrElse(sorted.size/10){0f}
        val upper=sorted.getOrElse(sorted.size*9/10){255f}
        val range=maxOf(24f,upper-lower)
        val output=IntArray(n)
        for(y in 0 until height)for(x in 0 until width) {
            val i=y*width+x
            val lum=luma[i]
            val dx=abs(luma[y*width+(x+1).coerceAtMost(width-1)]-luma[y*width+(x-1).coerceAtLeast(0)])
            val dy=abs(luma[(y+1).coerceAtMost(height-1)*width+x]-luma[(y-1).coerceAtLeast(0)*width+x])
            val tone=((lum-lower)/range).coerceIn(0f,1f)
            val detail=((dx+dy)/60f).coerceIn(0f,1f)
            val opacity=if(reliable) {
                if(keep[i])alpha[i] else 0f
            } else {
                // No trustworthy segmentation: etch tonal masses and internal edges. Keep
                // photographic detail without presenting an opaque/coloured mini-picture.
                val u=x.toFloat()/(width-1);val v=y.toFloat()/(height-1)
                val vignette=(minOf(u,v,1-u,1-v)*9f).coerceIn(0f,1f)
                alpha[i]*vignette*maxOf(detail*.85f,abs(tone-.5f)*1.40f).coerceIn(0f,.95f)
            }
            val a=(opacity*255f).roundToInt().coerceIn(0,255)
            val chromaticTone=(tone*.76f+.12f+(red[i]-blue[i])/255f*.12f).coerceIn(0f,1f)
            val heightValue=(35f+chromaticTone*200f).roundToInt().coerceIn(0,255)
            output[i]=if(a==0)0 else (a shl 24) or (heightValue shl 16) or (heightValue shl 8) or heightValue
        }
        return Result(output,if(reliable)"subject-saliency" else "subject-relief")
    }

    /** Preserve colour distinctions as relief depths, including colours with similar luminance. */
    fun tonalize(pixels:IntArray):IntArray? {
        val palette=HashMap<Int,Int>();val tones=ArrayList<Float>()
        fun tone(c:Int):Float {
            val r=c shr 16 and 255;val g=c shr 8 and 255;val b=c and 255
            return r*.2126f+g*.7152f+b*.0722f+(r-b)*.12f
        }
        for(c in pixels)if(c ushr 24>200) {
            val key=((c shr 16 and 255)/40)*49+((c shr 8 and 255)/40)*7+(c and 255)/40
            palette[key]=(palette[key] ?: 0)+1;tones.add(tone(c))
        }
        if(tones.isEmpty() || palette.count {it.value>=maxOf(3,tones.size/20)}<2)return null
        tones.sort();val low=tones[tones.size/20];val high=tones[tones.size*19/20]
        if(high-low<10f)return null
        return IntArray(pixels.size) {i ->
            val a=pixels[i] ushr 24
            if(a==0)0 else {
                val v=(40+((tone(pixels[i])-low)/(high-low)).coerceIn(0f,1f)*195).roundToInt()
                (a shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    /** Centre and fit actual ink, not source padding. Also fixes small emblems at top-left. */
    fun center(pixels:IntArray,width:Int,height:Int):IntArray {
        require(width>1 && height>1 && pixels.size==width*height)
        var left=width;var top=height;var right=-1;var bottom=-1
        for(y in 0 until height)for(x in 0 until width)if(pixels[y*width+x] ushr 24>=48) {
            left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
        }
        if(right<left || bottom<top)return pixels.copyOf()
        val span=maxOf(right-left+1,bottom-top+1).toFloat()
        val scale=minOf(width,height)*.72f/span
        val cx=(left+right)*.5f;val cy=(top+bottom)*.5f
        val output=IntArray(pixels.size)
        for(y in 0 until height)for(x in 0 until width) {
            val sx=(x-(width-1)*.5f)/scale+cx;val sy=(y-(height-1)*.5f)/scale+cy
            val ix=floor(sx).toInt();val iy=floor(sy).toInt()
            var a=0f;var r=0f;var g=0f;var b=0f
            for(dy in 0..1)for(dx in 0..1) {
                val px=ix+dx;val py=iy+dy;if(px !in 0 until width || py !in 0 until height)continue
                val weight=(if(dx==0)1-(sx-ix) else sx-ix)*(if(dy==0)1-(sy-iy) else sy-iy)
                val c=pixels[py*width+px];val alpha=(c ushr 24)*weight
                a+=alpha;r+=(c shr 16 and 255)*alpha;g+=(c shr 8 and 255)*alpha;b+=(c and 255)*alpha
            }
            if(a>=.5f)output[y*width+x]=(a.roundToInt().coerceIn(0,255) shl 24) or
                ((r/a).roundToInt().coerceIn(0,255) shl 16) or ((g/a).roundToInt().coerceIn(0,255) shl 8) or (b/a).roundToInt().coerceIn(0,255)
        }
        return output
    }
}
