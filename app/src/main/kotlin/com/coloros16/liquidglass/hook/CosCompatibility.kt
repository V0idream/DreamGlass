package com.coloros16.liquidglass.hook

/** Never infer COS16 volume draw ordering from SDK level alone. Unknown hosts stay native. */
object CosCompatibility {
    fun supportsLegacyVolumeOverrides(version:String?):Boolean = version?.substringBefore('.')=="16"
}
