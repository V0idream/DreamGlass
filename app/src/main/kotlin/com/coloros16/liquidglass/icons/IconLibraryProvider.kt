package com.coloros16.liquidglass.icons

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor

/** Only the module and its Launcher host may read downloaded artwork. Never writes remotely. */
class IconLibraryProvider:ContentProvider() {
    override fun onCreate()=true
    private fun checkCaller() {
        if(Binder.getCallingUid()==android.os.Process.myUid())return
        check(context!!.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
            .contains("com.android.launcher")) {"Icon library caller denied"}
    }
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle? {
        checkCaller();require(method=="catalog")
        return IconLibrary.runtimeCatalog(IconLibrary.forProvider(context!!))
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        checkCaller();require(mode=="r" && uri.pathSegments.size==1)
        val pkg=uri.lastPathSegment!!;require(IconLibrary.validPackage(pkg))
        val e=IconLibrary.forProvider(context!!).firstOrNull {it.pkg==pkg} ?: throw java.io.FileNotFoundException(pkg)
        return ParcelFileDescriptor.open(IconLibrary.file(context!!,e),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
    override fun getType(uri:Uri)="image/png"
    override fun insert(uri:Uri,v:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri:Uri,s:String?,a:Array<out String>?)=throw UnsupportedOperationException()
}
