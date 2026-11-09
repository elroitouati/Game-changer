package com.gamechanger.shield

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.FloatBuffer

/**
 * On-device nudity detector (NudeNet 320n, YOLOv8 based). Runs fully offline.
 *
 * Output tensor: [1, 22, N] where rows 0..3 are the box and rows 4..21 are the
 * 18 class scores. We only care whether any "exposed" class crosses the threshold,
 * so no box decoding / NMS is needed.
 */
class NudityDetector(context: Context) {

    companion object {
        const val INPUT = 320

        // NudeNet label order.
        val LABELS = arrayOf(
            "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED", "FEET_EXPOSED", "BELLY_COVERED", "FEET_COVERED",
            "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE", "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
        )

    }

    data class Result(val blocked: Boolean, val label: String?, val score: Float)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val pixels = IntArray(INPUT * INPUT)
    private val input = FloatBuffer.allocate(3 * INPUT * INPUT)
    private val square = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val bytes = context.assets.open("nudenet_320n.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
    }

    /**
     * Scans a full-screen bitmap. A tall phone screen is split into near-square
     * tiles so small images on the screen keep enough detail for the model.
     */
    fun scan(screen: Bitmap, thresholds: FloatArray): Result {
        val w = screen.width
        val h = screen.height
        val tiles = if (h > w) (h.toFloat() / w).let { Math.round(it).coerceIn(1, 3) } else 1
        val tileH = h / tiles
        var best = Result(false, null, 0f)
        for (i in 0 until tiles) {
            val top = i * tileH
            val bottom = if (i == tiles - 1) h else top + tileH
            val r = scanRegion(screen, Rect(0, top, w, bottom), thresholds)
            if (r.blocked) return r
            if (r.score > best.score) best = r
        }
        return best
    }

    private fun scanRegion(src: Bitmap, region: Rect, thresholds: FloatArray): Result {
        // Letterbox: keep aspect ratio, pad right/bottom with black (same as NudeNet).
        val scale = INPUT.toFloat() / maxOf(region.width(), region.height())
        val dw = (region.width() * scale).toInt().coerceAtLeast(1)
        val dh = (region.height() * scale).toInt().coerceAtLeast(1)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, region, Rect(0, 0, dw, dh), paint)
        square.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)

        val plane = INPUT * INPUT
        input.clear()
        val arr = input.array()
        for (p in 0 until plane) {
            val c = pixels[p]
            arr[p] = ((c shr 16) and 0xFF) / 255f
            arr[plane + p] = ((c shr 8) and 0xFF) / 255f
            arr[2 * plane + p] = (c and 0xFF) / 255f
        }

        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                val o = out[0] as OnnxTensor
                val shape = o.info.shape // [1, 22, N]
                val n = shape[2].toInt()
                val buf = o.floatBuffer
                // Score relative to each class threshold: >= 1 means block.
                var bestRatio = 0f
                var bestScore = 0f
                var bestCls = -1
                for (cls in thresholds.indices) {
                    val th = thresholds[cls]
                    if (th > 1f) continue
                    val row = (4 + cls) * n
                    for (j in 0 until n) {
                        val s = buf.get(row + j)
                        val ratio = s / th
                        if (ratio > bestRatio) {
                            bestRatio = ratio
                            bestScore = s
                            bestCls = cls
                        }
                    }
                }
                val label = if (bestCls >= 0) LABELS[bestCls] else null
                return Result(bestRatio >= 1f, label, bestScore)
            }
        }
    }

    fun close() {
        session.close()
    }
}
