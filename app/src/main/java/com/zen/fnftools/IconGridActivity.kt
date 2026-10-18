package com.zen.fnftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zen.fnftools.databinding.ActivityIconGridBinding
import com.zen.fnftools.util.OutputSaver
import com.zen.fnftools.util.SaveAccess
import com.zen.fnftools.util.setBouncy
import com.zen.fnftools.util.staggerIn
import kotlin.math.max
import kotlin.math.min

class IconGridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIconGridBinding
    private var normalBitmap: Bitmap? = null
    private var losingBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null

    private val pickNormal =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> loadImage(uri, true) }
    private val pickLosing =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> loadImage(uri, false) }

    private fun loadImage(uri: Uri?, isNormal: Boolean) {
        if (uri == null) return
        val bmp = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_SHORT).show()
            return
        }
        val view = if (isNormal) binding.ivNormal else binding.ivLosing
        val old = if (isNormal) normalBitmap else losingBitmap
        view.setImageBitmap(bmp)
        view.visibility = View.VISIBLE
        if (isNormal) normalBitmap = bmp else losingBitmap = bmp
        if (old != null && !old.isRecycled) old.recycle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIconGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickNormal.setBouncy()
        binding.btnPickLosing.setBouncy()
        binding.btnConvert.setBouncy()

        staggerIn(listOf(binding.tvHeader, binding.tvBody, binding.btnPickNormal, binding.btnPickLosing))

        binding.btnPickNormal.setOnClickListener { pickNormal.launch("image/*") }
        binding.btnPickLosing.setOnClickListener { pickLosing.launch("image/*") }
        binding.btnConvert.setOnClickListener { createIcon() }
    }

    private fun createIcon() {
        val normal = normalBitmap
        val losing = losingBitmap
        if (normal == null || losing == null) {
            Toast.makeText(this, "Pick both the normal and losing icon first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!SaveAccess.isGranted()) {
            SaveAccess.requestAccess(this)
            return
        }

        val size = (binding.etCellSize.text.toString().toIntOrNull() ?: 150).coerceIn(16, 1024)
        val outputName = binding.etOutputName.text.toString()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifBlank { "icon-custom" }
        val crop = binding.cbCrop.isChecked

        // Output is two square cells side by side: (size * 2) wide x size tall.
        val result = Bitmap.createBitmap(size * 2, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        drawCell(canvas, paint, normal, 0, size, crop)
        drawCell(canvas, paint, losing, size, size, crop)

        val oldResult = resultBitmap
        resultBitmap = result
        binding.ivIconPreview.setImageBitmap(result)
        binding.ivIconPreview.visibility = View.VISIBLE
        if (oldResult != null && !oldResult.isRecycled) oldResult.recycle()

        val ok = OutputSaver.savePng(this, result, "$outputName.png")

        binding.tvSavedInfo.visibility = View.VISIBLE
        binding.tvSavedInfo.text = if (ok) {
            "Saved ${size * 2} x $size icon to /ZFNFTSaves/$outputName.png"
        } else {
            "Something went wrong saving the icon"
        }
    }

    /** Draws [src] into the square cell starting at [left], either fitted inside or cropped to fill. */
    private fun drawCell(canvas: Canvas, paint: Paint, src: Bitmap, left: Int, size: Int, crop: Boolean) {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val scale = if (crop) max(size / sw, size / sh) else min(size / sw, size / sh)
        val dw = sw * scale
        val dh = sh * scale
        val dx = left + (size - dw) / 2f
        val dy = (size - dh) / 2f
        canvas.save()
        canvas.clipRect(left.toFloat(), 0f, (left + size).toFloat(), size.toFloat())
        canvas.drawBitmap(src, null, RectF(dx, dy, dx + dw, dy + dh), paint)
        canvas.restore()
    }
}
