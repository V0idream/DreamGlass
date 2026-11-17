/* DreamGlass contributors, 2026-11-13. GPL-3.0. */
package com.coloros16.liquidglass.hook

/** Only the real desktop/Dock icon slot. Never infer ownership from the drawable's class alone. */
internal object IconGlassPolicy {
    private val blockedNames=listOf("allapps","searchresult",
        "floatingicon","dragview","recentsview","deepshortcuts","taskbar")
    fun enabled(master:Boolean,glass:Boolean,feature:Boolean,member:Boolean) = master && glass && feature && member

    fun eligible(display:Int,container:Long,visible:Boolean,interactive:Boolean,locked:Boolean,
                 powerSave:Boolean,blockedAncestor:Boolean,desktopHost:Boolean=false,folderPreview:Boolean=false,
                 suggestion:Boolean=false):Boolean = ((display==0 && (container==-100L || container==-101L)) ||
        (container>=0 && display==2) || desktopHost || folderPreview || suggestion) &&
        visible && interactive && !locked && !powerSave && !blockedAncestor

    fun blockedParent(name:String):Boolean {
        val n=name.lowercase()
        return blockedNames.any(n::contains)
    }

    fun validSize(width:Float,height:Float):Boolean = width.isFinite() && height.isFinite() &&
        width>=8f && height>=8f && width<=1024f && height<=1024f && width/height in .65f..1.55f
}
