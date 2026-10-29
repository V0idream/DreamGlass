package com.dreamglass.cos17;
import org.junit.Test;
import com.coloros16.liquidglass.hook.PerClassCache;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class PerClassCacheTest {
 @Test public void falseDecisionsAreCachedPerClassOnApi33CompatibleMap() {
  AtomicInteger calls=new AtomicInteger();
  PerClassCache<Boolean> cache=new PerClassCache<Boolean>() {
   @Override protected Boolean computeValue(Class<?> c) { calls.incrementAndGet();return c==Integer.class; }
  };
  assertFalse(cache.get(String.class));assertFalse(cache.get(String.class));
  assertTrue(cache.get(Integer.class));assertTrue(cache.get(Integer.class));
  assertEquals(2,calls.get());
 }
}
