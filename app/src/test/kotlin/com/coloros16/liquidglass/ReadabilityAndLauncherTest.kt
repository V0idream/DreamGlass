package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.MaterialReadabilityPolicy
import com.coloros16.liquidglass.hook.LauncherTiltContract
import org.junit.Assert.*
import org.junit.Test

class ReadabilityAndLauncherTest {
    @Test fun explicitGlobalDimWinsOverWeakBuiltinPreset() {
        assertEquals(.7f,MaterialReadabilityPolicy.dim(.7f,true,.04f,null),0f)
        assertEquals(0f,MaterialReadabilityPolicy.dim(0f,true,.04f,null),0f)
    }
    @Test fun componentOverridesAndUntouchedPresetDefaultsRemainAvailable() {
        assertEquals(.2f,MaterialReadabilityPolicy.dim(.7f,true,.04f,.2f),0f)
        assertEquals(.04f,MaterialReadabilityPolicy.dim(0f,false,.04f,null),0f)
        assertEquals(1f,MaterialReadabilityPolicy.dim(2f,true,null,Float.NaN),0f)
    }
    @Test fun foregroundDecisionFollowsGrayInsteadOfRawBackground() {
        assertTrue(MaterialReadabilityPolicy.tintedLuminance(.02,.9f,0f)>.2)
        assertTrue(MaterialReadabilityPolicy.tintedLuminance(.9,.1f,.5f)<.15)
    }
    private class Target
    private class Helper
    private class Tx
    private class Params
    private class Old { @JvmField var a=Target();@JvmField var b=Helper();@JvmField var c=Tx();@JvmField var d=Params();fun run() {} }
    private class New { @JvmField var a=Target();@JvmField var b=Helper();@JvmField var c=Tx();@JvmField var d=Params();fun run() {} }
    private class ReusedOldName { @JvmField var a=Target();@JvmField var b=Tx();fun run() {} }
    private fun select(vararg classes:Class<*>)=LauncherTiltContract.callback(classes.toList(),Target::class.java,Helper::class.java,Tx::class.java,Params::class.java)
    @Test fun originalLauncherContractStillMatches() { assertEquals(Old::class.java,select(Old::class.java,New::class.java)) }
    @Test fun newlyReusedClassNameIsRejectedAndNewCallbackMatches() { assertEquals(New::class.java,select(ReusedOldName::class.java,New::class.java)) }
    @Test fun unknownCaptureLayoutKeepsNativeAnimation() { assertNull(select(ReusedOldName::class.java)) }
}
