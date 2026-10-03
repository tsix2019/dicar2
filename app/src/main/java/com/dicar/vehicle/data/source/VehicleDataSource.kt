package com.dicar.vehicle.data.source

import com.dicar.vehicle.data.model.CommandResult
import com.dicar.vehicle.data.model.DataSourceType
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState

/**
 * 数据源统一接口。实现约定：
 * - 由 Repository 在单个 IO 线程上串行调用，内部可以直接阻塞（反射 / HTTP），不要再切线程；
 * - 单个字段读不到 → 该字段置 null，不要抛异常；
 * - 整个数据源坏掉（类加载失败、端口拒绝连接）→ [read] 抛异常，Repository 会重新探测并在「自动」模式下切换数据源；
 * - [read] 返回的 state 不需要填 source/timestamp，由 Repository 统一补。
 */
interface VehicleDataSource {
    val type: DataSourceType

    /** 探测当前环境下是否可用（类是否存在 / 端口是否通）。可以被多次调用。 */
    suspend fun isAvailable(): Boolean

    suspend fun read(): VehicleState

    suspend fun execute(command: VehicleCommand): CommandResult

    fun release() {}
}
