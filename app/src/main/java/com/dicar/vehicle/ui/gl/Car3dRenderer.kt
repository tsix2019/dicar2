package com.dicar.vehicle.ui.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 车辆 3D 视图的渲染器。OpenGL ES 2.0，整台车一千多个三角形、十来个 draw call，
 * 刻意做得很轻——这个页面是给车机那块弱 GPU 跑的。
 *
 * 渲染线程和界面线程之间只靠下面这些 `@Volatile` 字段通信：界面随时写目标值，
 * 渲染线程每帧读一次并做平滑插值，不加锁。
 */
class Car3dRenderer : GLSurfaceView.Renderer {

    // ---------------- 外部写入的目标状态 ----------------

    @Volatile var doorTargets: FloatArray = FloatArray(4)
    @Volatile var windowTargets: FloatArray = FloatArray(4)
    @Volatile var speedKmh: Float = 0f
    @Volatile var turnLeft: Boolean = false
    @Volatile var turnRight: Boolean = false
    @Volatile var brake: Float = 0f

    /** 相机：绕车转的方位角、俯仰角、距离。由手势直接改。 */
    @Volatile var yawDeg: Float = 38f
    @Volatile var pitchDeg: Float = 16f
    @Volatile var distance: Float = 8.4f

    // ---------------- 配色（跟随 Compose 主题） ----------------

    @Volatile var background = floatArrayOf(0.07f, 0.09f, 0.11f, 1f)
    @Volatile var bodyColor = floatArrayOf(0.85f, 0.87f, 0.90f, 1f)
    @Volatile var glassColor = floatArrayOf(0.16f, 0.20f, 0.25f, 1f)
    @Volatile var interiorColor = floatArrayOf(0.07f, 0.08f, 0.10f, 1f)
    @Volatile var wheelColor = floatArrayOf(0.10f, 0.11f, 0.13f, 1f)
    @Volatile var accentColor = floatArrayOf(0.04f, 0.52f, 1f, 1f)
    @Volatile var shadowAlpha = 0.22f

    // ---------------- 内部 ----------------

    private val parts = CarMesh.build()
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uGloss = 0
    private var uLightDir = 0
    private var uEye = 0

    private class Gpu(val vbo: Int, val count: Int)

    private var gpuBody: Gpu? = null
    private var gpuGlass: Gpu? = null
    private var gpuInterior: Gpu? = null
    private var gpuWheel: Gpu? = null
    private var gpuShadow: Gpu? = null
    private var gpuDoorPanels: List<Gpu?> = emptyList()
    private var gpuDoorGlass: List<Gpu?> = emptyList()
    private var gpuLights: List<Gpu?> = emptyList()

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val eye = FloatArray(3)

    // 平滑后的当前值。开关门、升降窗都不该是瞬移
    private val door = FloatArray(4)
    private val window = FloatArray(4)
    private var wheelAngle = 0f
    private var lastFrameNs = 0L

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uGloss = GLES20.glGetUniformLocation(program, "uGloss")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uEye = GLES20.glGetUniformLocation(program, "uEye")

        // GL 上下文可能被销毁重建（切后台再回来），这里每次都重新上传
        gpuBody = upload(parts.body)
        gpuGlass = upload(parts.glass)
        gpuInterior = upload(parts.interior)
        gpuWheel = upload(parts.wheel)
        gpuShadow = upload(parts.shadow)
        gpuDoorPanels = parts.doors.map { upload(it.panel) }
        gpuDoorGlass = parts.doors.map { upload(it.glass) }
        gpuLights = parts.lights.map { upload(it) }
        lastFrameNs = 0L
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height
        Matrix.perspectiveM(projection, 0, 36f, aspect, 0.6f, 60f)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = now

        advanceAnimation(dt)

        GLES20.glClearColor(background[0], background[1], background[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        updateCamera()
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLightDir, LIGHT[0], LIGHT[1], LIGHT[2])
        GLES20.glUniform3f(uEye, eye[0], eye[1], eye[2])

        // 地面投影先画，关掉深度写入，免得挡住车底
        gpuShadow?.let {
            GLES20.glDepthMask(false)
            Matrix.setIdentityM(model, 0)
            draw(it, floatArrayOf(0f, 0f, 0f, shadowAlpha), gloss = 0f)
            GLES20.glDepthMask(true)
        }

        Matrix.setIdentityM(model, 0)
        gpuInterior?.let { draw(it, interiorColor, gloss = 0.02f) }
        gpuBody?.let { draw(it, bodyColor, gloss = 0.45f) }
        gpuGlass?.let { draw(it, glassColor, gloss = 0.85f) }

        drawDoors()
        drawWheels()
        drawLights()
    }

    private fun advanceAnimation(dt: Float) {
        val targets = doorTargets
        val windows = windowTargets
        for (i in 0..3) {
            door[i] += ((targets.getOrElse(i) { 0f }) - door[i]) * (dt * DOOR_SPEED).coerceIn(0f, 1f)
            window[i] += ((windows.getOrElse(i) { 0f }) - window[i]) * (dt * WINDOW_SPEED).coerceIn(0f, 1f)
        }
        // 车轮按真实周长滚：v(m/s) / r(m) = 角速度(rad/s)
        val v = speedKmh / 3.6f
        wheelAngle += v / 0.33f * dt
        if (wheelAngle > TWO_PI) wheelAngle -= TWO_PI
    }

    private fun updateCamera() {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.coerceIn(-5f, 78f).toDouble())
        val d = distance.coerceIn(5.5f, 16f)
        val ch = cos(pitch).toFloat()
        eye[0] = (d * ch * sin(yaw)).toFloat()
        eye[1] = TARGET_Y + (d * sin(pitch)).toFloat()
        eye[2] = (d * ch * cos(yaw)).toFloat()
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], 0f, TARGET_Y, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    private fun drawDoors() {
        parts.doors.forEachIndexed { i, part ->
            val open = door[i]
            Matrix.setIdentityM(model, 0)
            if (open > 0.001f) {
                // 绕车门前缘的竖轴转出去；左侧为负角、右侧为正角
                Matrix.translateM(model, 0, part.hingeX, 0f, part.hingeZ)
                Matrix.rotateM(model, 0, MAX_DOOR_ANGLE * open * part.side, 0f, 1f, 0f)
                Matrix.translateM(model, 0, -part.hingeX, 0f, -part.hingeZ)
            }
            gpuDoorPanels.getOrNull(i)?.let { draw(it, bodyColor, gloss = 0.45f) }

            // 车窗降下：玻璃绕腰线做 Y 向压缩，看起来就是落进门里
            val shut = 1f - window[i]
            if (shut > 0.02f) {
                System.arraycopy(model, 0, tmp, 0, 16)
                Matrix.translateM(tmp, 0, 0f, part.beltlineY, 0f)
                Matrix.scaleM(tmp, 0, 1f, shut, 1f)
                Matrix.translateM(tmp, 0, 0f, -part.beltlineY, 0f)
                System.arraycopy(tmp, 0, model, 0, 16)
                gpuDoorGlass.getOrNull(i)?.let { draw(it, glassColor, gloss = 0.85f) }
            }
        }
    }

    private fun drawWheels() {
        val wheel = gpuWheel ?: return
        val positions = listOf(
            -CarMesh.WHEEL_X to CarMesh.WHEEL_Z_FRONT,
            CarMesh.WHEEL_X to CarMesh.WHEEL_Z_FRONT,
            -CarMesh.WHEEL_X to CarMesh.WHEEL_Z_REAR,
            CarMesh.WHEEL_X to CarMesh.WHEEL_Z_REAR,
        )
        for ((x, z) in positions) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, 0.33f, z)
            Matrix.rotateM(model, 0, -Math.toDegrees(wheelAngle.toDouble()).toFloat(), 1f, 0f, 0f)
            draw(wheel, wheelColor, gloss = 0.15f)
        }
    }

    private fun drawLights() {
        if (gpuLights.size < 4) return
        // 转向灯 1.5Hz 闪；没在转向时前灯用暖白、尾灯用红，踩刹车尾灯变亮
        val blinkOn = (SystemClock.elapsedRealtime() / 380L) % 2L == 0L
        val leftSignal = turnLeft && blinkOn
        val rightSignal = turnRight && blinkOn
        val tailBase = 0.35f + 0.65f * brake.coerceIn(0f, 1f)

        fun lamp(index: Int, signal: Boolean, base: FloatArray, gloss: Float) {
            val gpu = gpuLights.getOrNull(index) ?: return
            Matrix.setIdentityM(model, 0)
            draw(gpu, if (signal) AMBER else base, gloss)
        }
        lamp(0, leftSignal, HEAD_COLOR, 0.9f)
        lamp(1, rightSignal, HEAD_COLOR, 0.9f)
        val tail = floatArrayOf(TAIL_COLOR[0] * tailBase + 0.08f, TAIL_COLOR[1] * tailBase, TAIL_COLOR[2] * tailBase, 1f)
        lamp(2, leftSignal, tail, 0.6f)
        lamp(3, rightSignal, tail, 0.6f)
    }

    private fun draw(gpu: Gpu, color: FloatArray, gloss: Float) {
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4f(uColor, color[0], color[1], color[2], color.getOrElse(3) { 1f })
        GLES20.glUniform1f(uGloss, gloss)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gpu.vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, gpu.count)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    private fun upload(mesh: Mesh): Gpu? {
        if (mesh.vertexCount == 0) return null
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val buffer = ByteBuffer.allocateDirect(mesh.data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(mesh.data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.data.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        return Gpu(ids[0], mesh.vertexCount)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "着色器链接失败：" + GLES20.glGetProgramInfoLog(id) }
        return id
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "着色器编译失败：" + GLES20.glGetShaderInfoLog(id) }
        return id
    }

    companion object {
        private const val STRIDE = 6 * 4
        private const val TARGET_Y = 0.72f
        private const val MAX_DOOR_ANGLE = 62f
        private const val DOOR_SPEED = 4.5f
        private const val WINDOW_SPEED = 4.5f
        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        private val LIGHT = floatArrayOf(0.42f, 0.80f, 0.43f)
        private val AMBER = floatArrayOf(1f, 0.70f, 0.12f, 1f)
        private val HEAD_COLOR = floatArrayOf(0.88f, 0.92f, 0.98f, 1f)
        private val TAIL_COLOR = floatArrayOf(0.95f, 0.18f, 0.16f, 1f)

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
                vNormal = mat3(uModel) * aNormal;
                vWorld = (uModel * vec4(aPos, 1.0)).xyz;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        // 一盏方向光 + 环境光 + 轮廓光。轮廓光是必需的：深色主题下深色车身贴着
        // 深色背景，没有这圈边就看不出车的外形。
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uGloss;
            uniform vec3 uLightDir;
            uniform vec3 uEye;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(uLightDir);
                vec3 viewDir = normalize(uEye - vWorld);
                float diff = max(dot(n, l), 0.0);
                float sky = 0.5 + 0.5 * n.y;
                vec3 base = uColor.rgb * (0.26 + 0.30 * sky + 0.62 * diff);
                vec3 h = normalize(l + viewDir);
                float spec = pow(max(dot(n, h), 0.0), 42.0) * uGloss;
                float rim = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0) * 0.30;
                gl_FragColor = vec4(base + vec3(spec) + uColor.rgb * rim + vec3(rim * 0.10), uColor.a);
            }
        """
    }
}
