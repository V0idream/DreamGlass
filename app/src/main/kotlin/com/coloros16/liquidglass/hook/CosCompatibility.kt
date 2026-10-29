package com.coloros16.liquidglass.hook

/** Never infer COS16 volume draw ordering from SDK level alone. Unknown hosts stay native. */
object CosCompatibility {
    /** COS17 17.3.12 removes the named PressFeedbackHandler used by the COS16 path. */
    fun supportsLegacyPressFeedback(version:String?):Boolean = version?.substringBefore('.')=="16"
    fun supportsLegacyVolumeOverrides(version:String?):Boolean = version?.substringBefore('.')=="16"
}
