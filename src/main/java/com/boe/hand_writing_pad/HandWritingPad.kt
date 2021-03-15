package com.boe.hand_writing_pad

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import java.lang.ref.WeakReference

/** 手写板，主要是在画布上绘制。[onChange] 绘制内容改变，主要用来在外部控制刷新显示 */
class HandWritingPad(
    width: Int,
    height: Int,
    private val applicationContext: Context,
    private val onChange: (Bitmap) -> Unit
) {

    // 绘制的图片，当作参数传入画布，所有绘制结果都在这个图片上
    private var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    // 用来绘制的画布
    private val canvas: Canvas = Canvas(bitmap)

    // 操作步骤
    private val steps = mutableListOf<Step>()

    // 操作栏
    private val operationBar = OperationBar()

    // 是否显示子菜单，一次只显示一个子菜单，所以放到外层控制
    private var showSubMenus: OperationBar.Menu? = null

    // 光标类型
    private var cursorType: CursorType = CursorType.PENCIL

    // 画笔
    private val paint = Paint().apply {
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    // 路径，绘制时统一使用这一个对象，防止创建过多的对象，每次绘制前reset
    private val path = Path()

    // 矩形，绘制时统一使用这一个对象，防止创建过多的对象，每次绘制前set
    private val rect = Rect()

    init {
        draw()
    }

    /** 手写板宽高改变，改变后步骤清空，不能再回退 */
    fun changeLayout(width: Int, height: Int) {
        if (width == bitmap.width && height == bitmap.height) {
            // 通知更新
            onChange(bitmap)
            return
        }
        val oldRatio = bitmap.width.toFloat() / bitmap.height
        val newRatio = width.toFloat() / height.toFloat()
        val oldBitmap = bitmap
        // 计算旧底图在新底图的绘制位置
        when {
            // 宽高比一样
            oldRatio == newRatio -> {
                rect.set(0, 0, width, height)
            }
            // 宽度所占比例小了，以宽为基准缩放
            oldRatio > newRatio -> {
                val scale = width.toFloat() / bitmap.width
                rect.set(0, 0, width, (bitmap.height * scale).toInt())
            }
            // 宽度所占比例大了，以高为基准缩放
            else -> {
                val scale = height.toFloat() / bitmap.height
                rect.set(0, 0, (bitmap.width * scale).toInt(), height)
            }
        }
        // 更新画布
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(oldBitmap, null, rect, paint)
        // 回收旧图片对象
        oldBitmap.recycle()
        // 清空步骤
        steps.clear()
        // 通知更新
        onChange(bitmap)
    }

    /** touch事件 */
    fun onTouch(event: MotionEvent): Boolean {
        // 点击到操作按钮上
        if (operationBar.onTouch(event)) return true
        // 绘制
        when (event.action) {
            // 按下
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val action = {
                    path.reset()
                    path.moveTo(x, y)
                    paint.run {
                        style = Paint.Style.STROKE
                        strokeWidth = 5F
                    }
                }
                val rect = Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
                steps.add(Step("绘制", rect, mutableListOf(action)))
            }
            // 移动
            MotionEvent.ACTION_MOVE -> {
                if (steps.isEmpty()) return false
                val lastStep = steps.last()
                if (lastStep.finished) return false
                val x = event.x
                val y = event.y
                val action = {
                    path.lineTo(x, y)
                    canvas.drawPath(path, paint)
                }
                lastStep.run {
                    actions.add(action)
                    frame.calculateStepFrame(x.toInt(), y.toInt())
                }
            }
            // 抬起
            MotionEvent.ACTION_UP -> {
                if (steps.isEmpty()) return false
                val lastStep = steps.last()
                if (lastStep.finished) return false
                val x = event.x
                val y = event.y
                val action = {
                    path.lineTo(x, y)
                    canvas.drawPath(path, paint)
                }
                lastStep.run {
                    actions.add(action)
                    frame.calculateStepFrame(x.toInt(), y.toInt())
                    finished = true
                }
            }
            // 取消
            MotionEvent.ACTION_CANCEL -> {
                if (steps.isNotEmpty()) {
                    steps.last().run { if (!finished) finished = true }
                }
                return false
            }
            // 其他
            else -> return false
        }
        draw()
        return true
    }

    /** 释放资源 */
    fun release() {
        bitmap.recycle()
        steps.clear()
    }

    // 绘制
    private fun draw() {
        canvas.drawColor(Color.WHITE)
        // 绘制各步骤
        if (steps.isNotEmpty()) for (step in steps) {
            step.actions.forEach { it.invoke() }
//            // 画框，测试用，要删掉
//            var oldColor: Int
//            val paint = getObject<Paint>().apply {
//                style = Paint.Style.STROKE
//                strokeWidth = 2F
//                oldColor = color
//                color = Color.RED
//            }
//            canvas.drawRect(step.frame, paint)
//            paint.color = oldColor
        }
        // 绘制操作栏
        operationBar.drawMenus()
        // 通知外层，绘制内容改变
        onChange(bitmap)
    }

    // 计算步骤里的action执行后此步骤所在区域的frame值
    private fun Rect.calculateStepFrame(x: Int, y: Int) {
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }

    // 操作步骤
    private data class Step(
        // 标记
        val tag: String,
        // 此步骤在画布上的范围
        val frame: Rect,
        // 步骤的细分操作
        val actions: MutableList<() -> Unit>,
        // 步骤是否已经结束
        var finished: Boolean = false
    )

    /** *******************操作栏，目前只考虑一竖排******************* */
    private inner class OperationBar {

        // 操作栏按钮大小
        private val menuSize = 150
        private val menuPadding = menuSize / 4
        private val menuRadius = menuSize / 50F

        // 操作栏位置
        var left: Int = 50
        var top: Int = 50
        private val right get() = left + menuSize
        private val bottom get() = top + menuSize * menus.size
        private val leftF get() = left.toFloat()
        private val topF get() = top.toFloat()
        private val rightF get() = right.toFloat()
        private val bottomF get() = bottom.toFloat()

        // 如果按下事件没在按钮上的话，需要判断隐藏子菜单栏
        private var touchDownHandled = false

        // 按钮
        private val menus = arrayOf(
            // 返回按钮
            Menu(R.drawable.ic_back) {
                // TODO 返回
                Log.e("=======", "返回按钮")
            },
            // 撤回按钮
            Menu(R.drawable.ic_withdraw) {
                if (steps.isNotEmpty()) {
                    steps.removeLast()
                    draw()
                }
            },
            // 选择画笔
            Menu(R.drawable.ic_pencil) {
                cursorType = CursorType.PENCIL
            },
            // 橡皮擦
            Menu(
                R.drawable.ic_eraser,
                arrayOf(
                    // TODO 图标
                    Menu(R.drawable.ic_eraser) {
                        cursorType = CursorType.ERASER
                        Toast.makeText(applicationContext, "1", Toast.LENGTH_SHORT).show()
                    },
                    // TODO 图标
                    Menu(R.drawable.ic_eraser) {
                        cursorType = CursorType.ERASER_CIRCLE
                        Toast.makeText(applicationContext, "2", Toast.LENGTH_SHORT).show()
                    },
                    // TODO 图标
                    Menu(R.drawable.ic_eraser) {
                        cursorType = CursorType.ERASER_STEP
                        Toast.makeText(applicationContext, "3", Toast.LENGTH_SHORT).show()
                    }
                )
            )
        )

        /** 操作栏按钮点击 */
        fun onTouch(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) touchDownHandled = false
            for (m in menus) {
                if (m.onTouch(event)) {
                    draw()
                    if (event.action == MotionEvent.ACTION_DOWN) touchDownHandled = true
                    return true
                }
            }
            if (!touchDownHandled) showSubMenus = null
            return false
        }

        /** 绘制操作栏 */
        fun drawMenus() {
            val oldColor: Int
            val oldStrokeWidth: Float
            val oldStyle: Paint.Style
            paint.run {
                oldColor = paint.color
                oldStrokeWidth = paint.strokeWidth
                oldStyle = paint.style
                paint.color = Color.BLACK
                paint.strokeWidth = 2F
                paint.style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(leftF, topF, rightF, bottomF, menuRadius, menuRadius, paint)
            for ((idx, m) in menus.withIndex()) m.draw(left, top + menuSize * idx)
            paint.apply {
                paint.color = oldColor
                paint.strokeWidth = oldStrokeWidth
                paint.style = oldStyle
            }
        }

        /** *******************按钮对象******************* */
        inner class Menu(
            val resId: Int,
            val subMenus: Array<Menu>? = null,
            val action: (() -> Unit)? = null
        ) {
            // 按钮位置
            private var left: Int = 0
            private var top: Int = 0
            private val right get() = left + menuSize
            private val bottom get() = top + menuSize
            private val leftF get() = left.toFloat()
            private val topF get() = top.toFloat()
            private val rightF get() = right.toFloat()
            private val bottomF get() = bottom.toFloat()

            // 子菜单是否显示中
            private val subMenusShowing get() = showSubMenus == this && subMenus?.isNotEmpty() == true

            // 按钮按下状态
            private var pressed: Boolean = false

            // 按钮图标
            private var bitmapWr: WeakReference<Bitmap>? = null

            /** 按钮点击事件判断 */
            fun onTouch(event: MotionEvent): Boolean {
                if (subMenusShowing) for (sm in subMenus!!) {
                    if (sm.onTouch(event)) return true
                }
                when (event.action) {
                    // 按下时判断是否点中按钮，设置按钮点中效果，调用按钮的点击方法，重新绘制
                    MotionEvent.ACTION_DOWN -> {
                        if (event.x.toInt() !in left..right || event.y.toInt() !in top..bottom) return false
                        pressed = true
                        return true
                    }
                    // 移动时判断是否移出按钮范围，取消点中效果，重新绘制
                    MotionEvent.ACTION_MOVE -> {
                        if (pressed && (event.x.toInt() !in left..right || event.y.toInt() !in top..bottom)) {
                            pressed = false
                            return true
                        }
                        return false
                    }
                    // 抬起时判断当前是否有点中的按钮，取消点中效果，重新绘制
                    MotionEvent.ACTION_UP -> {
                        if (pressed && event.x.toInt() in left..right && event.y.toInt() in top..bottom) {
                            pressed = false
                            performClick()
                            return true
                        }
                        return false
                    }
                    // 取消时判断当前是否有点中的按钮，取消点中效果，重新绘制
                    MotionEvent.ACTION_CANCEL -> {
                        if (pressed) {
                            pressed = false
                            return true
                        }
                        return false
                    }
                    else -> return false
                }
            }

            /** 绘制按钮 */
            fun draw(x: Int, y: Int) {
                left = x
                top = y
                val resBm = bitmapWr?.get() ?: vectorDrawableToBitmap() ?: return
                rect.set(
                    left + menuPadding,
                    top + menuPadding,
                    right - menuPadding,
                    bottom - menuPadding
                )
                canvas.drawBitmap(resBm, null, rect, paint)
                // 绘制子菜单
                drawSubMenus()
                // 绘制按下效果
                if (pressed) {
                    val oldColor: Int
                    val oldStyle: Paint.Style
                    paint.run {
                        oldColor = paint.color
                        oldStyle = paint.style
                        paint.color = Color.argb(50, 0, 0, 0)
                        paint.style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(
                        leftF,
                        topF,
                        rightF,
                        bottomF,
                        menuRadius,
                        menuRadius,
                        paint
                    )
                    paint.apply {
                        paint.color = oldColor
                        paint.style = oldStyle
                    }
                }
            }

            // 矢量图转Bitmap
            private fun vectorDrawableToBitmap(): Bitmap? {
                val vectorDrawable = applicationContext.getDrawable(resId) ?: return null
                return Bitmap.createBitmap(
                    vectorDrawable.intrinsicWidth,
                    vectorDrawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
                ).apply {
                    val c = Canvas(this)
                    vectorDrawable.setBounds(0, 0, c.width, c.height)
                    vectorDrawable.draw(c)
                    bitmapWr = WeakReference(this)
                }
            }

            // 按钮点击事件
            private fun performClick() {
                action?.invoke()
                showSubMenus = if (subMenus?.isNotEmpty() == true && showSubMenus == null) this
                else null
            }

            // 绘制子菜单
            private fun drawSubMenus() {
                if (!subMenusShowing) return
                val subTopF = topF + menuSize / 2
                canvas.drawRoundRect(
                    rightF,
                    subTopF,
                    rightF + menuSize,
                    subTopF + menuSize * subMenus!!.size,
                    menuRadius,
                    menuRadius,
                    paint
                )
                for ((idx, sm) in subMenus.withIndex()) sm.draw(
                    right,
                    subTopF.toInt() + menuSize * idx
                )
            }
        }
    }

    /** 光标类型 */
    private enum class CursorType {
        /** 铅笔 */
        PENCIL,

        /** 橡皮擦 */
        ERASER,

        /** 橡皮擦-擦除圈起来的区域 */
        ERASER_CIRCLE,

        /** 橡皮擦-擦除某一步（某一笔画） */
        ERASER_STEP
    }
}