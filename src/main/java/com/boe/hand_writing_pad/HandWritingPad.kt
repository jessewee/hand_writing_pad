package com.boe.hand_writing_pad

import android.graphics.*
import android.view.MotionEvent

/** 手写板，主要是在画布上绘制。[onChange] 绘制内容改变，主要用来在外部控制刷新显示 */
class HandWritingPad(width: Int, height: Int, private val onChange: (Bitmap) -> Unit) {

    // 绘制的图片，当作参数传入画布，所有绘制结果都在这个图片上
    private var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    // 用来绘制的画布
    private val canvas: Canvas = Canvas(bitmap).apply {
        drawColor(Color.WHITE)
        onChange(bitmap)
    }

    // 对象池，复用对象，避免在draw方法中重复创建对象，防止内存抖动
    private val objectPool = mutableMapOf<Class<*>, Any>()

    // 操作步骤
    private val steps = mutableListOf<Step>()

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
        val rect = getObject<Rect>()
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
        canvas.drawBitmap(oldBitmap, null, rect, getObject())
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
        // 绘制
        when (event.action) {
            // 按下
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val action = {
                    val path = getObject<Path>()
                    path.reset()
                    path.moveTo(x, y)
                    getObject<Paint>().run {
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
                val x = event.x
                val y = event.y
                val action = {
                    val path = getObject<Path>()
                    path.lineTo(x, y)
                    canvas.drawPath(path, getObject())
                }
                steps.last().run {
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
                    val path = getObject<Path>()
                    path.lineTo(x, y)
                    canvas.drawPath(path, getObject())
                }
                steps.last().run {
                    actions.add(action)
                    frame.calculateStepFrame(x.toInt(), y.toInt())
                }
            }
            // 取消
            MotionEvent.ACTION_CANCEL -> {
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
        objectPool.clear()
    }

    // 绘制
    private fun draw() {
        if (steps.isEmpty()) return
        canvas.drawColor(Color.WHITE)
        // 绘制操作栏
        drawMenus()
        // 绘制各步骤
        for (step in steps) {
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
        // 通知外层，绘制内容改变
        onChange(bitmap)
    }

    // 绘制操作栏
    private fun drawMenus() {
        // TODO
    }

    // 从对象池中获取对象
    private inline fun <reified T> getObject(): T {
        var obj = objectPool[T::class.java]
        if (obj != null) return obj as T
        // 各类型的对象创建也放到这里了
        obj = when (T::class.java) {
            // 画笔
            Paint::class.java -> Paint().apply {
                strokeJoin = Paint.Join.ROUND
                color = Color.BLACK
            }
            // 路径
            Path::class.java -> Path()
            // 矩形
            Rect::class.java -> Rect()
            // float类型的矩形
            RectF::class.java -> RectF()
            // 类型尚未添加
            else -> throw IllegalArgumentException("要创建的对象类型不存在")
        }
        objectPool[T::class.java] = obj
        return obj as T
    }

    // 计算步骤里的action执行后此步骤所在区域的frame值
    private fun Rect.calculateStepFrame(x: Int, y: Int) {
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }

    // 操作步骤
    private data class Step(val tag: String, val frame: Rect, val actions: MutableList<() -> Unit>)
}