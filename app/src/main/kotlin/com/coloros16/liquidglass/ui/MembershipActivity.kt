package com.coloros16.liquidglass.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.coloros16.liquidglass.R

/** Local persistent self-report only; no payment transaction is verified. */
object MembershipState {
    private fun localConfirmed(c:Context)=com.coloros16.liquidglass.config.MemberFeaturePolicy.isConfirmed(com.coloros16.liquidglass.config.Prefs.raw(c))
    fun confirmed(c:Context):Boolean {
        if(!localConfirmed(c))return false
        val service=com.coloros16.liquidglass.App.serviceRef ?: return true
        return runCatching {com.coloros16.liquidglass.config.MemberFeaturePolicy.isConfirmed(com.coloros16.liquidglass.config.Prefs.raw(service))}.getOrDefault(false)
    }
    @Synchronized fun initialize(c:Context) {
        com.coloros16.liquidglass.config.MemberFeaturePolicy.disableIfUnconfirmed(com.coloros16.liquidglass.config.Prefs.raw(c))
    }
    @Synchronized fun synchronize(c:Context,service:io.github.libxposed.service.XposedService):Boolean =
        com.coloros16.liquidglass.config.MemberStoreCoordinator.synchronize(
            com.coloros16.liquidglass.config.Prefs.raw(c),com.coloros16.liquidglass.config.Prefs.raw(service))
    @Synchronized fun confirm(c:Context):Boolean {
        val policy=com.coloros16.liquidglass.config.MemberFeaturePolicy
        val local=com.coloros16.liquidglass.config.Prefs.raw(c)
        if(!policy.clearMemberSwitches(local) || !policy.confirm(local))return false
        val service=com.coloros16.liquidglass.App.serviceRef ?: return true
        val ok=runCatching {synchronize(c,service)}.getOrDefault(false)
        if(!ok) {policy.lock(local);runCatching {policy.lock(com.coloros16.liquidglass.config.Prefs.raw(service))}}
        return ok
    }
    @Synchronized fun decline(c:Context):Boolean {
        val local=com.coloros16.liquidglass.config.Prefs.raw(c)
        val service=com.coloros16.liquidglass.App.serviceRef
        return if(service!=null)com.coloros16.liquidglass.config.MemberStoreCoordinator.revoke(local,
            com.coloros16.liquidglass.config.Prefs.raw(service))
        else runCatching {com.coloros16.liquidglass.config.MemberFeaturePolicy.lock(local)}.getOrDefault(false)
    }
    val categories=setOf("particles","tilt","keyguard","components","seedling")
}

class MembershipActivity:AppCompatActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!MembershipState.confirmed(this@MembershipActivity)) {MembershipState.decline(this@MembershipActivity);finishAffinity()} else finish() }
        })
        show()
    }
    private fun show() {
        val confirmed=MembershipState.confirmed(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(DreamUi.canvas) }
        setContentView(root)
        WindowCompat.getInsetsController(window,root).apply { isAppearanceLightStatusBars=false;isAppearanceLightNavigationBars=false }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,i ->
            val b=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left,b.top,b.right,b.bottom);i
        }
        ViewCompat.requestApplyInsets(root)
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(24)) }
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(DreamUi.label(this,if(confirmed)"会员功能" else "诚信付款 · 会员扩展",23f,DreamUi.ink,true))
        body.addView(DreamUi.label(this,"基础材质完全免费。粒子飘散、应用与通知倾斜、锁屏玻璃，以及其他应用与组件玻璃属于会员扩展。",14f,DreamUi.muted))
        if(!confirmed) {
            body.addView(DreamUi.label(this,"模块开发与持续适配存在成本，希望你支付 5 元支持开发。这里只保存你的诚信确认，不核验支付记录。未确认前，所有会员效果保持关闭。",15f,DreamUi.ink).apply { setPadding(0,dp(14),0,dp(8)) })
            body.addView(ImageView(this).apply {
                setImageDrawable(assets.open("appreciation/wechat.png").use { android.graphics.drawable.Drawable.createFromStream(it,null) });scaleType=ImageView.ScaleType.FIT_CENTER
                contentDescription="微信赞赏码，建议支付五元"
            },LinearLayout.LayoutParams(-1,dp(210)))
            body.addView(DreamUi.label(this,"诚信确认会保留，版本更新无需重复确认或付款。确认后请自行开启所需功能。",13f,DreamUi.accent))
            body.addView(DreamUi.label(this,"当然你实在不想付费直接用我也不拦你，不强制你给钱所以也别骂我圈钱。",13f,DreamUi.muted))
        } else {
            body.addView(DreamUi.label(this,"诚信确认已保存",12f,DreamUi.accent))
            val card=DreamUi.card(body)
            for((id,title,desc) in listOf(
                Triple("particles","粒子飘散","通知删除、多任务卡片消散与速度"),
                Triple("tilt","动态倾斜","应用窗口与通知卡片的动画倾转"),
                Triple("keyguard","锁屏液态玻璃","时钟、卡片与底部快捷按钮，实验性"),
                Triple("components","其他应用与组件","相册、智能侧边栏与负一屏"),
                Triple("seedling","流体云展开卡片","收起使用黑底，展开应用玻璃"))) {
                DreamUi.row(card,title,desc) { openCategory(id) }
            }
        }
        val policy=DreamUi.card(body)
        policy.addView(DreamUi.label(this,"维护与反馈说明",16f,DreamUi.ink,true))
        policy.addView(DreamUi.label(this,"鉴于本人精力有限，针对会员扩展中这些难以维护功能的建议与反馈，仅对实际付费用户开放。",13f,DreamUi.muted))
        if(!confirmed) {
            // Exactly the two requested actions; both stay visible on short displays.
            val actions=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),dp(12)) }
            DreamUi.action(actions,"我已支付，请继续",true) {
                if(MembershipState.confirm(this)) {
                    val destination=intent.getStringExtra("destination")
                    if(destination in MembershipState.categories) { openCategory(destination!!);finish() }
                    else show()
                } else Toast.makeText(this,"确认未能保存，请重试",Toast.LENGTH_SHORT).show()
            }
            DreamUi.action(actions,"暂不支付，退出",false) { if(!MembershipState.decline(this))Toast.makeText(this,"关闭状态未能同步，请重新打开模块重试",Toast.LENGTH_LONG).show();finishAffinity() }
            root.addView(actions)
        } else {
            DreamUi.action(body,"查看赞赏码与关于页面",false) { startActivity(Intent(this,LicenseActivity::class.java)) }
        }
    }
    private fun openCategory(id:String) {
        startActivity(Intent(this,SettingsActivity::class.java).putExtra("category",id))
    }
    private fun dp(n:Int)=DreamUi.dp(this,n)
}
