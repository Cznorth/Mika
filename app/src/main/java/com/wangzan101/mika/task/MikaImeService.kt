package com.wangzan101.mika.task

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.shell.IMikaShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 锁屏时系统不让任何 App 读剪贴板,中文粘不进去。这时临时把输入法切成 Mika 自己的这个(没有键盘界面),
 * 经输入法通道把文字直接提交给 Mika 虚拟屏上有焦点的编辑框,提交完立即切回用户原来的输入法。
 * 不在提交中途被选上(进程被杀后残留、用户误选)时,一开始输入就自己切回上一个输入法。
 */
class MikaImeService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View? = null
    override fun onEvaluateFullscreenMode() = false
    override fun onShowInputRequested(flags: Int, configChange: Boolean) = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!active.get()) {
            Log.i(TAG, "selected outside a commit; switching back")
            runCatching { switchToPreviousInputMethod() }
        }
    }

    /** 在主线程上把 [text] 提交给 [pkg] 的编辑框;不是它的输入连接时返回 false。 */
    private fun commitOnMain(pkg: String, text: String): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            ok = runCatching {
                val ic = currentInputConnection ?: return@runCatching false
                if (currentInputEditorInfo?.packageName != pkg) return@runCatching false
                ic.beginBatchEdit()
                try { ic.commitText(text, 1) } finally { ic.endBatchEdit() }
            }.getOrDefault(false)
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun ready(pkg: String) =
        currentInputConnection != null && currentInputEditorInfo?.packageName == pkg

    companion object {
        private const val TAG = "MikaIme"
        private const val BIND_WAIT_MS = 4_000L
        @Volatile private var current: MikaImeService? = null
        private val active = AtomicBoolean(false)
        private val lock = Any()

        private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /**
         * 经 root 助手切到 Mika 输入法,把 [text] 提交给 [pkg] 当前聚焦的编辑框,再切回。
         * 返回 [AsciiTextKeys] 的取值:没提交出去为 NOT_SENT,已提交为 DISPATCHED。
         */
        fun type(shell: IMikaShell, appId: String, pkg: String, text: String): Int = synchronized(lock) {
            val id = "$appId/${MikaImeService::class.java.name}"
            val prev = shell.runCommand("settings get secure default_input_method")
                .getString("output").orEmpty().trim()
            if (prev.isEmpty() || prev == "null") return AsciiTextKeys.NOT_SENT
            val wasEnabled = shell.runCommand("settings get secure enabled_input_methods")
                .getString("output").orEmpty().split(':').any { it.trim() == id }
            active.set(true)
            try {
                val set = shell.runCommand("ime enable ${quote(id)} >/dev/null 2>&1; ime set ${quote(id)}")
                if (set.getInt("exitCode") != 0) {
                    Log.w(TAG, "ime set failed: ${set.getString("output")}")
                    return AsciiTextKeys.NOT_SENT
                }
                val deadline = SystemClock.uptimeMillis() + BIND_WAIT_MS
                while (current?.ready(pkg) != true && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                val ime = current?.takeIf { it.ready(pkg) } ?: run {
                    Log.w(TAG, "no input connection to $pkg")
                    return AsciiTextKeys.NOT_SENT
                }
                return if (ime.commitOnMain(pkg, text)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.NOT_SENT
            } finally {
                active.set(false)
                val back = "ime set ${quote(prev)}" + if (wasEnabled) "" else "; ime disable ${quote(id)} >/dev/null 2>&1"
                runCatching { shell.runCommand(back) }.onFailure { Log.w(TAG, "restore ime failed", it) }
            }
        }
    }
}
