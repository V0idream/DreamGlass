package com.dreamglass.cos17;
import com.coloros16.liquidglass.hook.BackgroundCadence;
import com.coloros16.liquidglass.hook.CosCompatibility;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackgroundCadenceTest {
 @Test public void backgroundMotionBoostsWithoutGesture() {
  BackgroundCadence c=new BackgroundCadence();c.observe(1000,true);
  assertEquals(33,c.intervalMs(1100,60,false,false));
  for(int i=0;i<6;i++)c.observe(1400+i,false);
  assertEquals(167,c.intervalMs(1600,60,false,false));
  c.observe(1700,true);assertEquals(33,c.intervalMs(1710,60,false,false));
 }
 @Test public void explicitEventAndImmediateModeRemainResponsive() {
  BackgroundCadence c=new BackgroundCadence();for(int i=0;i<5;i++)c.observe(i,false);
  c.reveal(1000);assertEquals(33,c.intervalMs(1010,60,false,false));
  for(int i=0;i<5;i++)c.observe(1200+i,false);
  assertEquals(167,c.intervalMs(1300,60,false,false));
  assertEquals(16,c.intervalMs(1400,120,false,true));
  assertEquals(8,c.intervalMs(1400,120,true,true));
 }
 @Test public void cos17AndUnknownVolumeNeverUseCos16PostDrawOverrides() {
  assertTrue(CosCompatibility.INSTANCE.supportsLegacyVolumeOverrides("16.99.12"));
  assertFalse(CosCompatibility.INSTANCE.supportsLegacyVolumeOverrides("17.99.02"));
  assertFalse(CosCompatibility.INSTANCE.supportsLegacyVolumeOverrides(null));
  assertFalse(CosCompatibility.INSTANCE.supportsLegacyVolumeOverrides("160.0"));
 }
 @Test public void repeatedNativeNotificationsCannotKeepAnUnchangedBackdropAtFullRate() {
  BackgroundCadence c=new BackgroundCadence();for(int i=0;i<8;i++)c.observe(i,false);
  for(int i=1000;i<1100;i++)c.event(i);
  assertEquals(167,c.intervalMs(1100,60,false,false));
  c.reveal(1101);assertEquals(33,c.intervalMs(1102,60,false,false));
 }
}
