package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.wangzan101.mika.core.shell.IsolatedKeyboardProbe
import java.io.FileDescriptor
import java.io.IOException
import java.util.UUID

/**
 * Root-helper-only experimental adapter. Nothing constructs this from production
 * tools. Callers must explicitly opt in and supply a Mika display created with
 * STEAL_TOP_FOCUS_DISABLED; that attestation is not inferred from screen lock.
 *
 * Based on the mechanism documented by scrcpy's UhidManager (Apache-2.0),
 * a60891aea193d92e7e5c3942700eca63f9d19a5f. No scrcpy runtime is started.
 */
internal object RootIsolatedKeyboardProbe {
    fun create(
        context: Context,
        displayId: Int,
        expectedUniqueId: String,
        focusIsolated: Boolean,
        enabled: Boolean = false,
    ): IsolatedKeyboardProbe {
        val port = "mika-probe:${Process.myPid()}:${UUID.randomUUID()}"
        val target = IsolatedKeyboardProbe.Target(Build.VERSION.SDK_INT, displayId, expectedUniqueId, focusIsolated)
        // Construction has no device/system-service side effects. Disabled start never calls the adapter.
        return IsolatedKeyboardProbe(target, AndroidIo(context, port), enabled)
    }

    private class AndroidIo(private val context: Context, private val port: String) : IsolatedKeyboardProbe.Io {
        private val deviceName = port
        private var fd: FileDescriptor? = null
        private var deviceId: Int? = null
        private val input by lazy { checkNotNull(context.getSystemService(InputManager::class.java)) }
        private val displays by lazy { checkNotNull(context.getSystemService(DisplayManager::class.java)) }

        override fun associate(uniqueId: String) {
            check(Process.myUid() == 0) { "The probe requires the root helper" }
            input.javaClass.getMethod("addUniqueIdAssociationByPort", String::class.java, String::class.java)
                .invoke(input, port, uniqueId)
        }

        override fun create() {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            write(IsolatedKeyboardProbe.createPacket(deviceName, port))
        }

        override fun awaitRouting(target: IsolatedKeyboardProbe.Target): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 1500
            do {
                val matches = input.inputDeviceIds.map { input.getInputDevice(it) }.filterNotNull().filter { it.name == deviceName }
                if (matches.size == 1) {
                    deviceId = matches.single().id
                    return isRouted(target)
                }
                if (matches.size > 1) return false
                Thread.sleep(25)
            } while (SystemClock.elapsedRealtime() < deadline)
            return false
        }

        override fun isRouted(target: IsolatedKeyboardProbe.Target): Boolean {
            val display = displays.getDisplay(target.displayId) ?: return false
            val uniqueId = display.javaClass.getMethod("getUniqueId").invoke(display) as? String
            if (uniqueId != target.uniqueId || target.displayId <= 0) return false
            val id = deviceId ?: return false
            val device = input.getInputDevice(id) ?: return false
            if (device.name != deviceName) return false
            val associated = device.javaClass.getMethod("getAssociatedDisplayId").invoke(device) as? Int
            return associated == target.displayId
        }

        override fun write(packet: ByteArray) {
            val device = fd ?: throw IOException("UHID device is closed")
            // UHID writes are complete events. Retrying a short write as a second event is unsafe.
            if (Os.write(device, packet, 0, packet.size) != packet.size) throw IOException("Incomplete UHID event")
        }

        override fun closeDevice() {
            val device = fd ?: return
            Os.close(device)
            fd = null
            deviceId = null
        }

        override fun removeAssociation() {
            input.javaClass.getMethod("removeUniqueIdAssociationByPort", String::class.java).invoke(input, port)
        }
    }
}
