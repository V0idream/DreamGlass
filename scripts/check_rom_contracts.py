"""Check actual DEX field/method contracts; no device connection or APK upload.

python scripts/check_rom_contracts.py --systemui FILE --launcher FILE --module FILE
"""
import argparse, re, unittest, zipfile, hashlib, struct, sys
from pathlib import Path
from dex_inspector import scan

p=argparse.ArgumentParser()
p.add_argument('--systemui',required=True);p.add_argument('--launcher',required=True);p.add_argument('--module',required=True)
p.add_argument('--framework')
args=p.parse_args(); root=Path(__file__).resolve().parents[1]
system=scan(args.systemui)['classes']; launcher=scan(args.launcher)['classes']; module=scan(args.module)['classes']
framework=scan(args.framework)['classes'] if args.framework else None

class RomContracts(unittest.TestCase):
    def test_launcher_fields_match_bytecode_and_reflection(self):
        cs=launcher['com.oplus.posteffect.drawable.b']
        source=(root/'app/src/main/kotlin/com/coloros16/liquidglass/hook/LauncherHook.kt').read_text(encoding='utf-8')
        contracts={'FIELD_BLUR_SHADER_PAINT_17':('M','Landroid/graphics/Paint;'),
                   'FIELD_PAINT_17':('f','Landroid/graphics/Paint;'),
                   'FIELD_RENDER_NODE_17':('h','Landroid/graphics/RenderNode;'),
                   'FIELD_DRAWABLE_PATH_17':('i','Landroid/graphics/Path;'),
                   'FIELD_PAINT_ALPHA_17':('F','I'),'FIELD_ENABLE_BLUR_SHADER_17':('a','Z')}
        for key,(name,type_) in contracts.items():
            self.assertEqual(re.search(r'const val '+key+r' = "([^"]+)"',source).group(1),name)
            self.assertIn(name+':'+type_,cs['fields'])
        self.assertIn('a(Lcom/oplus/posteffect/drawable/d;)V',cs['methods'])
        self.assertIn('w:Z',cs['fields'])
        self.assertEqual(launcher['com.oplus.posteffect.drawable.d']['super'],'Lcom/oplus/posteffect/drawable/b;')

    def test_systemui_core_signatures(self):
        targets={
            'com.oplus.posteffect.drawable.BaseDrawable':['drawBlurShader(Landroid/graphics/Canvas;)V','onDrawContent(Landroid/graphics/Canvas;)V','getDrawableShaderPaint()Landroid/graphics/Paint;'],
            'com.oplus.posteffect.drawable.BlendDrawable':['onDrawContent(Landroid/graphics/Canvas;)V'],
            'com.oplus.posteffect.agsl.DrawableShader':['getShaderOrNull()Landroid/graphics/RuntimeShader;'],
            'com.oplus.posteffect.manager.BlurDrawableManager':['onBlurReady(Ljava/util/List;Landroid/hardware/HardwareBuffer;IFLandroid/graphics/Rect;)V']}
        for cls,methods in targets.items():
            for method in methods:self.assertIn(method,system[cls]['methods'])

    def test_cos17_wide_icon_decoration_contract(self):
        prefix='com.oplus.systemui.plugins.qs.customize.view.tile.'
        icon=system[prefix+'OplusQSIconView']
        self.assertIn('iconBgView:Landroid/widget/ImageView;',icon['fields'])
        self.assertIn('updateIconBgDrawable()V',icon['methods'])
        self.assertIn('onLayout(ZIIII)V',icon['methods'])
        self.assertIn('onAttachedToWindow()V',icon['methods'])
        self.assertIn('onIconDrawableUpdate(Z)V',icon['methods'])
        self.assertIn(prefix+'OplusQSResizeableTileViewTwoXOne',system)
        self.assertIn('com.coloros16.liquidglass.hook.ControlCenterStyleHook',module)

    def test_independent_volume_platform_wrapper(self):
        wrapper=system['com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable']
        self.assertIn('getHostViewName:Lkotlin/jvm/functions/Function0;',wrapper['fields'])
        self.assertIn('blurMaskColor:I',wrapper['fields'])
        self.assertIn('draw(Landroid/graphics/Canvas;)V',wrapper['methods'])
        self.assertIn('getBlurDrawable()Lcom/oplus/posteffect/BlurDrawable;',wrapper['methods'])
        self.assertIn('com.oplus.systemui.volume.OplusVolumeSeekBar',system)
        for name in ('NativeScenePolicy','NativeDrawScope','VolumePanelGuard'):
            self.assertIn('com.coloros16.liquidglass.hook.'+name,module)

    def test_module_entry_and_new_product_classes(self):
        for name in ['entry.XposedEntry','ui.DreamActivity','ui.SettingsActivity','diagnostics.RuntimeDiagnostics','diagnostics.RuntimeStore','root.ScopeRestarter','hook.CaptureApi','hook.BlurDrawHook','hook.LauncherHook','liquidglass.LiquidGlassShader']:
            self.assertIn('com.coloros16.liquidglass.'+name,module)
        self.assertNotIn('io.github.libxposed.api.XposedModule',module)
        self.assertNotIn('android.window.ScreenCaptureInternal',module) # JVM fixtures must stay out of the APK.

    @unittest.skipUnless(args.framework, 'Supply --framework for the actual connected ROM contract')
    def test_api37_capture_family_matches_window_manager(self):
        root='android.window.ScreenCaptureInternal'
        self.assertNotIn('android.window.ScreenCapture$CaptureArgs',framework)
        self.assertIn('createSyncCaptureListener()Landroid/window/ScreenCaptureInternal$SynchronousScreenCaptureListener;',framework[root]['methods'])
        self.assertIn('captureDisplay(Landroid/window/ScreenCaptureInternal$DisplayCaptureArgs;)Landroid/window/ScreenCaptureInternal$ScreenshotHardwareBuffer;',framework[root]['methods'])
        self.assertIn('setSize(II)Landroid/window/ScreenCaptureInternal$DisplayCaptureArgs$Builder;',framework[root+'$DisplayCaptureArgs$Builder']['methods'])
        self.assertIn('captureDisplay(ILandroid/window/ScreenCaptureInternal$CaptureArgs;Landroid/window/ScreenCaptureInternal$ScreenCaptureListener;)V',framework['android.view.IWindowManager']['methods'])

    def test_apk_integrity_and_xposed_metadata(self):
        with zipfile.ZipFile(args.module) as z:
            self.assertIsNone(z.testzip())
            self.assertEqual(z.read('META-INF/xposed/java_init.list').decode().strip(),'com.coloros16.liquidglass.entry.XposedEntry')
            scopes={x.strip() for x in z.read('META-INF/xposed/scope.list').decode().splitlines() if x.strip() and not x.startswith('#')}
            self.assertEqual(scopes,{'com.android.systemui','com.android.launcher'})
            self.assertIn('minApiVersion=102',z.read('META-INF/xposed/module.prop').decode())
            for n in z.namelist():
                if re.fullmatch(r'classes\d*\.dex',n):
                    b=z.read(n);self.assertEqual(hashlib.sha1(b[32:]).digest(),b[12:32])

if __name__=='__main__':unittest.main(argv=[sys.argv[0]],verbosity=2)
