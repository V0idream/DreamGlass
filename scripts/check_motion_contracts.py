"""Check motion bindings against preserved DEX inventories, without a connected device.
The inventories contain signatures, not executable vendor implementations.
"""
from pathlib import Path
import argparse, json, hashlib
p=argparse.ArgumentParser()
p.add_argument('--launcher',type=Path,required=True)
p.add_argument('--systemui',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--headsup-source',type=Path,required=True)
a=p.parse_args()
launcher=json.loads(a.launcher.read_text(encoding='utf-8'))
systemui=json.loads(a.systemui.read_text(encoding='utf-8'))
checks=[]
def require(data, name, methods=(), fields=()):
    c=data['classes'][name]
    for method in methods: assert method in c['methods'], (name,method)
    for field in fields: assert field in c['fields'], (name,field)
    checks.append(name)
helper='com.android.quickstep.util.animation.RectTransformHelper'
params=helper+'$TransformParams'
light='com.android.launcher3.anim.light.AppLaunchAnimUtil$getLightWindowAnimation$1'
require(launcher,helper,[f'a(L{params.replace(".","/")};[Lgi/c;ILxi/q;Lni/e;Landroid/graphics/RectF;ZLandroid/graphics/RectF;Lgi/c;)V'],['a:Landroid/graphics/Rect;','c:Landroid/graphics/Rect;'])
require(launcher,params,fields=['l:F','D:Z'])
require(launcher,'xi.q',['<init>()V','e(Landroid/view/SurfaceControl;)Lxi/q$b;','g()Landroid/view/SurfaceControl$Transaction;'])
require(launcher,'xi.q$b',['i(Landroid/graphics/Matrix;)Lxi/q$b;','j([F)V'],['a:Landroid/view/SurfaceControl;','b:Lxi/q;'])
require(launcher,'gi.c',fields=['d:Landroid/view/SurfaceControl;','k:Landroid/graphics/Rect;','c:I','s:I'])
require(launcher,light,[f'b(Lgi/c;Lxi/q;ZZFFZZL{light.replace(".","/")};Landroid/graphics/Matrix;FLcom/android/launcher3/Launcher;FLandroid/graphics/Point;Lkotlin/jvm/internal/Ref$BooleanRef;Landroid/graphics/Rect;)V'])
require(systemui,'com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController$10',['onSwipeOutAnimStart(Landroid/view/View;)V','onChildDismissed(Landroid/view/View;)V','handleChildViewDismissed(Landroid/view/View;)V','onChildNotDismissed(Landroid/view/View;ZZ)V'])
row='com.android.systemui.statusbar.notification.row.ExpandableNotificationRow'
require(systemui,row,['performDismiss(Z)V'])
assert any(m.startswith('performAddAnimation(') for m in systemui['classes'][row]['methods'])
assert any(m.startswith('performRemoveAnimation(') for m in systemui['classes'][row]['methods'])
require(systemui,'com.android.systemui.SwipeHelper',['onDownUpdate(Landroid/view/View;)V','snapChild(FFLandroid/view/View;)V','onChildSnappedBack(FLandroid/view/View;)V'])
require(systemui,'com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper',['onDownUpdate(Landroid/view/View;)V','dismiss(Landroid/view/View;F)V','snapChild(FFLandroid/view/View;)V','superDismissChild(Landroid/view/View;FZ)V'])
require(systemui,'com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpAnimator',['show(Landroid/view/View;)V','onY(FLkotlin/jvm/functions/Function0;)V','animateTo(FLkotlin/jvm/functions/Function0;)V'])
require(systemui,'com.oplus.systemui.notification.headsup.windowframe.HeadsUpContainerWindow',['getView()Landroid/view/View;'])
source=a.headsup_source.read_text(encoding='utf-8')
assert 'private final HeadsUpContainerWindow control;' in source and 'public float holdY;' in source
source_sha=hashlib.sha256(a.headsup_source.read_bytes()).hexdigest()
report={'headsupSourceSha256':source_sha,'headsupFieldsCheckedFromDecompilation':True,'launcherSha256':launcher['sha256'],'systemUiSha256':systemui['sha256'],
        'matchedClasses':checks,'classCount':len(checks),'physicalDeviceTested':False,
        'dexSignatureEvidenceOnly':True,'surface4x4ImplementationExecuted':False}
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
