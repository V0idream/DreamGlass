package com.coloros16.liquidglass.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.icons.IconLibrary
import java.util.concurrent.Executors

internal object IconLibraryUi {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"DreamGlass-icon-download").apply {isDaemon=true}}
    fun bind(activity:Activity,view:View) {
        val scan=view.findViewById<Button>(R.id.iconLibraryScan)
        val download=view.findViewById<Button>(R.id.iconLibraryDownload)
        val status=view.findViewById<TextView>(R.id.iconLibraryStatus)
        val home=view.findViewById<Button>(R.id.iconLibraryHome)
        var matches=emptyList<IconLibrary.Entry>();var busy=false
        view.findViewById<Button>(R.id.iconCustomize).setOnClickListener {
            activity.startActivity(Intent(activity,IconCustomizationActivity::class.java))
        }
        download.isEnabled=false
        fun render(text:String) {if(!activity.isDestroyed && !activity.isFinishing)status.text=text}
        fun finish(text:String) {busy=false;scan.isEnabled=true;download.isEnabled=matches.isNotEmpty();render(text)}
        home.setOnClickListener {activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(IconLibrary.HOME)))}
        scan.setOnClickListener {
            if(busy)return@setOnClickListener
            busy=true;scan.isEnabled=false;download.isEnabled=false;render("正在获取图标目录并匹配本机应用…")
            worker.execute {
                val result=runCatching {
                    val installed=IconLibrary.installed(activity)
                    IconLibrary.fetchCatalog().onEach {require(it.source=="library")}.filter {it.pkg in installed}
                }
                activity.runOnUiThread {
                    if(activity.isDestroyed || activity.isFinishing)return@runOnUiThread
                    result.fold({list ->matches=list;finish(if(list.isEmpty())"暂未找到匹配的定制图标。" else
                        "找到 ${list.size} 个匹配应用：\n"+list.joinToString("、"){it.label}+"\n点击下方按钮下载并应用。")},
                        {finish("扫描失败：${it.message?.take(120)}。可稍后重试。")})
                }
            }
        }
        download.setOnClickListener {
            if(busy || matches.isEmpty())return@setOnClickListener
            busy=true;scan.isEnabled=false;download.isEnabled=false;val batch=matches.toList()
            render("正在下载定制图标…")
            worker.execute {
                val result=runCatching {IconLibrary.download(activity,batch) {done,total ->
                    activity.runOnUiThread {render("正在下载并校验：$done / $total")}
                }}.onSuccess {IconLibrary.publish(activity,it)}
                activity.runOnUiThread {
                    if(activity.isDestroyed || activity.isFinishing)return@runOnUiThread
                    result.fold({finish("已下载 ${batch.size} 个定制图标，返回桌面后应用。下载后可离线使用。")},
                        {finish("下载失败：${it.message?.take(120)}。原有图标保持可用。")})
                }
            }
        }
    }
}
