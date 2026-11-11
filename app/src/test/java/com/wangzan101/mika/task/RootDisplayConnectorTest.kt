package com.wangzan101.mika.task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class RootDisplayConnectorTest {
 @Test fun deathListenerFailurePreventsResourceCreation() = runBlocking {
  var created=false; var initialized=false
  try { connectRootDisplayPhone({initialized=true}, {created=true; error("unexpected")}, {error("dead")}); fail() }
  catch (_:IllegalStateException) { }
  assertTrue(initialized); assertFalse(created)
 }
 @Test fun initializationFailurePreventsDeathListener() = runBlocking {
  var connected=false
  try { connectRootDisplayPhone({error("init")}, {error("unexpected")}, {connected=true}); fail() }
  catch (_:IllegalStateException) { }
  assertFalse(connected)
 }
 @Test fun failureAfterDisplayAcquisitionReleasesOnce() = runBlocking {
  val scope = com.wangzan101.mika.core.resource.ResourceScope()
  var released = 0
  try {
   connectRootDisplayPhone({}, {
    scope.acquire(kotlinx.coroutines.Dispatchers.IO, { _: Any -> released++ }) { Any() }
    error("screen construction failed")
   }, {})
   fail("expected construction failure")
  } catch (e: IllegalStateException) { assertEquals("screen construction failed", e.message) }
  finally { scope.close(); scope.close() }
  assertEquals(1, released)
 }
 @Test fun disabledBackendDoesNotInitializeOrAllocate() = runBlocking {
  var touched = false
  try {
   connectRootDisplayPhone({ touched = true }, { touched = true; error("unexpected") }, { touched = true }, enabled = false)
   fail("disabled backend should fail")
  } catch (_: RootDisplayDisabled) { }
  assertFalse(touched)
 }
}
