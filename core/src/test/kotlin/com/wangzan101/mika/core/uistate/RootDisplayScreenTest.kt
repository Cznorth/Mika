package com.wangzan101.mika.core.uistate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class RootDisplayScreenTest {
 @Test fun openAndUncertainInputRequireNewDisplayFrame() = runTest {
  val t=RootDisplayTarget(2,"session",8,"app"); var now=10L; var stamp=11L
  val d=object:RootDisplayDriver {
   override suspend fun target()=t
   override suspend fun capture(displayId:Int)=stamp to byteArrayOf(1)
   override suspend fun dispatch(target:RootDisplayTarget,input:VisualInput)=VisualDispatch.Uncertain
   override fun nowNanos()=now
  }
  val s=RootDisplayScreen(RootDisplayBackend(d),d,{true},{emptyList()},100,100)
  assertTrue(s.openApp("app") is ActionResult.Ok); now=12; assertNotNull(s.screenshotPng())
  assertEquals(ActionResult.Failed("input_unconfirmed"),s.tapXY(3,4)); assertNull(s.screenshotPng())
  stamp=13; now=14; assertNotNull(s.screenshotPng())
 }
}
