package com.coloros16.liquidglass

import com.coloros16.liquidglass.icons.IconImport
import com.coloros16.liquidglass.icons.IconLibrary
import com.coloros16.liquidglass.hook.IconGlassDynamicIcons
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class IconLibraryImportTest {
    @Test fun acceptsExactPackageFilenamesIncludingRealUppercasePackage() {
        assertEquals("com.One.WoodenLetter",IconImport.packageForEntry("com.One.WoodenLetter.PNG"))
        assertEquals("me.sukimon.mdl",IconImport.packageForEntry("me.sukimon.mdl.webp"))
    }
    @Test fun rejectsTraversalNestedFilesAndNonImages() {
        for(name in listOf("../com.test.app.png","folder/com.test.app.png","C:\\com.test.app.png",
            "/com.test.app.png","com.test.app.svg","com.test.app.apk","icon.png","com..test.png")) {
            assertTrue(name,runCatching {IconImport.packageForEntry(name)}.isFailure)
        }
    }
    @Test fun enforcesStreamSizeEvenWithoutDeclaredLength() {
        assertEquals(4,IconImport.read(byteArrayOf(1,2,3,4).inputStream(),4).size)
        assertTrue(runCatching {IconImport.read(ByteArray(5).inputStream(),4)}.isFailure)
    }
    @Test fun repositoryOnlyAcceptsRelativePngArtworkPaths() {
        assertTrue(IconLibrary.validPath("icons/delta-force.png"))
        for(path in listOf("https://example.com/a.png","icons/../a.png","/icons/a.png","icons/a.svg"))assertFalse(IconLibrary.validPath(path))
    }
    @Test fun timeCacheChangesOnMinuteAndTimezoneButNotEachFrame() {
        val t=Instant.parse("2026-11-17T03:16:02Z").toEpochMilli();val utc=ZoneId.of("UTC")
        val pkg="com.coloros.alarmclock"
        assertEquals(IconGlassDynamicIcons.bucket(pkg,t,utc),IconGlassDynamicIcons.bucket(pkg,t+20000,utc))
        assertNotEquals(IconGlassDynamicIcons.bucket(pkg,t,utc),IconGlassDynamicIcons.bucket(pkg,t+60000,utc))
        assertNotEquals(IconGlassDynamicIcons.bucket(pkg,t,utc),IconGlassDynamicIcons.bucket(pkg,t,ZoneId.of("Asia/Shanghai")))
    }
    @Test fun calendarChangesOnlyOnLocalDateAndHandlesMidnight() {
        val t=Instant.parse("2026-11-17T15:59:30Z").toEpochMilli();val zone=ZoneId.of("Asia/Shanghai")
        assertEquals("2026-11-17",IconGlassDynamicIcons.bucket("com.youloft.calendar",t,zone))
        assertEquals("2026-11-18",IconGlassDynamicIcons.bucket("com.youloft.calendar",t+60000,zone))
        assertEquals("static",IconGlassDynamicIcons.bucket("com.netease.dwrg",t,zone))
    }
}
