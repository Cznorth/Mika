package com.wangzan101.mika.task

import android.util.Log
import com.wangzan101.mika.core.uistate.RootVisualBackend
import com.wangzan101.mika.core.uistate.RootVisualScreen

/** A backend is supplied only when capture and input are bound to the same window identity. */
internal class RootWindowUnavailable : Exception()

internal suspend fun connectRootPhone(
    width: Int,
    height: Int,
    initialize: suspend () -> Unit,
    backend: suspend () -> RootVisualBackend?,
    onConnected: suspend () -> Unit = {},
): RootVisualScreen {
    initialize()
    val verifiedBackend = backend() ?: throw RootWindowUnavailable()
    onConnected()
    return RootVisualScreen(verifiedBackend, width, height)
}

internal class RootDisplayDisabled : Exception()

/** The display session is scoped to the task; caller owns and releases its resources. */
internal suspend fun connectRootDisplayPhone(
    initialize: suspend () -> Unit,
    create: suspend () -> com.wangzan101.mika.core.uistate.RootDisplayScreen,
    onConnected: suspend () -> Unit,
    enabled: Boolean = true,
): com.wangzan101.mika.core.uistate.RootDisplayScreen {
    Log.i("MikaRootDisplay", "connect enabled=$enabled")
    if (!enabled) throw RootDisplayDisabled()
    initialize()
    Log.i("MikaRootDisplay", "initialize complete; creating display backend")
    onConnected()
    return runCatching { create() }.onSuccess {
        Log.i("MikaRootDisplay", "RootDisplayScreen created")
    }.onFailure {
        Log.e("MikaRootDisplay", "RootDisplayScreen creation failed", it)
    }.getOrThrow()
}
