package com.boe.hand_writing_pad

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
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
                val x = event.x
                val y = event.y
                val action = {
                    path.lineTo(x, y)
                    canvas.drawPath(path, paint)
                }
                steps.last().run {
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

    // 操作栏，目前只考虑一竖排
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

        // 显示子菜单，值是要显示的子菜单的父级菜单，null表示不显示
        private var showSubMenu: Menu? = null

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
            Menu(R.drawable.ic_eraser) {
                // TODO
                Log.e("=======", "橡皮擦")
            }
        )

        /** 操作栏按钮点击 */
        fun onTouch(event: MotionEvent): Boolean {
            when (event.action) {
                // 按下时判断是否点中按钮，设置按钮点中效果，调用按钮的action方法，重新绘制
                MotionEvent.ACTION_DOWN -> {
                    val menu = findTouchedMenu(event.x.toInt(), event.y.toInt()) ?: return false
                    menu.pressed = true
                    menu.action.invoke()
                    draw()
                    return true
                }
                // 抬起时判断当前是否有点中的按钮，取消点中效果，重新绘制
                MotionEvent.ACTION_UP -> {
                    val pressedMenus = menus.filter { it.pressed }
                    if (pressedMenus.isEmpty())
                        return findTouchedMenu(event.x.toInt(), event.y.toInt()) != null
                    for (m in pressedMenus) m.pressed = false
                    draw()
                    return true
                }
                // 取消时判断当前是否有点中的按钮，取消点中效果，重新绘制
                MotionEvent.ACTION_CANCEL -> {
                    val pressedMenus = menus.filter { it.pressed }
                    if (pressedMenus.isEmpty()) return false
                    for (m in pressedMenus) m.pressed = false
                    draw()
                    return true
                }
                else -> return false
            }
        }

        // 判断点中的按钮
        private fun findTouchedMenu(x: Int, y: Int): Menu? {
            if (x !in left..right || x !in top..bottom) return null
            for ((idx, m) in menus.withIndex()) {
                val menuTop = top + menuSize * idx
                val menuBottom = menuTop + menuSize
                if (y in menuTop..menuBottom) return m
            }
            return null
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
            for ((idx, m) in menus.withIndex()) drawMenuItem(m, left, top + menuSize * idx)
            drawSubMenus()
            paint.apply {
                paint.color = oldColor
                paint.strokeWidth = oldStrokeWidth
                paint.style = oldStyle
            }
        }

        // 绘制子菜单
        private fun drawSubMenus() {
            if (showSubMenu == null) return
            // TODO
        }

        // 绘制按钮
        private fun drawMenuItem(menu: Menu, x: Int, y: Int) {
            val itemRight = x + menuSize
            val itemBottom = y + menuSize
            val resBm = menu.bitmapWr?.get() ?: vectorDrawableToBitmap(menu) ?: return
            rect.set(
                x + menuPadding,
                y + menuPadding,
                itemRight - menuPadding,
                itemBottom - menuPadding
            )
            canvas.drawBitmap(resBm, null, rect, paint)
            if (menu.pressed) {
                val oldColor: Int
                val oldStyle: Paint.Style
                paint.run {
                    oldColor = paint.color
                    oldStyle = paint.style
                    paint.color = Color.argb(50, 0, 0, 0)
                    paint.style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    x.toFloat(),
                    y.toFloat(),
                    itemRight.toFloat(),
                    itemBottom.toFloat(),
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
        private fun vectorDrawableToBitmap(menu: Menu): Bitmap? {
            val vectorDrawable = applicationContext.getDrawable(menu.resId) ?: return null
            return Bitmap.createBitmap(
                vectorDrawable.intrinsicWidth,
                vectorDrawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            ).apply {
                val c = Canvas(this)
                vectorDrawable.setBounds(0, 0, c.width, c.height)
                vectorDrawable.draw(c)
                menu.bitmapWr = WeakReference(this)
            }
        }
    }

    /** 按钮对象 */
    private data class Menu(
        val resId: Int,
        var pressed: Boolean = false,
        var bitmapWr: WeakReference<Bitmap>? = null,
        val action: () -> Unit
    )

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