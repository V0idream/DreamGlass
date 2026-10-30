package com.dreamglass.cos17;

import android.content.Context;
import android.view.View;
import com.coloros16.liquidglass.hook.RecentsParticleContract;
import com.coloros16.liquidglass.hook.RecentsParticleHook;
import com.coloros16.liquidglass.hook.ParticleDissolveHook;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecentsParticleContractTest {
 public static class Playback {
  public void startWithVelocity(Context c,boolean toEnd,float velocity,float distance,long duration) {}
 }
 public static class ChangedPlayback {
  public void startWithVelocity(Context c,float velocity,boolean toEnd,float distance,long duration) {}
 }
 public static class Batch {}
 public static class Recents {
  public void dismissTask(View task,boolean animate,boolean remove) {}
  public void dismissTask(int unrelatedId) {}
  public void dismissAllTasks(View clearButton) {}
  public Batch createTaskDismissAnimation(View task,boolean animate,boolean remove,long duration,boolean split) {return null;}
  public void addDismissedTaskAnimations(View task,long duration,Batch batch) {}
  public void removeView(View unrelated) {}
 }
 @Test public void onlyConfirmedUpwardDeleteEmitsParticles() {
  assertTrue(RecentsParticleContract.INSTANCE.confirmed(true,true));
  assertFalse(RecentsParticleContract.INSTANCE.confirmed(true,false));
  assertFalse(RecentsParticleContract.INSTANCE.confirmed(false,true));
  assertFalse(RecentsParticleContract.INSTANCE.confirmed(false,false));
 }
 @Test public void changedPlaybackShapeDoesNotGuessTheCommitFlag() {
  assertNotNull(RecentsParticleContract.INSTANCE.velocity(Playback.class));
  assertNull(RecentsParticleContract.INSTANCE.velocity(ChangedPlayback.class));
 }
 @Test public void nativeBatchSignaturesExcludeGenericViewRemovalAndIdDismissal() {
  assertEquals(4,RecentsParticleContract.INSTANCE.routes(Recents.class,View.class,Batch.class).size());
 }
 @Test public void recentsSettingIsIndependentAndWorkIsBounded() {
  assertNotEquals(ParticleDissolveHook.KEY_ENABLED,RecentsParticleHook.KEY_ENABLED);
  assertNotEquals(ParticleDissolveHook.KEY_SPEED,RecentsParticleHook.KEY_SPEED);
  assertEquals(2,RecentsParticleHook.MAX_ACTIVE);
 }
}
