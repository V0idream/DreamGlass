package com.dreamglass.cos17;
import org.junit.Test;
import static org.junit.Assert.*;
import kotlin.Unit;
import com.coloros16.liquidglass.hook.SnapshotFingerprint;
public class SnapshotFingerprintTest {
 private int hash(int[] pixels) {
  return SnapshotFingerprint.INSTANCE.hash(192,192,(y,row)-> { System.arraycopy(pixels,y*192,row,0,192);return Unit.INSTANCE; });
 }
 @Test public void detectsLocalChangeBetweenOldGridSamples() {
  int[] pixels=new int[192*192];java.util.Arrays.fill(pixels,0xff102030);
  int before=hash(pixels);pixels[2*192+2]=0xffffffff;
  assertNotEquals(before,hash(pixels));
 }
 @Test public void repeatedBackgroundHasStableFingerprintWithoutMutation() {
  int[] pixels=new int[192*192];for(int i=0;i<pixels.length;i++)pixels[i]=i;
  int[] copy=pixels.clone();assertEquals(hash(pixels),hash(pixels));assertArrayEquals(copy,pixels);
 }
}
