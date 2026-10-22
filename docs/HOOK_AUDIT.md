# Hook registration audit

Generated from source registration functions and the supplied APK DEX definitions. This is static evidence only. Android/framework classes may be supplied outside the APK. A method name match is not a signature or runtime pass. Dynamic lookups are explicitly retained as unresolved.

## BlurCaptureHook.kt

### mountOnBindAnchor — BlurCaptureHook.kt:166

- Classes: com.oplus.blur.BlurService: not defined in supplied APKs / external or dynamic
- Methods: `onBind`
- Registration evidence: source only; device invocation and final pixels pending.

### mountFallback — BlurCaptureHook.kt:237

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountFrameScaleHooks — BlurCaptureHook.kt:741

- Classes: e.a: SystemLauncher
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

## BlurDrawHook.kt

### mountAutoBlurDrawable — BlurDrawHook.kt:2916

- Classes: com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountLightStyleStrokeDrawable — BlurDrawHook.kt:2985

- Classes: com.oplus.systemui.qs.base.widget.LightStyleStrokeDrawable: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountMaskBlurDrawable — BlurDrawHook.kt:3016

- Classes: com.oplusos.systemui.common.blurability.drawable.MaskBlurDrawable: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountCapsuleBackground — BlurDrawHook.kt:3059

- Classes: com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundHelper: SystemUI
- Methods: `drawCapsuleBg`
- Registration evidence: source only; device invocation and final pixels pending.

### mountCapsuleEar — BlurDrawHook.kt:3119

- Classes: com.oplus.systemui.notification.lockscreen.capsule.CapsuleEarView: SystemUI
- Methods: `onDraw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountMediaCarousel — BlurDrawHook.kt:3166

- Classes: com.oplus.systemui.media.controls.ui.OplusMediaCarouselView: SystemUI
- Methods: `onDraw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountMixColorTileRegion — BlurDrawHook.kt:4071

- Classes: com.oplus.systemui.qs.base.res.drawable.MixColorTileDrawable: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountNotifHeaderMaskColor — BlurDrawHook.kt:4293

- Classes: com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp$NotificationHeaderMaskColorDrawable: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountMetaColorSuppress — BlurDrawHook.kt:4356

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountNotificationBackground — BlurDrawHook.kt:4799

- Classes: com.oplus.systemui.statusbar.notification.row.NotificationBackgroundViewExtImp: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountPlatformBlurMaskColor — BlurDrawHook.kt:4923

- Classes: com.oplusos.systemui.common.blurability.platformblur.PlatformBlurDrawable: SystemUI
- Methods: `draw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountMotionSources — BlurDrawHook.kt:5144

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountShadeRootAttach — BlurDrawHook.kt:5169

- Classes: com.android.systemui.shade.NotificationShadeWindowView: SystemUI
- Methods: `onAttachedToWindow`, `onDetachedFromWindow`
- Registration evidence: source only; device invocation and final pixels pending.

### mountQsPageScroll — BlurDrawHook.kt:5234

- Classes: com.android.systemui.qs.PagedTileLayout$2: not defined in supplied APKs / external or dynamic; com.android.systemui.qs.deprecated.PagedTileLayout$2: SystemUI
- Methods: `onPageScrolled`
- Registration evidence: source only; device invocation and final pixels pending.

### mountPanelExpansion — BlurDrawHook.kt:5280

- Classes: com.android.systemui.shade.QuickSettingsControllerImpl: SystemUI
- Methods: `setExpansionHeight`
- Registration evidence: source only; device invocation and final pixels pending.

### mountCustomRowTranslation — BlurDrawHook.kt:5406

- Classes: com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow: SystemUI
- Methods: `setTranslation`
- Registration evidence: source only; device invocation and final pixels pending.

### mountNotificationTilt — BlurDrawHook.kt:5518

- Classes: com.android.systemui.statusbar.notification.row.ExpandableView: SystemUI; com.oplus.systemui.notification.headsup.windowframe.HeadsUpContainerWindow: SystemUI; com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout: SystemUI; com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpAnimator: SystemUI
- Methods: `generateAddAnimation`, `animateTo`, `dismiss`, `getView`, `show`, `setTranslationY`
- Registration evidence: source only; device invocation and final pixels pending.

### mountContainerTransformHooks — BlurDrawHook.kt:6165

- Classes: com.android.systemui.shade.NotificationPanelView: SystemUI; com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout: SystemUI; com.oplus.systemui.plugins.qs.OplusQSRootView: SystemUI
- Methods: `setScaleX`, `setScaleY`, `setTranslationX`, `setTranslationY`
- Registration evidence: source only; device invocation and final pixels pending.

### mountViewBlurProxySetBlurType — BlurDrawHook.kt:6350

- Classes: com.oplusos.systemui.common.blurability.ViewBlurProxy: SystemUI
- Methods: `setBlurType`
- Registration evidence: source only; device invocation and final pixels pending.

### mountHeadsUpContainerIsHeadsUpView — BlurDrawHook.kt:6419

- Classes: com.oplus.systemui.notification.headsup.windowframe.HeadsUpContainerWindow: SystemUI
- Methods: `isHeadsUpView`
- Registration evidence: source only; device invocation and final pixels pending.

### mountSeedlingCardContainer — BlurDrawHook.kt:6464

- Classes: com.oplus.systemui.statusbar.seeding.CapsulePluginContainer: SystemUI; android.view.ViewGroup: not defined in supplied APKs / external or dynamic
- Methods: `dispatchDraw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountCardBackgroundGlass — BlurDrawHook.kt:6875

- Classes: com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView: not defined in supplied APKs / external or dynamic
- Methods: `onDraw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountSimpleBannerGlass — BlurDrawHook.kt:7109

- Classes: com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBanner: SystemUI; android.view.ViewGroup: not defined in supplied APKs / external or dynamic
- Methods: `dispatchDraw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountSimpleBannerAttachHook — BlurDrawHook.kt:7197

- Classes: com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBanner: SystemUI
- Methods: `onAttachedToWindow`
- Registration evidence: source only; device invocation and final pixels pending.

### mountCardBackgroundAttachHook — BlurDrawHook.kt:7261

- Classes: com.oplus.systemui.plugins.seedling.card.ui.view.CardBackgroundView: not defined in supplied APKs / external or dynamic
- Methods: `onAttachedToWindow`
- Registration evidence: source only; device invocation and final pixels pending.

### mountCcBlurHooks — BlurDrawHook.kt:7381

- Classes: com.oplusos.systemui.common.blurability.BlurConfig: SystemUI; com.oplusos.systemui.common.blurability.ViewBlurProxy: SystemUI
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountApplyPanelMirrorScale — BlurDrawHook.kt:7492

- Classes: com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt: SystemUI; com.oplusos.systemui.common.blurability.ViewBlurProxy: SystemUI
- Methods: `applyPanelMirrorScale`
- Registration evidence: source only; device invocation and final pixels pending.

### mountViewBlurProxyApplyBlurConfig — BlurDrawHook.kt:7531

- Classes: com.oplusos.systemui.common.blurability.ViewBlurProxy: SystemUI
- Methods: `applyConfigToPlatformBlur`
- Registration evidence: source only; device invocation and final pixels pending.

### mountOplusCustomRowGlass — BlurDrawHook.kt:7870

- Classes: com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow: SystemUI
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountQsColorUtil — BlurDrawHook.kt:8079

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountTextViewSetTextColor — BlurDrawHook.kt:8156

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountTextViewOnDraw — BlurDrawHook.kt:8253

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountOnBlurReady — BlurDrawHook.kt:9816

- Classes: com.oplus.posteffect.manager.BlurDrawableManager: SystemUI/SystemLauncher
- Methods: `onBlurReady`
- Registration evidence: source only; device invocation and final pixels pending.

### mountAddBlurDrawable — BlurDrawHook.kt:9891

- Classes: com.oplus.posteffect.drawable.ContinuousBlurDrawable: SystemUI
- Methods: `addBlurDrawable`, `getDrawableId`, `getDrawingParam`, `getSurfaceControl`
- Registration evidence: source only; device invocation and final pixels pending.

### mountQsSeekBarActiveDrawable — BlurDrawHook.kt:9975

- Classes: com.oplus.systemui.qs.base.util.QsSeekBarBlurManager: SystemUI
- Methods: `getSeekBarActiveDrawable`
- Registration evidence: source only; device invocation and final pixels pending.

### mountDrawBlurShader — BlurDrawHook.kt:10003

- Classes: com.oplus.posteffect.drawable.BaseDrawable: SystemUI
- Methods: `drawBlurShader`
- Registration evidence: source only; device invocation and final pixels pending.

### mountOnDrawContent — BlurDrawHook.kt:10093

- Classes: com.oplus.posteffect.drawable.BaseDrawable: SystemUI; com.oplus.posteffect.drawable.BlendDrawable: SystemUI
- Methods: `onDrawContent`
- Registration evidence: source only; device invocation and final pixels pending.

### mountLegacyDrawBlur — BlurDrawHook.kt:10135

- Classes: com.oplus.posteffect.drawable.BaseDrawable: SystemUI; com.oplus.posteffect.ForegroundBlurParam: SystemUI
- Methods: `drawBlur`
- Registration evidence: source only; device invocation and final pixels pending.

### mountScrimViewAlpha — BlurDrawHook.kt:13562

- Classes: com.android.systemui.scrim.ScrimView: SystemUI; com.android.systemui.scrim.ScrimViewEx: SystemUI; com.oplus.systemui.scrim.ScrimViewExImp: SystemUI
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountScrimAlphaHook — BlurDrawHook.kt:13573

- Classes: dynamic / inherited target
- Methods: `setViewAlpha`
- Registration evidence: source only; device invocation and final pixels pending.

### mountScrimGetViewAlphaHook — BlurDrawHook.kt:13606

- Classes: com.android.systemui.scrim.ScrimView: SystemUI
- Methods: `getViewAlpha`
- Registration evidence: source only; device invocation and final pixels pending.

## LauncherAnimHook.kt

### mountSetAnimParamByType — LauncherAnimHook.kt:150

- Classes: com.android.quickstep.util.animation.CustomRectFSpringAnim$AnimType: not defined in supplied APKs / external or dynamic; com.android.quickstep.util.animation.CustomRectFSpringAnim: SystemLauncher
- Methods: `setAnimParamByType`
- Registration evidence: source only; device invocation and final pixels pending.

### mountInitAllAnimations — LauncherAnimHook.kt:182

- Classes: com.android.quickstep.util.animation.CustomRectFSpringAnim: SystemLauncher
- Methods: `initAllAnimations`
- Registration evidence: source only; device invocation and final pixels pending.

### mountGetLauncherViewAnimParam — LauncherAnimHook.kt:210

- Classes: com.android.quickstep.util.animation.CustomRectFSpringAnim$AnimType: not defined in supplied APKs / external or dynamic; com.oplus.quickstep.anim.LauncherContentAnimManager: not defined in supplied APKs / external or dynamic
- Methods: `getLauncherViewAnimParam`
- Registration evidence: source only; device invocation and final pixels pending.

## LauncherHook.kt

### mountDrawBlurShader — LauncherHook.kt:1037

- Classes: com.oplus.posteffect.drawable.BaseDrawable: SystemUI
- Methods: `drawBlurShader`
- Registration evidence: source only; device invocation and final pixels pending.

### mountInlineRecordPathA — LauncherHook.kt:1085

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountPreviewBackground — LauncherHook.kt:1220

- Classes: com.android.launcher3.folder.OplusPreviewBackground: SystemLauncher
- Methods: `drawBackground`
- Registration evidence: source only; device invocation and final pixels pending.

### mountFolderRoundImageView — LauncherHook.kt:1276

- Classes: com.android.launcher3.folder.FolderRoundImageView: SystemLauncher
- Methods: `onDraw`
- Registration evidence: source only; device invocation and final pixels pending.

### mountDockGlass — LauncherHook.kt:1320

- Classes: com.android.launcher3.OplusHotseat: SystemLauncher; com.android.common.util.ScreenUtils: SystemLauncher
- Methods: `isSupportDockerExpandScreen`, `onDraw`, `setDockerBackground`
- Registration evidence: source only; device invocation and final pixels pending.

### mountOnBlurReady — LauncherHook.kt:1459

- Classes: com.oplus.posteffect.manager.BlurDrawableManager: SystemUI/SystemLauncher
- Methods: `onBlurReady`, `f`
- Registration evidence: source only; device invocation and final pixels pending.

### mountWallpaperOffsets — LauncherHook.kt:1518

- Classes: android.app.WallpaperManager: not defined in supplied APKs / external or dynamic
- Methods: `setWallpaperOffsets`
- Registration evidence: source only; device invocation and final pixels pending.

### mountWallpaperZoomCommand — LauncherHook.kt:1576

- Classes: android.app.WallpaperManager: not defined in supplied APKs / external or dynamic
- Methods: `sendWallpaperCommand`
- Registration evidence: source only; device invocation and final pixels pending.

## LauncherTiltHook.kt

### mountConfigureSites — LauncherTiltHook.kt:447

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountApplyLambdas — LauncherTiltHook.kt:519

- Classes: com.android.quickstep.util.animation.RectTransformHelper: SystemLauncher
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountIconLayerUpdater — LauncherTiltHook.kt:568

- Classes: com.android.quickstep.util.animation.IconLayerUpdater: not defined in supplied APKs / external or dynamic
- Methods: `updateIconLayer`
- Registration evidence: source only; device invocation and final pixels pending.

### mountLightWindowPath — LauncherTiltHook.kt:622

- Classes: com.android.launcher3.anim.light.AppLaunchAnimUtil$getLightWindowAnimation$1: SystemLauncher
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountBreakCarry — LauncherTiltHook.kt:750

- Classes: com.android.quickstep.util.animation.AnimationRecord: SystemLauncher; com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler: not defined in supplied APKs / external or dynamic; com.android.quickstep.util.animation.RectTransformHelper$TransformParams: SystemLauncher
- Methods: `createBreakAppOpenAnim`
- Registration evidence: source only; device invocation and final pixels pending.

## LockScreenClockHook.kt

### mountSetWallpaperBitmap — LockScreenClockHook.kt:207

- Classes: com.oplus.keyguard.plugin.ThemePlugin: not defined in supplied APKs / external or dynamic
- Methods: `setWallpaperBitmapInternal`
- Registration evidence: source only; device invocation and final pixels pending.

### mountRenderEffectBlocker — LockScreenClockHook.kt:245

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.

### mountViewDraw — LockScreenClockHook.kt:310

- Classes: dynamic / inherited target
- Methods: literal / structural / delegated resolution; see source
- Registration evidence: source only; device invocation and final pixels pending.
