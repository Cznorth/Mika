package com.wangzan101.mika.task

import android.content.Context
import android.util.Log
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 谁需要 Mika 在后台一直醒着、连着网:任务在跑([REASON_TASK])、电脑连接开着([REASON_COMPUTER],电脑随时会派任务、查状态)。
 * 有任何一个就请 root 身份的内置助手维持 BackgroundGuard(ColorOS 的 Hans 熄屏或退到后台后会把 Mika 断网、冻住、清掉),
 * 一个都没有就撤。助手换了(重连)时在新的那个上重新开;助手那边有时长上限,需要期间每 [RENEW_MS] 再请一次。
 * 助手不是 root 或是旧版本时什么也不做。进程里只有一份。
 */
object KeepAlive {
    const val REASON_TASK = "task"
    const val REASON_COMPUTER = "computer"
    private const val RENEW_MS = 30 * 60 * 1000L
    private const val TAG = "MikaKeepAlive"

    private val reasons = MutableStateFlow<Set<String>>(emptySet())
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun set(context: Context, reason: String, on: Boolean) {
        follow(context)
        reasons.update { if (on) it + reason else it - reason }
    }

    private fun follow(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val phone = runCatching { PhoneServices.of(context) }.getOrNull() ?: return
        val hub = phone.hub
        val pkg = context.packageName
        val renew = flow { var n = 0; while (true) { emit(n++); delay(RENEW_MS) } }
        scope.launch {
            var on: IMikaShell? = null
            combine(reasons.map { it.isNotEmpty() }.distinctUntilChanged(), hub.shell, renew) { wanted, shell, round ->
                // 要撑着却没有助手(电脑连接刚打开、还没有任务用过手机):照常请看门狗起一个,起来了这里会再走一遍
                if (wanted && shell == null) runCatching { phone.shellManager.need() }
                (if (wanted) shell else null) to round
            }.collect { (want, _) ->
                if (want !== on) on?.let { old -> runCatching { old.keepTaskAlive(pkg, false) } }
                // 同一个助手上再请一次也无害:还在维持就直接答应,到了上限停掉的会重新开
                on = want?.takeIf { s ->
                    val code = runCatching { s.keepTaskAlive(pkg, true) }.getOrDefault(-1)
                    if (want !== on) Log.i(TAG, "background guard on=$code reasons=${reasons.value}")
                    code == 0
                }
            }
        }
    }
}
