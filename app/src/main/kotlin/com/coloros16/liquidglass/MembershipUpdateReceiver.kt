package com.coloros16.liquidglass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.coloros16.liquidglass.ui.MembershipState
import io.github.libxposed.service.XposedService
import java.util.concurrent.atomic.AtomicBoolean

/** Preserve existing explicit confirmation and switches across APK updates.
 * The bounded service sync still denies unconfirmed/revoked stores. Loading new
 * rendering code into an already-running target remains the framework's job. */
class MembershipUpdateReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED)return
        val app=context.applicationContext
        MembershipState.initialize(app)
        val pending=goAsync();val handler=Handler(Looper.getMainLooper())
        val completed=AtomicBoolean(false)
        var timeout:Runnable?=null
        lateinit var listener:App.ServiceStateListener
        fun finish() {
            if(!completed.compareAndSet(false,true))return
            timeout?.let {handler.removeCallbacks(it)}
            App.removeServiceStateListener(listener);pending.finish()
        }
        listener=object:App.ServiceStateListener {
            override fun onServiceStateChanged(service:XposedService?) {
                if(service!=null && runCatching {MembershipState.synchronize(app,service)}.getOrDefault(false))finish()
            }
        }
        timeout=Runnable {finish()}
        App.addServiceStateListener(listener,true)
        if(!completed.get())handler.postDelayed(timeout!!,8_000L)
    }
}
