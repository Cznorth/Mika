package com.wangzan101.mika.core.uistate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class RootDisplayBackendTest {
 private class Driver : RootDisplayDriver {
  var current = RootDisplayTarget(3, "session", 9, "example"); var time = 100L; var stamp = 90L; var calls = 0; var change = false
  override suspend fun target() = current
  override suspend fun capture(displayId: Int): Pair<Long, ByteArray> { if (change) current = current.copy(session = "replacement"); return stamp to byteArrayOf(1) }
  override suspend fun dispatch(target: RootDisplayTarget, input: VisualInput): VisualDispatch { calls++; return VisualDispatch.Uncertain }
  override fun nowNanos() = time
 }
 @Test fun rejectsPhysicalDisplayAndChangedSession() = runTest { val d=Driver(); val b=RootDisplayBackend(d); val t=d.current
  assertEquals(VisualDispatch.NotSent,b.dispatch(t.copy(displayId=0),VisualInput.Tap(0,0))); d.change=true; assertNull(b.frame(t)); assertEquals(0,d.calls) }
 @Test fun uncertainInputInvalidatesOldAndFutureFrames() = runTest { val d=Driver(); val b=RootDisplayBackend(d); val t=d.current
  assertNotNull(b.frame(t)); assertEquals(VisualDispatch.Uncertain,b.dispatch(t,VisualInput.Tap(1,2))); assertNull(b.frame(t)); d.stamp=102; assertNull(b.frame(t)); d.time=103; assertNotNull(b.frame(t)) }
}
