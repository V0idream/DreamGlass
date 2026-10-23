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

    def test_systemui_lifecycle_owner_and_content_draw_bridge(self):
        app=system['com.android.systemui.application.impl.SystemUIApplicationImpl']
        self.assertIn('onCreate()V',app['methods'])
        base=system['com.oplus.posteffect.drawable.BaseDrawable']
        self.assertIn('getPaint()Landroid/graphics/Paint;',base['methods'])
        self.assertIn('drawBlurShader(Landroid/graphics/Canvas;)V',base['methods'])
        blend=system['com.oplus.posteffect.drawable.BlendDrawable']
        self.assertIn('onDrawContent(Landroid/graphics/Canvas;)V',blend['methods'])
        for cls in ['hook.SystemUiLifecycle','hook.SystemUiInstallGate','hook.ContentDrawBridge']:
            self.assertIn('com.coloros16.liquidglass.'+cls,module)
        # Host fixtures are for JVM tests only, never ship replacement SystemUI classes.
        self.assertNotIn('com.android.systemui.application.impl.SystemUIApplicationImpl',module)
        self.assertNotIn('com.android.systemui.SystemUIApplication',module)

    def test_volume_mask_host_contract(self):
        wrapper=system['com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable']
        self.assertIn('getHostViewName:Lkotlin/jvm/functions/Function0;',wrapper['fields'])
        self.assertIn('blurMaskColor:I',wrapper['fields'])
        self.assertIn('draw(Landroid/graphics/Canvas;)V',wrapper['methods'])
        helper=system['com.oplusos.systemui.common.blurability.platformblur.PlatformBlurHelper']
        self.assertIn('hostView:Landroid/view/View;',helper['fields'])
        proxy=system['com.oplusos.systemui.common.blurability.ViewBlurProxy']
        self.assertIn('getView()Landroid/view/View;',proxy['methods'])
        self.assertIn('getBlurDrawable(Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;',proxy['methods'])
        self.assertIn('com.coloros16.liquidglass.hook.VolumeMaskGuard',module)
        guard=module['com.coloros16.liquidglass.hook.VolumeMaskGuard']
        self.assertIn('protectNativeContent(Ljava/lang/Object;Ljava/lang/Object;)V',guard['methods'])
        self.assertIn('preservesContent(Ljava/lang/Object;)Z',guard['methods'])

    def test_cos17_seekbar_factory_and_actual_render_target(self):
        manager=system['com.oplus.systemui.qs.base.util.QsSeekBarBlurManager']
        args='(Landroid/view/View;Landroid/graphics/Rect;FFZZF)Landroid/graphics/drawable/Drawable;'
        self.assertIn('getSeekBarActiveDrawable'+args,manager['methods'])
        self.assertIn('getSeekBarBackground'+args,manager['methods'])
        self.assertNotIn('getSeekBarActiveDrawable(Landroid/view/View;)Landroid/graphics/drawable/Drawable;',manager['methods'])
        # The factory wrapper is NOT drawBlurShader's receiver. Verify the full unwrapping chain.
        auto=system['com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable']
        proxy=system['com.oplusos.systemui.common.blurability.ViewBlurProxy']
        platform=system['com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable']
        self.assertIn('getViewBlurProxy()Lcom/oplusos/systemui/common/blurability/ViewBlurProxy;',auto['methods'])
        self.assertIn('getBlurDrawable(Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;',proxy['methods'])
        self.assertIn('getBlurDrawable()Lcom/oplus/posteffect/BlurDrawable;',platform['methods'])
        name='com.oplus.posteffect.BlurDrawable'
        while name != 'com.oplus.posteffect.drawable.BaseDrawable':
            name=system[name]['super'][1:-1].replace('/','.')
        self.assertIn('com.coloros16.liquidglass.hook.QsSeekbarCompat',module)

    def test_qs_surface_shapes_and_icon_background_contract(self):
        slider=system['com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar']
        self.assertIn('drawActiveMixColorTrack(Landroid/graphics/Canvas;)V',slider['methods'])
        parent=system['com.coui.appcompat.seekbar.COUIVerticalSeekBar']
        for field in ['mClipProgressRect:Landroid/graphics/Rect;','mCurProgressRadius:F','mBackgroundRoundCornerWeight:F']:
            self.assertIn(field,parent['fields'])
        smooth=system['com.oplus.posteffect.util.OplusPathAdapterCompatUtils']
        self.assertIn('create(Landroid/graphics/Path;)Ljava/lang/Object;',smooth['methods'])
        self.assertIn('addSmoothRoundRect(Ljava/lang/Object;Landroid/graphics/RectF;[FLandroid/graphics/Path$Direction;F)Z',smooth['methods'])
        icon=system['com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView']
        self.assertIn('iconBgView:Landroid/widget/ImageView;',icon['fields'])
        for method in ['onAttachedToWindow()V','onLayout(ZIIII)V','updateIconBgDrawable()V']:
            self.assertIn(method,icon['methods'])
        tile=system['com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne']
        self.assertIn('com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable',system)
        self.assertIn('draw(Landroid/graphics/Canvas;)V',system['com.oplus.systemui.qs.base.res.drawable.TileTransitionDrawable']['methods'])
        for name in ['hook.QsSurfaceHook','hook.QsSurfacePolicy']:
            self.assertIn('com.coloros16.liquidglass.'+name,module)

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
