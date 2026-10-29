package com.coloros16.liquidglass.hook

/** Decorate controls/cards, never the extra outer frame around a toolbar, screen or strip. */
object AppGlassSurfacePolicy {
    const val NONE=0;const val CONTROL=1;const val PANEL=2
    fun role(name:String,id:String,width:Float,height:Float,screenWidth:Float,screenHeight:Float,
        group:Boolean,clickable:Boolean,descendantControl:Boolean):Int {
        if(width<=0 || height<=0 || screenWidth<=0 || screenHeight<=0)return NONE
        val text=(name+"/"+id).lowercase()
        val chrome=listOf("toolbar","tool_bar","actionbar","action_bar","navigation","bottom_bar","top_bar","title_bar","thumbnail","filmstrip","root_view","decorview").any(text::contains)
        val wide=width>=screenWidth*.68f && width/height>2.5f
        if(width>=screenWidth*.9f && height>=screenHeight*.65f)return NONE
        val explicitButton=listOf("button","icon").any(text::contains) && width<screenWidth*.45f && width/height in .4f..2.4f
        if(explicitButton)return CONTROL
        if(chrome && (width>=screenWidth*.68f || width/height>2.5f && !clickable || group && descendantControl))return NONE
        if(group && wide && descendantControl)return NONE
        if(clickable || !group && listOf("button","action","icon").any(text::contains))return CONTROL
        if(text.contains("sidebar") || text.contains("smartside") || text.contains("assistant") || text.contains("card"))return PANEL
        return if(group && descendantControl && !wide)PANEL else NONE
    }
}
