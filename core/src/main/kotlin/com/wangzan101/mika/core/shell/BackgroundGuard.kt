package com.wangzan101.mika.core.shell

/**
 * 任务跑着时不让系统把 Mika 冻住、断网。ColorOS 的 Hans 在熄屏后会先用 BPF 封掉后台 App 的网
 * (iptables 的 oplus_fw_OUTPUT / oplus_fw_INPUT 里 skfilter_*_hans),再把进程放进 cgroup v1 的冻结组;
 * 前台服务、电池白名单都拦不住它。Mika 自己被冻住时什么也做不了,所以由 root 身份的内置助手在任务期间维持:
 *
 * - 电池优化白名单(原先不在才加,结束时撤)、待机分组设为 active;
 * - 定时看一遍 Mika 的进程:冻住的解冻(v1 挪进 thaw 组或根组,v2 把 cgroup.freeze 写回 0);
 * - 在 Hans 那两条链的最前面放行 Mika 的连接:出去的包按 uid 认,并给连接打上 [CONNMARK_BIT];
 *   回来的包认这个连接标记(INPUT 上看不出 uid)。Hans 不在时这两条链不存在,加不上就算了。结束时删掉。
 *
 * 只改 Mika 自己的进程和 uid,不动 Hans 的配置文件,也不动别的 App。纯逻辑,命令和文件读写由 [io] 给(测试里换掉)。
 */
class BackgroundGuard(private val uid: Int, private val packageName: String, private val io: Io) {
    interface Io {
        /** 跑一条命令,返回 (退出码, 输出)。 */
        fun run(vararg cmd: String): Pair<Int, String>
        /** 属于 [uid] 的进程号。 */
        fun pidsOf(uid: Int): List<Int>
        fun read(path: String): String?
        fun write(path: String, text: String): Boolean
    }

    private var addedWhitelist = false
    private var started = false

    /** 开始维持;不合规矩的参数什么都不做,返回 false。 */
    fun start(): Boolean {
        if (uid < FIRST_APP_UID || !PACKAGE.matches(packageName)) return false
        started = true
        val listed = io.run("cmd", "deviceidle", "whitelist").second
        if (!whitelisted(listed, packageName)) {
            addedWhitelist = io.run("cmd", "deviceidle", "whitelist", "+$packageName").first == 0
        }
        io.run("am", "set-standby-bucket", packageName, "active")
        ensureRules()
        tick()
        return true
    }

    /** 每一轮:解冻冻住的进程;返回这一轮解冻了几个、Mika 还有没有进程。 */
    fun tick(): Tick {
        if (!started) return Tick(0, false)
        val pids = io.pidsOf(uid)
        var thawed = 0
        val v1 = freezerMount()
        for (pid in pids) {
            val groups = io.read("/proc/$pid/cgroup") ?: continue
            for (g in parseCgroups(groups)) {
                if (g.v1Freezer && v1 != null && frozenV1(g.path)) {
                    val to = if (io.read("$v1/thaw/cgroup.procs") != null) "$v1/thaw/cgroup.procs" else "$v1/cgroup.procs"
                    if (io.write(to, pid.toString())) thawed++
                }
                if (g.v2 && g.path != "/") {
                    val freeze = "$CGROUP2${g.path}/cgroup.freeze"
                    if (io.read(freeze)?.trim() == "1" && io.write(freeze, "0")) thawed++
                }
            }
        }
        return Tick(thawed, pids.isNotEmpty())
    }

    /** 放行规则被别的东西冲掉了就补回来。 */
    fun ensureRules() {
        if (!started) return
        for (ipt in IPTABLES) {
            if (!chainExists(ipt, OUT_CHAIN)) continue
            // 先打标记再放行:两条都在才不动,缺一条就整组重放,保证顺序
            if (outRules().any { !present(ipt, OUT_CHAIN, it) }) {
                outRules().forEach { deleteAll(ipt, OUT_CHAIN, it) }
                outRules().reversed().forEach { io.run(ipt, "-w", "-I", OUT_CHAIN, "1", *it.toTypedArray()) }
            }
            if (chainExists(ipt, IN_CHAIN) && !present(ipt, IN_CHAIN, inRule())) {
                io.run(ipt, "-w", "-I", IN_CHAIN, "1", *inRule().toTypedArray())
            }
        }
    }

    /** 撤掉这次加的一切;重复调用无害。 */
    fun stop() {
        if (!started) return
        started = false
        for (ipt in IPTABLES) {
            outRules().forEach { deleteAll(ipt, OUT_CHAIN, it) }
            deleteAll(ipt, IN_CHAIN, inRule())
        }
        if (addedWhitelist) io.run("cmd", "deviceidle", "whitelist", "-$packageName")
        addedWhitelist = false
    }

    data class Tick(val thawed: Int, val alive: Boolean)

    private fun outRules(): List<List<String>> = listOf(
        listOf("-m", "owner", "--uid-owner", uid.toString(), "-j", "CONNMARK", "--set-xmark", "$CONNMARK_BIT/$CONNMARK_BIT"),
        listOf("-m", "owner", "--uid-owner", uid.toString(), "-j", "RETURN"),
    )

    private fun inRule(): List<String> = listOf("-m", "connmark", "--mark", "$CONNMARK_BIT/$CONNMARK_BIT", "-j", "RETURN")

    private fun chainExists(ipt: String, chain: String) = io.run(ipt, "-w", "-S", chain).first == 0

    private fun present(ipt: String, chain: String, rule: List<String>) =
        io.run(ipt, "-w", "-C", chain, *rule.toTypedArray()).first == 0

    private fun deleteAll(ipt: String, chain: String, rule: List<String>) {
        repeat(MAX_DUPLICATES) { if (io.run(ipt, "-w", "-D", chain, *rule.toTypedArray()).first != 0) return }
    }

    private fun freezerMount(): String? = io.read("/proc/mounts")?.let(::freezerMountOf)

    internal data class Group(val v1Freezer: Boolean, val v2: Boolean, val path: String)

    companion object {
        /** 连接标记里 Mika 用的一位:避开 netd 的低 20 位和 box 一类代理常用的 0x1000000、0x2000000。 */
        const val CONNMARK_BIT = "0x10000000"
        const val OUT_CHAIN = "oplus_fw_OUTPUT"
        const val IN_CHAIN = "oplus_fw_INPUT"
        const val CGROUP2 = "/sys/fs/cgroup"
        private const val FIRST_APP_UID = 10_000
        private const val MAX_DUPLICATES = 8
        private val IPTABLES = listOf("iptables", "ip6tables")
        private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

        /** `cmd deviceidle whitelist` 的输出(每行 `类别,包名,uid`)里有没有这个包。 */
        fun whitelisted(output: String, pkg: String): Boolean =
            output.lineSequence().any { line -> line.split(',').map(String::trim).getOrNull(1) == pkg }

        /** /proc/mounts 里 cgroup v1 freezer 挂在哪;没有为 null。 */
        fun freezerMountOf(mounts: String): String? = mounts.lineSequence().map { it.split(' ') }
            .firstOrNull { f -> f.size >= 4 && f[2] == "cgroup" && f[3].split(',').contains("freezer") }?.get(1)

        /** v1 冻结组里的路径算冻住:根组、thaw 组之外带 frozen 字样的。 */
        internal fun frozenV1(path: String): Boolean = path != "/" && !path.startsWith("/thaw") && path.contains("frozen")

        /** /proc/<pid>/cgroup 的每一行 `编号:控制器:路径`。 */
        internal fun parseCgroups(text: String): List<Group> = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(':', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val controllers = parts[1].split(',')
            Group(v1Freezer = "freezer" in controllers, v2 = parts[0] == "0" && parts[1].isEmpty(), path = parts[2])
        }.toList()
    }
}
