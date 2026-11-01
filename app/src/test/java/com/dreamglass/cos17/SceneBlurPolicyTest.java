package com.dreamglass.cos17;
import com.coloros16.liquidglass.hook.SceneBlurPolicy;
import org.junit.Test;
import static org.junit.Assert.*;
public class SceneBlurPolicyTest {
 @Test public void fullNativeAndNoBlurEndpoints() {
  assertEquals(300,SceneBlurPolicy.INSTANCE.radius(300,100));
  assertEquals(0,SceneBlurPolicy.INSTANCE.radius(300,0));
  assertEquals(0,SceneBlurPolicy.INSTANCE.radius(0,70));
 }
 @Test public void fadeOutCannotAccumulateScaling() {
  for(int nativeRadius:new int[]{300,180,40,1,0}) {
   int expected=(int)(nativeRadius*.3);
   for(int i=0;i<60;i++)assertEquals(expected,SceneBlurPolicy.INSTANCE.radius(nativeRadius,30));
  }
 }
 @Test public void noOverBlurOrInvalidGpuRadius() {
  assertEquals(300,SceneBlurPolicy.INSTANCE.radius(300,200));
  assertEquals(0,SceneBlurPolicy.INSTANCE.radius(Float.NaN,30),0);
  assertEquals(11.1f,SceneBlurPolicy.INSTANCE.radius(37f,30),.001f);
 }
 @Test public void menuScopeExcludesLockscreenAndMainShade() {
  assertTrue(SceneBlurPolicy.INSTANCE.menuRoot("com.oplus.systemui.statusbar.notification.row.menupanel.NotificationMenuPanelView"));
  assertTrue(SceneBlurPolicy.INSTANCE.menuRoot("com.coui.appcompat.poplist.COUIPopupMenuRootView"));
  for(String other:new String[]{"com.android.systemui.shade.NotificationShadeWindowView","com.android.keyguard.KeyguardRootView","android.widget.FrameLayout"})
   assertFalse(SceneBlurPolicy.INSTANCE.menuRoot(other));
 }
}
