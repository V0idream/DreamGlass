/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import android.graphics.Paint
import android.graphics.Shader

internal object NativePaintShaderLease {
    fun <T> withShader(paint:Paint,shader:Shader,draw:()->T):T {
        val original=paint.shader
        try {paint.shader=shader;return draw()} finally {paint.shader=original}
    }
}
