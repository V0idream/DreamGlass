package com.coloros16.liquidglass.hook

/** Per-material uniform values. Unchanged frames allocate no arrays and make no JNI writes.
 * Clear whenever the RuntimeShader is replaced; a different shader has no uploaded state.
 */
internal class IconGlassUniformCache {
    private val values=HashMap<String,FloatArray>(24)
    fun one(name:String,x:Float):Boolean {
        val old=values[name]
        if(old!=null && old.size==1 && old[0]==x)return false
        if(old!=null && old.size==1)old[0]=x else values[name]=floatArrayOf(x)
        return true
    }
    fun two(name:String,x:Float,y:Float):Boolean {
        val old=values[name]
        if(old!=null && old.size==2 && old[0]==x && old[1]==y)return false
        if(old!=null && old.size==2) {old[0]=x;old[1]=y} else values[name]=floatArrayOf(x,y)
        return true
    }
    fun four(name:String,x:Float,y:Float,z:Float,w:Float):Boolean {
        val old=values[name]
        if(old!=null && old.size==4 && old[0]==x && old[1]==y && old[2]==z && old[3]==w)return false
        if(old!=null && old.size==4) {old[0]=x;old[1]=y;old[2]=z;old[3]=w}
        else values[name]=floatArrayOf(x,y,z,w)
        return true
    }
    fun clear()=values.clear()
}
