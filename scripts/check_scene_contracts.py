"""Verify stock17 plugin/wallpaper signatures preserved in the original DEX inventory."""
from pathlib import Path
import argparse,json
p=argparse.ArgumentParser();p.add_argument('--systemui',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();data=json.loads(a.systemui.read_text(encoding='utf-8'));classes=data['classes']
targets={
 'com.oplus.keyguard.plugin.AbstractSubPlugin':['getView(I)Landroid/view/View;'],
 'com.oplus.keyguard.plugin.ClockPlugin':['setPeerWallpaperBitmap(Landroid/os/Bundle;)V'],
 'com.oplus.keyguard.plugin.WidgetPlugin':[],
 'com.oplus.keyguard.binder.WallpaperPeerTransfer':['resolveBitmapFromBinder(Landroid/os/IBinder;)Landroid/graphics/Bitmap;'],
 'com.oplus.systemui.keyguard.wallpaper.OplusLockScreenWallpaperController':['copyWallpaperBitmap()Landroid/graphics/Bitmap;'],
 'com.oplus.systemui.keyguard.view.CustomOplusKeyguardStyleClock':['onAttachedToWindow()V'],
}
for name,methods in targets.items():
 for method in methods:assert method in classes[name]['methods'],(name,method)
assert classes['com.oplus.keyguard.plugin.ClockPlugin']['super']=='Lcom/oplus/keyguard/plugin/AbstractSubPlugin;'
assert classes['com.oplus.keyguard.plugin.WidgetPlugin']['super']=='Lcom/oplus/keyguard/plugin/AbstractSubPlugin;'
result={'systemUiSha256':data['sha256'],'matchedSignatures':targets,'dexInventoryEvidenceOnly':True,
 'nativePluginMethodsExecuted':False,'physicalDeviceTested':False,
 'oemAppBackgroundBridge':'Runtime capability check; framework implementation is not contained in this SystemUI inventory'}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,indent=2))
