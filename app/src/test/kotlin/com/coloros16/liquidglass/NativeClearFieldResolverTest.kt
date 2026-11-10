package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.NativeClearFieldResolver as Fields
import org.junit.Assert.*
import org.junit.Test

class NativeClearFieldResolverTest {
    private class Named {private val dataLock=Any();private val enableShader=true}
    private class Obfuscated {private val r=Any();private val a=true}
    private class Wrong {private val dataLock=1;private val alternate=Any()}
    private class Ambiguous {private val first=Any();private val second=Any()}
    @Test fun namedAndObfuscatedLocksUseAuditedTypedFields() {
        assertEquals("dataLock",Fields.resolve(Named::class.java,Any::class.java,"dataLock").name)
        assertEquals("r",Fields.resolve(Obfuscated::class.java,Any::class.java,"r").name)
        assertEquals("enableShader",Fields.resolve(Named::class.java,Boolean::class.javaPrimitiveType!!,"enableShader").name)
    }
    @Test fun uniqueTypedFallbackSupportsMixedObfuscation() {
        assertEquals("dataLock",Fields.resolve(Named::class.java,Any::class.java,"unknown",uniqueType=true).name)
    }
    @Test fun wrongAuditedTypeIsNeverReplacedWithAnUnrelatedField() {
        try {Fields.resolve(Wrong::class.java,Any::class.java,"dataLock",uniqueType=true);fail("wrong type accepted")}
        catch(_:IllegalStateException) {}
    }
    @Test fun ambiguousFallbackFailsClosed() {
        try {Fields.resolve(Ambiguous::class.java,Any::class.java,"unknown",uniqueType=true);fail("ambiguous field accepted")}
        catch(_:NoSuchFieldException) {}
    }
}
