package com.wangzan101.mika.cap.phone.shell

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.wangzan101.mika.routing.RoutingProtocol
import java.util.concurrent.ConcurrentHashMap

/** Root helper side of the system-server lease. No provider, exported service, or writable config file. */
internal class RootTaskRouting {
    private val leases = ConcurrentHashMap<Int, IBinder>()
    private val service: IBinder
        get() = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder

    private fun request(op: Int, token: IBinder? = null, write: (Parcel) -> Unit = {}) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RoutingProtocol.DESCRIPTOR)
            data.writeInt(op)
            if (op != RoutingProtocol.HEALTH) data.writeStrongBinder(token ?: error("missing routing lease"))
            write(data)
            check(service.transact(RoutingProtocol.TRANSACTION, data, reply, 0)) { "system routing hook not loaded" }
            reply.readException()
            check(reply.readInt() == RoutingProtocol.VERSION) { "system routing protocol mismatch" }
        } finally { reply.recycle(); data.recycle() }
    }

    fun requireAvailable() {
        try { request(RoutingProtocol.HEALTH) }
        catch (t: Throwable) { throw RootDisplayRoutingUnavailable(t) }
    }
    fun open(display: Int, identity: String) {
        check(display !in leases)
        val token = Binder()
        request(RoutingProtocol.OPEN, token) { it.writeInt(display); it.writeString(identity) }
        leases[display] = token
    }
    fun bind(display: Int, packageName: String, user: Int) {
        request(RoutingProtocol.BIND, leases[display]) { it.writeString(packageName); it.writeInt(user) }
    }
    fun check(display: Int): Boolean = runCatching { request(RoutingProtocol.CHECK, leases[display]); true }.getOrDefault(false)
    fun beginClose(display: Int) { request(RoutingProtocol.BEGIN_CLOSE, leases[display]) }
    fun close(display: Int) {
        val token = leases[display] ?: return
        request(RoutingProtocol.CLOSE, token)
        leases.remove(display, token)
    }
}
