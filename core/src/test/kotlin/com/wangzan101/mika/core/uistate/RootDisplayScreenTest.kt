package com.wangzan101.mika.core.uistate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class RootDisplayScreenTest {
 @Test fun aBlockedLaunchKeepsItsReasonAndNeverReadsAFrame() = runTest {
  val driver=object:RootDisplayDriver {
   override suspend fun target(): RootDisplayTarget? = error("must not inspect a blocked launch")
   override suspend fun capture(displayId:Int): Pair<Long,ByteArray>? = error("must not capture a blocked launch")
   override suspend fun dispatch(target:RootDisplayTarget,input:VisualInput)=VisualDispatch.NotSent
   override fun nowNanos()=10L
  }
  val blocked=ActionResult.Failed("app_in_use_by_user", "Close the app from Recents")
  val screen=RootDisplayScreen(RootDisplayBackend(driver),driver,{blocked},{emptyList()},100,100)
  assertEquals(blocked,screen.openApp("app")); assertNull(screen.screenshotPng())
 }
 @Test fun openAndUncertainInputRequireNewDisplayFrame() = runTest {
  val t=RootDisplayTarget(2,"session",8,"app"); var now=10L; var stamp=11L
  val d=object:RootDisplayDriver {
   override suspend fun target()=t
   override suspend fun capture(displayId:Int)=stamp to byteArrayOf(1)
   override suspend fun dispatch(target:RootDisplayTarget,input:VisualInput)=VisualDispatch.Uncertain
   override fun nowNanos()=now
  }
  val s=RootDisplayScreen(RootDisplayBackend(d),d,{ActionResult.Ok()},{emptyList()},100,100)
  assertTrue(s.openApp("app") is ActionResult.Ok); now=12; assertNotNull(s.screenshotPng())
  assertEquals(ActionResult.Failed("input_unconfirmed"),s.tapXY(3,4)); assertNull(s.screenshotPng())
  stamp=13; now=14; assertNotNull(s.screenshotPng())
 }
}
