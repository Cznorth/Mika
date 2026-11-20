package com.wangzan101.mika.cap.phone.shell

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.File

/** SSH-only root console using the same isolated service as Mika. No socket or main-screen input. */
object IsolatedSessionMain {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 0) { "root required" }
        Looper.prepareMainLooper()
        // app_process does not initialize this Android 16 singleton.
        val memory = Class.forName("com.android.internal.os.ApplicationSharedMemory")
        runCatching { memory.getMethod("getInstance").invoke(null) }.onFailure {
            memory.getMethod("setInstance", memory).invoke(null, memory.getMethod("create").invoke(null))
        }
        val context = ShellContext.create()
        val service = ShellService(0)
        val reader = ImageReader.newInstance(480, 800, PixelFormat.RGBA_8888, 3)
        val frames = HandlerThread("mika-session-frames").apply { start() }
        val lock = Any()
        var bitmap: Bitmap? = null
        var lastFrame = 0L
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastFrame >= 300) {
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (cropped !== padded) padded.recycle()
                    synchronized(lock) { bitmap?.recycle(); bitmap = cropped }
                    lastFrame = now
                }
            } finally { image.close() }
        }, Handler(frames.looper))
        var display = service.createDisplay(reader.surface, 480, 800, 240)
        check(display > 0) { "isolated display unavailable" }
        fun reply(value: JSONObject) { println(value.toString()); System.out.flush() }
        fun guard() {
            fun dump(vararg cmd: String): String {
                val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "device state unreadable" }
                return output
            }
            check(!Regex("mCallState=[12]").containsMatchIn(dump("/system/bin/dumpsys", "telephony.registry"))) { "call active" }
            val main = dump("/system/bin/dumpsys", "activity", "activities").substringAfter("Display #0").substringBefore("\nDisplay #")
            val pkg = Regex("topResumedActivity=.*? u\\d+ ([^/ ]+)/").find(main)?.groupValues?.get(1)
            if (pkg != null) {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                check(info.category != ApplicationInfo.CATEGORY_GAME && info.flags and ApplicationInfo.FLAG_IS_GAME == 0) { "game active" }
            }
        }
        reply(JSONObject().put("ready", true).put("display", display))
        try {
            for (line in generateSequence(::readLine)) {
                try {
                    val q = JSONObject(line)
                    val op = q.getString("op")
                    if (op !in listOf("close", "exit", "dead_probe")) guard()
                    val out = JSONObject().put("op", op).put("display", display)
                    when (op) {
                        "launch" -> out.put("code", service.startApp(display, q.getString("package")))
                        "open" -> out.put("code", service.startView(display, null, q.getString("url"), q.getString("package")))
                        "tap" -> out.put("ok", service.injectTap(display, q.getInt("x"), q.getInt("y")))
                        "swipe" -> out.put("ok", service.injectStroke(display,
                            intArrayOf(q.getInt("x1"), q.getInt("x2")),
                            intArrayOf(q.getInt("y1"), q.getInt("y2")), longArrayOf(0, 500)))
                        "shot" -> {
                            Thread.sleep(q.optLong("wait_ms", 1200).coerceIn(0, 8000))
                            val file = File("/data/local/tmp/mika-isolated-session.png")
                            synchronized(lock) {
                                val frame = bitmap ?: error("no frame")
                                file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                            out.put("path", file.path).put("top", service.topActivity(display))
                        }
                        "status" -> out.put("top", service.topActivity(display)).put("task", service.topTaskId(display))
                        "close" -> { service.releaseDisplay(display); out.put("closed", true) }
                        "dead_probe" -> out.put("code", service.startApp(q.getInt("display"), q.getString("package")))
                        "exit" -> { reply(out.put("ok", true)); break }
                        else -> error("unsupported operation")
                    }
                    reply(out)
                } catch (t: Throwable) { reply(JSONObject().put("error", t.javaClass.simpleName).put("message", t.message)) }
            }
        } finally {
            service.releaseAll()
            reader.close()
            frames.quitSafely()
            synchronized(lock) { bitmap?.recycle() }
        }
        kotlin.system.exitProcess(0)
    }
}
