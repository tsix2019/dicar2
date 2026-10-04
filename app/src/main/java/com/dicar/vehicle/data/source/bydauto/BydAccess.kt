package com.dicar.vehicle.data.source.bydauto

import android.content.Context
import com.dicar.vehicle.data.source.bydauto.BydDevice.CallResult
import com.dicar.vehicle.data.source.bydauto.adb.AdbTransport

/**
 * 读写车辆数据的底层通道抽象，两种实现：
 * - [InProcessBydAccess]：App 自身进程直接反射（多数 DiLink 会被签名权限拒绝）；
 * - [AdbBydAccess]：经车机本机 adbd 起的辅助进程以调试身份读写（真正能拿到数据的那条路）。
 *
 * [BydAutoDataSource] 按「哪条通道能用」自动选择，上层无感知。
 * 所有方法只在 Repository 的单 IO 线程上调用。
 */
interface BydAccess {
    val label: String

    /** 便宜的可用性探测（不建立完整会话）。 */
    fun probeAvailable(): Boolean

    /** 建立/校验通道；不可用时抛异常（消息会展示给用户）。 */
    fun ensureReady()

    fun readGetter(className: String, method: String, args: IntArray): CallResult
    fun readFid(devId: Int, fid: Int, isFloat: Boolean): CallResult
    fun callSetter(className: String, method: String, args: IntArray): CallResult
    fun writeFid(devId: Int, fid: Int, value: Int): CallResult

    /** 一次读多项。默认逐条读；跨进程通道应覆盖成单次往返。 */
    fun readBatch(requests: List<RawReq>): List<CallResult> = requests.map {
        when (it) {
            is RawReq.Getter -> readGetter(it.className, it.method, it.args)
            is RawReq.Fid -> readFid(it.devId, it.fid, it.isFloat)
        }
    }

    fun close() {}
}

/** 与 [BydApiMap.Src] 解耦的底层读请求（FID 符号已解析成本车数值）。 */
sealed interface RawReq {
    data class Getter(val className: String, val method: String, val args: IntArray) : RawReq
    data class Fid(val devId: Int, val fid: Int, val isFloat: Boolean) : RawReq
}

/** 进程内直连。FID 读写走 BYDAutoDeviceManager（同样用 BydDevice 包一层）。 */
class InProcessBydAccess(context: Context) : BydAccess {

    override val label = "进程内"

    private val ctx = BydPermissionContext(context.applicationContext)
    private val devices = HashMap<String, BydDevice>()
    private fun device(className: String) = devices.getOrPut(className) { BydDevice(ctx, className) }
    private val manager get() = device(BydApiMap.DEVICE_MANAGER_CLASS)

    override fun probeAvailable(): Boolean =
        // 能读到一个数值（哪怕是错误码）就说明权限没被拒；被拒会是 Error
        readGetter("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", "getElecPercentageValue", IntArray(0)) is CallResult.Value

    override fun ensureReady() = Unit

    override fun readGetter(className: String, method: String, args: IntArray) = device(className).call(method, *args)

    override fun readFid(devId: Int, fid: Int, isFloat: Boolean) =
        manager.call(if (isFloat) "getDouble" else "getInt", devId, fid)

    override fun callSetter(className: String, method: String, args: IntArray) = device(className).call(method, *args)

    override fun writeFid(devId: Int, fid: Int, value: Int) = manager.call("setInt", devId, fid, value)
}

/** 经辅助进程（调试身份）读写。 */
class AdbBydAccess(private val transport: AdbTransport) : BydAccess {

    override val label = "无线调试"

    override fun probeAvailable(): Boolean = transport.isPortOpen()

    /** 会话还活着就直接复用，不每次都 PING（省一次往返）。 */
    override fun ensureReady() = transport.ensureConnected()

    override fun readGetter(className: String, method: String, args: IntArray) =
        parse(transport.request(line("MG", className, method, args)))

    override fun readFid(devId: Int, fid: Int, isFloat: Boolean) =
        parse(transport.request("${if (isFloat) "GD" else "GI"} $devId $fid"))

    override fun callSetter(className: String, method: String, args: IntArray) =
        parse(transport.request(line("MC", className, method, args)))

    override fun writeFid(devId: Int, fid: Int, value: Int) =
        parse(transport.request("SI $devId $fid $value"))

    override fun readBatch(requests: List<RawReq>): List<CallResult> {
        if (requests.isEmpty()) return emptyList()
        val lines = requests.map {
            when (it) {
                is RawReq.Getter -> line("MG", it.className, it.method, it.args)
                is RawReq.Fid -> "${if (it.isFloat) "GD" else "GI"} ${it.devId} ${it.fid}"
            }
        }
        return transport.requestBatch(lines).map(::parse)
    }

    override fun close() = transport.reset()

    private fun line(op: String, className: String, method: String, args: IntArray) =
        "$op $className $method ${args.size}${args.joinToString("") { " $it" }}"

    private fun parse(reply: String) = parseReply(reply)

    companion object {
        /** 解析辅助进程应答（协议见 HelperMain.render）。 */
        internal fun parseReply(reply: String): CallResult {
            val sp = reply.indexOf(' ')
            val tag = if (sp < 0) reply else reply.substring(0, sp)
            val body = if (sp < 0) "" else reply.substring(sp + 1)
            return when (tag) {
                "I" -> CallResult.Value(body.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it })
                "D" -> CallResult.Value(body.toDoubleOrNull())
                "A" -> CallResult.Value(
                    body.split(',').filter { it.isNotBlank() }.map { it.trim().toDouble().toInt() }.toIntArray()
                )
                "N" -> CallResult.Value(null)
                "S" -> CallResult.Value(body)
                "M" -> CallResult.Missing
                else -> CallResult.Error(IllegalStateException(if (tag == "E") body else reply))
            }
        }
    }
}
