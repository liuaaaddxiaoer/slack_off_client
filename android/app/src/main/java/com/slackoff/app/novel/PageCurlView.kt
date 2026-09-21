package com.slackoff.app.novel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * 仿真翻页：用 [Canvas.drawBitmapMesh] 做网格卷曲变形。
 *
 * Compose 没有自带的书页卷曲，第三方库又要新增依赖，所以自绘：
 * - 下一页直接画在底层（掀开的区域露出来）；
 * - 当前页先渲染进一张离屏 Bitmap，再按卷曲轴做 20×20 网格镜像变形叠在上层；
 * - 卷曲轴附近加线性渐变阴影 + 纸背压暗，营造厚度感。
 *
 * 测量/渲染都复用同一个 [NovelLayout]，与 iOS 端 UIPageViewController(.pageCurl) 的角色对等。
 */
class PageCurlView(context: Context) : View(context) {

    interface Listener {
        /** 翻页动画结束后回调：+1 向后翻，-1 向前翻。 */
        fun onTurnPage(delta: Int)
        /** 拖动过程中回调进度 0~1，供外部决定是否预取相邻页。 */
        fun onDrag(progress: Float) {}
    }

    var listener: Listener? = null
    var layout: NovelLayout? = null
        set(value) {
            field = value
            releaseBitmap()
            invalidate()
        }

    var currentIndex: Int = 0
        set(value) {
            if (field != value) {
                field = value
                releaseBitmap()
                invalidate()
            }
        }

    /**
     * 向后翻时露出的下一页；-1 表示没有（已是最后一页）。
     * 向前翻时 prevIndex 作为「压在上层的页」，nextIndex 不参与。
     * 拆成两个字段而不是一个 adjacentIndex：拖动方向在手指落下后才确定，
     * 但两个方向的邻页必须提前都备好，否则会画出错误的页。
     */
    var nextIndex: Int = 1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 向前翻时压在上面的上一页；-1 表示没有（已是第一页）。 */
    var prevIndex: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var textColor: Int = Color.BLACK
        set(value) {
            field = value
            releaseBitmap()
            invalidate()
        }

    var paperColor: Int = Color.WHITE
        set(value) {
            field = value
            releaseBitmap()
            invalidate()
        }

    private val meshWidth = 20
    private val meshHeight = 20
    private val verts = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)

    private var curlBitmap: Bitmap? = null
    private var curlCanvas: Canvas? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }

    /** 卷曲进度 0（平整）→ 1（完全翻过）。 */
    private var progress = 0f

    /** 拖动方向：+1 向后翻（手指左滑），-1 向前翻（手指右滑）。 */
    private var direction = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        releaseBitmap()
    }

    private fun releaseBitmap() {
        curlBitmap?.recycle()
        curlBitmap = null
        curlCanvas = null
    }

    private fun ensureBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val existing = curlBitmap
        if (existing != null && existing.width == width && existing.height == height && !existing.isRecycled) {
            return existing
        }
        releaseBitmap()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        curlBitmap = bitmap
        curlCanvas = Canvas(bitmap)
        return bitmap
    }

    /** 把某一页渲染进离屏 Bitmap（纸色打底 + 正文）。 */
    private fun renderPage(bitmap: Bitmap, index: Int) {
        val canvas = curlCanvas ?: return
        canvas.drawColor(paperColor)
        layout?.drawPage(canvas, index, textColor)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = layout ?: return
        if (current.pages.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        if (progress <= 0.0001f) {
            // 静止态：直接把当前页画上去，省掉一次离屏渲染
            canvas.drawColor(paperColor)
            current.drawPage(canvas, currentIndex, textColor)
            return
        }

        // 1) 底层：被掀开后露出的那一页
        val revealed = if (direction >= 0) nextIndex else currentIndex
        if (revealed < 0 || revealed >= current.pages.size) {
            canvas.drawColor(paperColor)
            current.drawPage(canvas, currentIndex, textColor)
            return
        }
        canvas.drawColor(paperColor)
        current.drawPage(canvas, revealed, textColor)

        // 2) 上层：正在被卷起的页
        val curlingPage = if (direction >= 0) currentIndex else prevIndex
        if (curlingPage < 0 || curlingPage >= current.pages.size) return
        val bitmap = ensureBitmap() ?: return
        renderPage(bitmap, curlingPage)

        buildCurlMesh(w, h)
        canvas.save()
        canvas.drawBitmapMesh(bitmap, meshWidth, meshHeight, verts, 0, null, 0, bitmapPaint)
        canvas.restore()

        // 3) 卷曲轴阴影 + 纸背压暗
        drawCurlShading(canvas, w, h)
    }

    /**
     * 生成卷曲网格。
     *
     * 模型：卷曲轴是一条垂直线 x = axisX，随 progress 从右边缘（或左边缘）扫向另一侧。
     * 轴外侧的部分被「掀起来」，镜像到轴的另一侧并做横向压缩（模拟绕圆柱卷起），
     * 同时叠加一点纵向位移让卷边不完全笔直。
     */
    private fun buildCurlMesh(w: Float, h: Float) {
        val p = progress.coerceIn(0f, 1f)

        // 向后翻：轴从右边缘移向左边缘；向前翻：轴从左边缘移向右边缘
        val axisX = if (direction >= 0) w * (1f - p) else w * p
        // 卷起部分的横向压缩系数：越靠近完成，卷得越紧
        val compression = 1f - 0.35f * p
        // 卷边的纵向起伏幅度
        val curlAmplitude = h * 0.035f * p

        var index = 0
        for (j in 0..meshHeight) {
            val baseY = h * j / meshHeight
            for (i in 0..meshWidth) {
                val baseX = w * i / meshWidth

                val mappedX: Float
                if (direction >= 0) {
                    mappedX = if (baseX <= axisX) {
                        baseX
                    } else {
                        // 掀起部分镜像到轴左侧，并压缩
                        axisX - (baseX - axisX) * compression
                    }
                } else {
                    mappedX = if (baseX >= axisX) {
                        baseX
                    } else {
                        axisX + (axisX - baseX) * compression
                    }
                }

                // 只在卷曲带（轴附近）做纵向起伏，形成圆柱感
                val distance = kotlin.math.abs(baseX - axisX) / w
                val band = (1f - (distance * 6f).coerceIn(0f, 1f))
                val mappedY = baseY + curlAmplitude * band *
                    kotlin.math.sin((baseY / h) * Math.PI.toFloat())

                verts[index++] = mappedX
                verts[index++] = mappedY
            }
        }
    }

    private fun drawCurlShading(canvas: Canvas, w: Float, h: Float) {
        val p = progress.coerceIn(0f, 1f)
        val axisX = if (direction >= 0) w * (1f - p) else w * p
        val bandWidth = (w * 0.16f).coerceAtLeast(24f)

        // 卷曲轴两侧的阴影：越靠近轴越深
        val start = (axisX - bandWidth).coerceIn(0f, w)
        val end = (axisX + bandWidth).coerceIn(0f, w)
        if (end > start) {
            shadowPaint.shader = LinearGradient(
                start, 0f, end, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb((70 * p).toInt(), 0, 0, 0), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(start, 0f, end, h, shadowPaint)
        }
        shadowPaint.shader = null
    }

    // MARK: 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                // 交给父容器决定要不要拦截（垂直滚动模式下不抢事件）
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging) {
                    // 水平位移明显大于垂直才认为是翻页手势，避免和上下滚动打架
                    if (kotlin.math.abs(dx) < 24f || kotlin.math.abs(dx) < kotlin.math.abs(dy)) {
                        return true
                    }
                    dragging = true
                    direction = if (dx < 0) 1 else -1
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val width = width.toFloat().coerceAtLeast(1f)
                val raw = if (direction >= 0) -dx / width else dx / width
                progress = raw.coerceIn(0f, 1f)
                listener?.onDrag(progress)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    // 没拖动 → 当作点击（显隐菜单）交给点击监听处理
                    dragging = false
                    progress = 0f
                    performClick()
                    invalidate()
                    return true
                }
                val shouldTurn = progress > 0.45f
                val delta = if (shouldTurn) direction else 0
                progress = 0f
                dragging = false
                direction = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (delta != 0) listener?.onTurnPage(delta)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseBitmap()
    }
}
