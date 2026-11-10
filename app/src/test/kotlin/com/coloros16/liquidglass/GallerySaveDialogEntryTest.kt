package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.GallerySaveDialogEntry
import org.junit.Assert.*
import org.junit.Test

class GallerySaveDialogEntryTest {
    open class Context
    class View
    open class Dialog
    open class Alert:Dialog()
    class NativeAlert:Alert()
    open class ParentBuilder {fun getContext():Context=Context()}
    class SaveBuilder:ParentBuilder() {fun show(view:View?):NativeAlert=NativeAlert()}
    class ForeignBuilder:ParentBuilder() {fun show(view:View?):Dialog=Dialog()}
    class WrongOverload:ParentBuilder() {fun show():Alert=Alert()}
    class WrongContext {fun getContext():Any=Any();fun show(view:View?):Alert=Alert()}
    private fun resolve(type:Class<*>)=GallerySaveDialogEntry.resolve(type,View::class.java,
        Dialog::class.java,Alert::class.java,Context::class.java)

    @Test fun actualReturnMayBeAlertSubclassAndBuilderContextMayBeInherited() {
        val contract=resolve(SaveBuilder::class.java)
        val builder=SaveBuilder()
        assertTrue(contract.show.invoke(builder,null) is NativeAlert)
        assertTrue(contract.context.invoke(builder) is Context)
        assertEquals(ParentBuilder::class.java,contract.context.declaringClass)
    }
    @Test fun foreignDialogReturnIsRejectedBeforeRegisteringAnUnsafeHook() {
        assertThrows(IllegalArgumentException::class.java) {resolve(ForeignBuilder::class.java)}
    }
    @Test fun noArgumentShowCannotBeConfusedWithTheAuditedViewEntry() {
        assertThrows(NoSuchMethodException::class.java) {resolve(WrongOverload::class.java)}
    }
    @Test fun sameNamedContextGetterMustActuallyReturnAContext() {
        assertThrows(IllegalArgumentException::class.java) {resolve(WrongContext::class.java)}
    }
}
