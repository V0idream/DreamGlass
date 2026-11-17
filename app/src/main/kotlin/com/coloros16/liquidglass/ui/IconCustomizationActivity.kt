package com.coloros16.liquidglass.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.icons.IconImport
import com.coloros16.liquidglass.icons.IconLibrary
import java.util.concurrent.Executors

class IconCustomizationActivity:AppCompatActivity() {
    private var selected:String?=null
    private lateinit var status:TextView
    private lateinit var choose:Button
    private lateinit var image:Button
    private lateinit var zip:Button
    private lateinit var reset:Button
    private var busy=false
    private val worker=Executors.newSingleThreadExecutor()
    private val pickImage=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        val pkg=selected
        if(uri!=null && pkg!=null)work("正在处理图片…") {
            val bytes=contentResolver.openInputStream(uri)!!.use {IconImport.read(it,IconImport.MAX_IMAGE)}
            val revision=IconLibrary.saveUploads(this,mapOf(pkg to IconImport.normalize(bytes)))
            revision to "已替换 $pkg 的图标，返回桌面后应用。"
        }
    }
    private val pickZip=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null)work("正在校验并导入 ZIP…") {
            val images=contentResolver.openInputStream(uri)!!.use {IconImport.zip(it)}
            IconLibrary.saveUploads(this,images) to "已导入 ${images.size} 个图标。未安装应用的图标会保留，安装后即可匹配。"
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);selected=state?.getString("selected")
        val density=resources.displayMetrics.density
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding((20*density).toInt(),(28*density).toInt(),(20*density).toInt(),0)}
        fun text(value:String,size:Float)=TextView(this).apply {text=value;textSize=size;setTextColor(getColor(R.color.dream_ink));column.addView(this)}
        text("自定义桌面图标",24f)
        text("选择应用并上传图片，或导入图片压缩包。自定义图标优先于在线库；需开启桌面图标功能。",14f)
        text("ZIP 内只放图片，按“包名.png / jpg / webp”命名，例如 com.example.app.png。图片会居中裁切为正方形。",13f)
        fun button(label:String)=Button(this).apply {text=label;column.addView(this,LinearLayout.LayoutParams(-1,-2))}
        choose=button(selected ?: "选择应用")
        image=button("上传图片作为图标");image.isEnabled=selected!=null
        zip=button("一键导入 ZIP 图标包")
        reset=button("恢复所选应用默认图标");reset.isEnabled=selected!=null
        status=text("图片仅保存在本机，不会上传 GitHub。",14f)
        button("返回").setOnClickListener {finish()}
        setContentView(ScrollView(this).apply {addView(column)})
        choose.setOnClickListener {showApps()}
        image.setOnClickListener {pickImage.launch(arrayOf("image/*"))}
        zip.setOnClickListener {pickZip.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}
        reset.setOnClickListener {selected?.let {pkg ->work("正在恢复…") {
            IconLibrary.removeUpload(this,pkg) to "已移除 $pkg 的自定义图片，返回桌面后恢复在线定制或自动生成图标。"
        }}}
    }
    private fun showApps() {
        work("正在扫描已安装应用…",false) {
            val apps=IconLibrary.installed(this).map {pkg ->pkg to runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString()
            }.getOrDefault(pkg)}.sortedBy {it.second.lowercase()}
            runOnUiThread {
                if(isDestroyed || isFinishing)return@runOnUiThread
                val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
                val search=EditText(this).apply {hint="搜索应用名称或包名"};column.addView(search)
                val list=ListView(this);column.addView(list,LinearLayout.LayoutParams(-1,(400*resources.displayMetrics.density).toInt()))
                var visible=apps
                fun refresh() {val q=search.text.toString().trim();visible=apps.filter {it.first.contains(q,true)||it.second.contains(q,true)}
                    list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,visible.map {"${it.second}\n${it.first}"})}
                val dialog=MaterialAlertDialogBuilder(this).setTitle("选择应用").setView(column).setNegativeButton("取消",null).create()
                list.setOnItemClickListener {_,_,position,_ ->val app=visible[position];selected=app.first
                    choose.text="${app.second}\n${app.first}";image.isEnabled=true;reset.isEnabled=true;dialog.dismiss()}
                search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}
                    override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){refresh()};override fun afterTextChanged(s:Editable?) {}})
                refresh();dialog.show()
            }
            "" to "请选择要替换图标的应用。"
        }
    }
    private fun work(label:String,publish:Boolean=true,job:()->Pair<String,String>) {
        if(busy)return
        busy=true;status.text=label;listOf(choose,image,zip,reset).forEach {it.isEnabled=false}
        worker.execute {
            val result=runCatching {job()}
            // Publish even if the user leaves this Activity while processing a document.
            result.getOrNull()?.first?.takeIf {publish && it.isNotEmpty()}?.let {IconLibrary.publish(this,it)}
            runOnUiThread {
                if(isDestroyed || isFinishing)return@runOnUiThread
                busy=false;choose.isEnabled=true;zip.isEnabled=true;image.isEnabled=selected!=null;reset.isEnabled=selected!=null
                status.text=result.fold({it.second},{"未应用：${it.message?.take(160)}"})
            }
        }
    }
    override fun onSaveInstanceState(state:Bundle) {state.putString("selected",selected);super.onSaveInstanceState(state)}
    override fun onDestroy() {worker.shutdown();super.onDestroy()}
}
