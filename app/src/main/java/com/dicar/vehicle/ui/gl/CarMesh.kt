package com.dicar.vehicle.ui.gl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 一段三角网格。顶点按「位置 xyz + 法线 xyz」交错存放，平面着色（不共享顶点）。 */
class Mesh(val data: FloatArray, val vertexCount: Int)

/**
 * 三角形累加器。法线按面自动算，所以车身是低多边形的硬边观感——
 * 这是故意的：车机 GPU 弱，平面着色不需要共享顶点和平滑法线，几何量也能压到最小。
 */
class MeshBuilder {
    private val out = ArrayList<Float>(2048)
    private var count = 0

    fun tri(a: FloatArray, b: FloatArray, c: FloatArray) {
        val n = normalOf(a, b, c)
        for (p in arrayOf(a, b, c)) {
            out.add(p[0]); out.add(p[1]); out.add(p[2])
            out.add(n[0]); out.add(n[1]); out.add(n[2])
            count++
        }
    }

    fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
        tri(a, b, c); tri(a, c, d)
    }

    /**
     * 按「法线必须背离 [center]」自动决定绕序。
     * 车身是放样出来的，逐个面去推正确的顶点顺序极易出错且难查（表现为某些面在
     * 背面剔除下凭空消失），用一次点积判断省掉这整类问题。
     */
    fun quadOutward(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, center: FloatArray) {
        val n = normalOf(a, b, c)
        val mx = (a[0] + b[0] + c[0] + d[0]) / 4f - center[0]
        val my = (a[1] + b[1] + c[1] + d[1]) / 4f - center[1]
        val mz = (a[2] + b[2] + c[2] + d[2]) / 4f - center[2]
        if (n[0] * mx + n[1] * my + n[2] * mz >= 0f) quad(a, b, c, d) else quad(d, c, b, a)
    }

    fun triOutward(a: FloatArray, b: FloatArray, c: FloatArray, center: FloatArray) {
        val n = normalOf(a, b, c)
        val mx = (a[0] + b[0] + c[0]) / 3f - center[0]
        val my = (a[1] + b[1] + c[1]) / 3f - center[1]
        val mz = (a[2] + b[2] + c[2]) / 3f - center[2]
        if (n[0] * mx + n[1] * my + n[2] * mz >= 0f) tri(a, b, c) else tri(c, b, a)
    }

    val isEmpty: Boolean get() = count == 0

    fun build() = Mesh(out.toFloatArray(), count)
}

private fun normalOf(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
    val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
    val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
    var nx = uy * vz - uz * vy
    var ny = uz * vx - ux * vz
    var nz = ux * vy - uy * vx
    val len = sqrt(nx * nx + ny * ny + nz * nz)
    if (len > 1e-6f) { nx /= len; ny /= len; nz /= len } else { ny = 1f }
    return floatArrayOf(nx, ny, nz)
}

private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

/** 一扇车门：钣金和玻璃分开，铰链在车门前缘的竖轴上。 */
class DoorPart(
    val panel: Mesh,
    val glass: Mesh,
    /** 铰链位置（世界坐标，绕 Y 轴转）。 */
    val hingeX: Float,
    val hingeZ: Float,
    /** 车窗升降时玻璃绕这个高度做 Y 向缩放，看起来像降下去。 */
    val beltlineY: Float,
    /** 左侧为 -1、右侧为 +1，决定开门方向。 */
    val side: Float,
)

class CarParts(
    val body: Mesh,
    val glass: Mesh,
    val interior: Mesh,
    val doors: List<DoorPart>,
    val wheel: Mesh,
    /** 前后灯左右分开：转向灯要单独点亮一侧。顺序 [左前, 右前, 左后, 右后]。 */
    val lights: List<Mesh>,
    val shadow: Mesh,
)

/**
 * 程序化生成的三厢轿车。
 *
 * 做法是沿车长方向定义若干横截面，相邻截面之间放样成面。截面本身描述的是
 * 「从左下沿车侧上去、翻过车顶、再下到右下」的轮廓，所以车头收窄、腰线隆起、
 * 车顶收进这些造型全部由截面参数控制，不需要手工摆顶点。
 *
 * 车门不是贴在车身上的额外壳子：放样时直接把门所在区段的车侧面**划给车门**，
 * 车身那里是真的空的，开门能看进车内（内饰由一个深色内壳兜住）。
 */
object CarMesh {

    /** 车长（米）。相机距离、阴影大小都按这个推。 */
    const val LENGTH = 4.62f
    const val WIDTH = 1.85f
    const val HEIGHT = 1.47f

    private val CENTER = v(0f, 0.72f, 0f)

    private const val WHEEL_RADIUS = 0.33f
    private const val WHEEL_WIDTH = 0.22f

    /**
     * 轮心的横向位置。必须让轮胎外侧略微探出车身（车身腰线半宽 0.925），
     * 否则四个轮子整个埋在车身里，从外面根本看不见。
     */
    const val WHEEL_X = 0.845f
    const val WHEEL_Z_FRONT = 1.40f
    const val WHEEL_Z_REAR = -1.36f

    /**
     * 顶面高度在一段内变化超过这个值，就认为那块斜面是前/后风挡（玻璃）。
     * 阈值不能再低了：车尾 C 柱到后备箱盖那一段的落差有 0.15，调低会把
     * 后备箱盖也判成玻璃。
     */
    private const val SCREEN_DROP = 0.20f

    /**
     * 横截面。[halfW] 腰线处半宽，[yBot] 底边高度，[yBelt] 腰线高度，
     * [yTop] 顶面高度，[topHalfW] 顶面半宽（车顶比车身收进去多少）。
     */
    private class Sec(
        val z: Float,
        val halfW: Float,
        val yBot: Float,
        val yBelt: Float,
        val yTop: Float,
        val topHalfW: Float,
    ) {
        /** 11 个点：0..4 左半边自下而上，5 车顶中线，6..10 右半边镜像。 */
        fun outline(): Array<FloatArray> {
            val yMid = yBot + (yBelt - yBot) * 0.45f
            val yShoulder = yBelt + (yTop - yBelt) * 0.55f
            return arrayOf(
                floatArrayOf(-halfW * 0.80f, yBot),
                floatArrayOf(-halfW, yMid),
                floatArrayOf(-halfW, yBelt),
                floatArrayOf(-topHalfW, yShoulder),
                floatArrayOf(-topHalfW * 0.86f, yTop),
                floatArrayOf(0f, yTop),
                floatArrayOf(topHalfW * 0.86f, yTop),
                floatArrayOf(topHalfW, yShoulder),
                floatArrayOf(halfW, yBelt),
                floatArrayOf(halfW, yMid),
                floatArrayOf(halfW * 0.80f, yBot),
            )
        }
    }

    // 车头在 +Z。yTop 从车头的 0.82 一路抬到座舱的 1.47 再收回车尾，
    // 中间那两段陡升/陡降的面自然就成了前后风挡。
    private val SECTIONS = listOf(
        Sec(z = 2.31f, halfW = 0.70f, yBot = 0.44f, yBelt = 0.70f, yTop = 0.72f, topHalfW = 0.66f),
        Sec(z = 2.02f, halfW = 0.86f, yBot = 0.33f, yBelt = 0.77f, yTop = 0.80f, topHalfW = 0.82f),
        Sec(z = 1.56f, halfW = 0.915f, yBot = 0.30f, yBelt = 0.84f, yTop = 0.86f, topHalfW = 0.88f),
        Sec(z = 1.02f, halfW = 0.925f, yBot = 0.30f, yBelt = 0.92f, yTop = 1.16f, topHalfW = 0.78f),
        Sec(z = 0.44f, halfW = 0.925f, yBot = 0.30f, yBelt = 0.95f, yTop = 1.46f, topHalfW = 0.66f),
        Sec(z = -0.26f, halfW = 0.925f, yBot = 0.30f, yBelt = 0.95f, yTop = 1.47f, topHalfW = 0.66f),
        Sec(z = -0.96f, halfW = 0.905f, yBot = 0.31f, yBelt = 0.94f, yTop = 1.37f, topHalfW = 0.63f),
        Sec(z = -1.52f, halfW = 0.885f, yBot = 0.33f, yBelt = 0.92f, yTop = 1.08f, topHalfW = 0.74f),
        Sec(z = -1.98f, halfW = 0.845f, yBot = 0.36f, yBelt = 0.90f, yTop = 0.93f, topHalfW = 0.81f),
        Sec(z = -2.31f, halfW = 0.70f, yBot = 0.44f, yBelt = 0.82f, yTop = 0.84f, topHalfW = 0.66f),
    )

    // 车门占用的截面区段：前门 3→5，后门 5→7
    private const val FRONT_DOOR_FROM = 3
    private const val FRONT_DOOR_TO = 5
    private const val REAR_DOOR_FROM = 5
    private const val REAR_DOOR_TO = 7

    /**
     * 车门包含的轮廓边（边 e 连接点 e 和 e+1）。
     * 左侧只有 1（门板，腰线以下）和 2（侧窗玻璃）两条：
     * 边 0 是门槛、边 3 是车顶纵梁，这两条都得留在车身上——把纵梁也划给车门的话，
     * 开门会在车顶上挖出一个豁口，看着像整个侧面掉了。右侧对应 8 和 7。
     */
    private val LEFT_DOOR_EDGES = setOf(1, 2)
    private val RIGHT_DOOR_EDGES = setOf(7, 8)

    /** 玻璃所在的边：肩部以上那两条；只有座舱段（顶面明显高于腰线）才算。 */
    private val GLASS_EDGES = setOf(2, 3, 6, 7)

    fun build(): CarParts {
        val body = MeshBuilder()
        val glass = MeshBuilder()
        val doorPanels = Array(4) { MeshBuilder() }
        val doorGlass = Array(4) { MeshBuilder() }

        val outlines = SECTIONS.map { it.outline() }

        for (i in 0 until SECTIONS.size - 1) {
            val s0 = SECTIONS[i]
            val s1 = SECTIONS[i + 1]
            val o0 = outlines[i]
            val o1 = outlines[i + 1]
            // 这一段是不是座舱（顶面明显高于腰线），决定肩部那两条边算不算玻璃
            val greenhouse = (s0.yTop - s0.yBelt) > 0.18f && (s1.yTop - s1.yBelt) > 0.18f
            // 风挡：顶面高度在这一段里陡变，那块斜面就是前/后风挡
            val screen = abs(s1.yTop - s0.yTop) > SCREEN_DROP

            for (e in 0..10) {
                val e2 = (e + 1) % 11
                val a = v(o0[e][0], o0[e][1], s0.z)
                val b = v(o0[e2][0], o0[e2][1], s0.z)
                val c = v(o1[e2][0], o1[e2][1], s1.z)
                val d = v(o1[e][0], o1[e][1], s1.z)

                val door = doorIndexFor(i, e)
                val isGlass = when {
                    screen && e in 3..6 -> true          // 前后风挡横跨车顶那几条边
                    greenhouse && e in GLASS_EDGES -> true
                    else -> false
                }
                val target = when {
                    door >= 0 && isGlass -> doorGlass[door]
                    door >= 0 -> doorPanels[door]
                    isGlass -> glass
                    else -> body
                }
                target.quadOutward(a, b, c, d, CENTER)
            }
        }

        // 车头和车尾的封口
        capSection(body, outlines.first(), SECTIONS.first().z)
        capSection(body, outlines.last(), SECTIONS.last().z)

        return CarParts(
            body = body.build(),
            glass = glass.build(),
            interior = buildInterior(),
            doors = buildDoors(doorPanels, doorGlass),
            wheel = buildWheel(),
            lights = listOf(
                lamp(front = true, side = -1f), lamp(front = true, side = 1f),
                lamp(front = false, side = -1f), lamp(front = false, side = 1f),
            ),
            shadow = buildShadow(),
        )
    }

    /** 这条边属于哪扇门：0 左前 1 右前 2 左后 3 右后，-1 表示归车身。 */
    private fun doorIndexFor(segment: Int, edge: Int): Int {
        val front = segment in FRONT_DOOR_FROM until FRONT_DOOR_TO
        val rear = segment in REAR_DOOR_FROM until REAR_DOOR_TO
        if (!front && !rear) return -1
        return when {
            edge in LEFT_DOOR_EDGES -> if (front) 0 else 2
            edge in RIGHT_DOOR_EDGES -> if (front) 1 else 3
            else -> -1
        }
    }

    private fun buildDoors(panels: Array<MeshBuilder>, glasses: Array<MeshBuilder>): List<DoorPart> {
        fun hinge(segFrom: Int): Pair<Float, Float> = SECTIONS[segFrom].let { it.halfW to it.z }
        val specs = listOf(
            Triple(0, FRONT_DOOR_FROM, -1f),
            Triple(1, FRONT_DOOR_FROM, 1f),
            Triple(2, REAR_DOOR_FROM, -1f),
            Triple(3, REAR_DOOR_FROM, 1f),
        )
        return specs.map { (idx, segFrom, side) ->
            val (halfW, z) = hinge(segFrom)
            DoorPart(
                panel = panels[idx].build(),
                glass = glasses[idx].build(),
                hingeX = halfW * side,
                hingeZ = z,
                beltlineY = SECTIONS[segFrom].yBelt,
                side = side,
            )
        }
    }

    /** 车头/车尾封口：以截面重心为中心扇形填充。 */
    private fun capSection(mb: MeshBuilder, outline: Array<FloatArray>, z: Float) {
        var cx = 0f; var cy = 0f
        outline.forEach { cx += it[0]; cy += it[1] }
        cx /= outline.size; cy /= outline.size
        val c = v(cx, cy, z)
        for (e in outline.indices) {
            val e2 = (e + 1) % outline.size
            mb.triOutward(c, v(outline[e][0], outline[e][1], z), v(outline[e2][0], outline[e2][1], z), CENTER)
        }
    }

    /**
     * 内饰壳：开门之后挡住视线，不然能一眼看穿整台车。
     * 比车身内收一截，用深色绘制，形状糙一点无所谓——只会从门洞里露出一小块。
     */
    private fun buildInterior(): Mesh {
        val mb = MeshBuilder()
        box(mb, -0.76f, 0.26f, -1.62f, 0.76f, 1.30f, 1.08f, v(0f, 0.8f, 0f))
        return mb.build()
    }

    private fun box(
        mb: MeshBuilder,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        center: FloatArray,
    ) {
        val p = arrayOf(
            v(x0, y0, z0), v(x1, y0, z0), v(x1, y1, z0), v(x0, y1, z0),
            v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1),
        )
        mb.quadOutward(p[0], p[1], p[2], p[3], center)
        mb.quadOutward(p[4], p[5], p[6], p[7], center)
        mb.quadOutward(p[0], p[4], p[7], p[3], center)
        mb.quadOutward(p[1], p[5], p[6], p[2], center)
        mb.quadOutward(p[3], p[2], p[6], p[7], center)
        mb.quadOutward(p[0], p[1], p[5], p[4], center)
    }

    /** 轮胎 + 轮毂盘。沿 X 轴的圆柱，原点在轮心，四个轮子共用同一份网格。 */
    private fun buildWheel(): Mesh {
        val mb = MeshBuilder()
        val seg = 16
        val r = WHEEL_RADIUS
        val hw = WHEEL_WIDTH / 2f
        val center = v(0f, 0f, 0f)
        for (i in 0 until seg) {
            val a0 = 2.0 * PI * i / seg
            val a1 = 2.0 * PI * (i + 1) / seg
            val y0 = (r * cos(a0)).toFloat(); val z0 = (r * sin(a0)).toFloat()
            val y1 = (r * cos(a1)).toFloat(); val z1 = (r * sin(a1)).toFloat()
            // 胎面
            mb.quadOutward(v(-hw, y0, z0), v(hw, y0, z0), v(hw, y1, z1), v(-hw, y1, z1), center)
            // 两侧轮辐面（稍微内凹，边缘留出胎壁）
            val ri = 0.82f
            mb.quadOutward(
                v(hw, y0, z0), v(hw * 0.72f, y0 * ri, z0 * ri),
                v(hw * 0.72f, y1 * ri, z1 * ri), v(hw, y1, z1), center,
            )
            mb.quadOutward(
                v(-hw, y0, z0), v(-hw * 0.72f, y0 * ri, z0 * ri),
                v(-hw * 0.72f, y1 * ri, z1 * ri), v(-hw, y1, z1), center,
            )
            // 轮毂盘面，给个轮辐的暗示
            mb.tri(v(hw * 0.72f, 0f, 0f), v(hw * 0.72f, y0 * ri, z0 * ri), v(hw * 0.72f, y1 * ri, z1 * ri))
            mb.tri(v(-hw * 0.72f, 0f, 0f), v(-hw * 0.72f, y1 * ri, z1 * ri), v(-hw * 0.72f, y0 * ri, z0 * ri))
        }
        return mb.build()
    }

    /** 单个灯组。左右分开成独立网格，转向灯才能只亮一侧。 */
    private fun lamp(front: Boolean, side: Float): Mesh {
        val mb = MeshBuilder()
        // 稍微埋进车头/车尾里一点，避免和车身面重合打架（z-fighting）
        val zOuter = if (front) 2.26f else -2.26f
        val zInner = if (front) 2.08f else -2.08f
        val y0 = if (front) 0.60f else 0.73f
        val y1 = if (front) 0.75f else 0.85f
        val x0 = side * 0.26f
        val x1 = side * 0.62f
        box(
            mb,
            minOf(x0, x1), y0, minOf(zOuter, zInner),
            maxOf(x0, x1), y1, maxOf(zOuter, zInner),
            CENTER,
        )
        return mb.build()
    }

    /** 车底的椭圆投影，把车"放"在地面上而不是飘着。 */
    private fun buildShadow(): Mesh {
        val mb = MeshBuilder()
        val seg = 28
        val rx = WIDTH * 0.62f
        val rz = LENGTH * 0.54f
        val c = v(0f, 0.01f, 0f)
        for (i in 0 until seg) {
            val a0 = 2.0 * PI * i / seg
            val a1 = 2.0 * PI * (i + 1) / seg
            mb.tri(
                c,
                v((rx * cos(a1)).toFloat(), 0.01f, (rz * sin(a1)).toFloat()),
                v((rx * cos(a0)).toFloat(), 0.01f, (rz * sin(a0)).toFloat()),
            )
        }
        return mb.build()
    }
}
