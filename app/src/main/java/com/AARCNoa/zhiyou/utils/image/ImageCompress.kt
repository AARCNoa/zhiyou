package com.AARCNoa.zhiyou.utils.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 图片压缩：把任意图片字节压成长边 ≤ [jpeg] 的 JPEG，给「喂给模型」的场景用。
 *
 * 只做解码 + 缩放 + 编码，不处理 WXGF（微信私有格式由调用方先解码）。
 * 聊天识图与朋友圈评论共用这一份：两处参数必须一致，否则同一张图在两处占的
 * token 与清晰度不同，模型表现会莫名其妙地不一样。
 */
object ImageCompress {

    /**
     * 默认 800px / 70%：长边 800 已够看清画面说什么，再大只是白付 token。
     *
     * 实现上先按 2 的幂降采样到不超过 2 倍目标，再精确缩放 —— 一张 4000px 的原图
     * 只解出一份小位图，不会同时存在全尺寸与缩放结果两份。
     */
    fun jpeg(raw: ByteArray, maxSide: Int = 800, quality: Int = 70): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        // 解不出尺寸：不是位图（视频封面 / WXAM / HEVC），或文件还只是 0 字节占位。
        if (longest <= 0) return null

        var sample = 1
        while (longest / sample > maxSide * 2) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxSide) {
            val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }
}
