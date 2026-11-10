/* DreamGlass contributors, 2026. GPL-3.0-or-later. */
package com.coloros16.liquidglass.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Accept audited names and types only; never guess among unrelated boolean,
 * paint or matrix fields in an OEM class. A unique shader type is safe when a
 * build retains the named BaseDrawable but obfuscates DrawableShader. */
internal object NativeClearFieldResolver {
    fun resolve(owner:Class<*>,expected:Class<*>,vararg names:String,uniqueType:Boolean=false):Field {
        for(name in names) {
            val field=runCatching {owner.getDeclaredField(name)}.getOrNull() ?: continue
            check(field.type==expected && !Modifier.isStatic(field.modifiers)) {"${owner.name}.$name type mismatch"}
            return field.apply {isAccessible=true}
        }
        if(uniqueType)owner.declaredFields.singleOrNull {
            it.type==expected && !Modifier.isStatic(it.modifiers)
        }?.let {return it.apply {isAccessible=true}}
        throw NoSuchFieldException("${owner.name}: ${names.joinToString()}")
    }
}
