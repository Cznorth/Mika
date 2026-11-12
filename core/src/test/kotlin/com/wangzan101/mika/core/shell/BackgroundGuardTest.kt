package com.wangzan101.mika.core.shell

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class BackgroundGuardTest {
    private class FakeIo(var whitelist: String = "", val chains: Set<String> = setOf("oplus_fw_OUTPUT", "oplus_fw_INPUT")) : BackgroundGuard.Io {
        val rules = HashMap<String, MutableList<List<String>>>()
        val commands = ArrayList<List<String>>()
        val files = HashMap<String, String>()
        val writes = ArrayList<Pair<String, String>>()
        var pids = listOf<Int>()

        override fun run(vararg cmd: String): Pair<Int, String> {
            commands += cmd.toList()
            if (cmd[0] == "cmd") return 0 to whitelist
            if (cmd[0] == "am") return 255 to "exempted"
            val (ipt, op, chain) = Triple(cmd[0], cmd[2], cmd[3])
            if (chain !in chains) return 1 to ""
            val list = rules.getOrPut("$ipt $chain") { ArrayList() }
            return when (op) {
                "-S" -> 0 to ""
                "-C" -> (if (cmd.drop(4) in list) 0 else 1) to ""
                "-I" -> { list.add(cmd[4].toInt() - 1, cmd.drop(5)); 0 to "" }
                "-D" -> (if (list.remove(cmd.drop(4))) 0 else 1) to ""
                else -> 1 to ""
            }
        }

        override fun pidsOf(uid: Int) = pids
        override fun read(path: String) = files[path]
        override fun write(path: String, text: String): Boolean { writes += path to text; return true }
    }

    @Test
    fun `adds release rules ahead of the hans filter and removes them on stop`() {
        val io = FakeIo()
        val g = BackgroundGuard(10583, "com.example.mika", io)
        assertTrue(g.start())
        val out = io.rules.getValue("iptables oplus_fw_OUTPUT")
        assertEquals("CONNMARK", out[0][5])
        assertEquals("RETURN", out[1].last())
        assertEquals(listOf("-m", "connmark", "--mark", "0x10000000/0x10000000", "-j", "RETURN"), io.rules.getValue("ip6tables oplus_fw_INPUT")[0])
        g.stop()
        assertTrue(io.rules.values.all { it.isEmpty() })
    }

    @Test
    fun `restores rule order when one of the pair went missing`() {
        val io = FakeIo()
        val g = BackgroundGuard(10583, "com.example.mika", io)
        g.start()
        io.rules.getValue("iptables oplus_fw_OUTPUT").removeAt(0)
        g.ensureRules()
        val out = io.rules.getValue("iptables oplus_fw_OUTPUT")
        assertEquals(2, out.size)
        assertEquals("CONNMARK", out[0][5])
    }

    @Test
    fun `skips firewall rules when hans chains do not exist`() {
        val io = FakeIo(chains = emptySet())
        val g = BackgroundGuard(10583, "com.example.mika", io)
        assertTrue(g.start())
        assertTrue(io.commands.none { it.getOrNull(2) == "-I" })
    }

    @Test
    fun `only removes the battery whitelist entry it added`() {
        val listed = FakeIo(whitelist = "user,com.example.mika,10583\n")
        BackgroundGuard(10583, "com.example.mika", listed).apply { start(); stop() }
        assertTrue(listed.commands.none { it[0] == "cmd" && it.size == 4 })

        val absent = FakeIo(whitelist = "user,other.app,10111\n")
        BackgroundGuard(10583, "com.example.mika", absent).apply { start(); stop() }
        assertTrue(listOf("cmd", "deviceidle", "whitelist", "+com.example.mika") in absent.commands)
        assertTrue(listOf("cmd", "deviceidle", "whitelist", "-com.example.mika") in absent.commands)
    }

    @Test
    fun `thaws frozen v1 and v2 groups of its own processes`() {
        val io = FakeIo()
        io.pids = listOf(42)
        io.files["/proc/mounts"] = "none /dev/freezer cgroup rw,relatime,freezer 0 0\n"
        io.files["/dev/freezer/thaw/cgroup.procs"] = ""
        io.files["/proc/42/cgroup"] = "4:freezer:/frozen\n0::/apps/uid_10583/pid_42\n"
        io.files["/sys/fs/cgroup/apps/uid_10583/pid_42/cgroup.freeze"] = "1\n"
        val g = BackgroundGuard(10583, "com.example.mika", io)
        g.start()
        io.writes.clear()
        val tick = g.tick()
        assertEquals(2, tick.thawed)
        assertTrue(tick.alive)
        assertEquals(listOf("/dev/freezer/thaw/cgroup.procs" to "42", "/sys/fs/cgroup/apps/uid_10583/pid_42/cgroup.freeze" to "0"), io.writes)
    }

    @Test
    fun `leaves running processes alone`() {
        val io = FakeIo()
        io.pids = listOf(42)
        io.files["/proc/mounts"] = "none /dev/freezer cgroup rw,relatime,freezer 0 0\n"
        io.files["/proc/42/cgroup"] = "4:freezer:/\n0::/apps/uid_10583/pid_42\n"
        io.files["/sys/fs/cgroup/apps/uid_10583/pid_42/cgroup.freeze"] = "0\n"
        val g = BackgroundGuard(10583, "com.example.mika", io)
        g.start()
        assertEquals(0, g.tick().thawed)
        assertTrue(io.writes.isEmpty())
    }

    @Test
    fun `refuses system uids and odd package names`() {
        assertFalse(BackgroundGuard(1000, "com.example.mika", FakeIo()).start())
        assertFalse(BackgroundGuard(10583, "x; reboot", FakeIo()).start())
    }

    @Test
    fun `parses the freezer mount`() {
        assertEquals("/dev/freezer", BackgroundGuard.freezerMountOf("cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\nnone /dev/freezer cgroup rw,relatime,freezer 0 0\n"))
        assertEquals(null, BackgroundGuard.freezerMountOf("cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n"))
    }
}
