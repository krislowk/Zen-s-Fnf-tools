package com.zen.fnftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zen.fnftools.databinding.ActivityIconGridBinding
import com.zen.fnftools.util.OutputSaver
import com.zen.fnftools.util.SaveAccess
import com.zen.fnftools.util.setBouncy
import com.zen.fnftools.util.staggerIn
import kotlin.math.min

class IconGridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIconGridBinding
    private var pickedBitmap: Bitmap? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val stream = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            pickedBitmap = bmp
            binding.ivIconPreview.setImageBitmap(bmp)
            binding.ivIconPreview.visibility = android.view.View.VISIBLE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIconGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickIcons.setBouncy()
        binding.btnConvert.setBouncy()

        staggerIn(listOf(binding.tvHeader, binding.tvBody, binding.btnPickIcons))

        binding.btnPickIcons.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnConvert.setOnClickListener { createIcon() }
    }

    private fun createIcon() {
        val source = pickedBitmap
        if (source == null) {
            Toast.makeText(this, "Pick an image first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!SaveAccess.isGranted()) {
            SaveAccess.requestAccess(this)
            return
        }

        val size = binding.etCellSize.text.toString().toIntOrNull() ?: 150
        val outputName = binding.etOutputName.text.toString().ifBlank { "icon" }

        // Center-crop to a square, then scale to the target size.
        val cropSize = min(source.width, source.height)
        val cropX = (source.width - cropSize) / 2
        val cropY = (source.height - cropSize) / 2
        val squared = Bitmap.createBitmap(source, cropX, cropY, cropSize, cropSize)
        val finalIcon = Bitmap.createScaledBitmap(squared, size, size, true)

        binding.ivIconPreview.setImageBitmap(finalIcon)

        val ok = OutputSaver.savePng(this, finalIcon, "$outputName.png")

        binding.tvSavedInfo.visibility = android.view.View.VISIBLE
        binding.tvSavedInfo.text = if (ok) {
            "Saved to /ZFNFTSaves/$outputName.png"
        } else {
            "Something went wrong saving the icon"
        }
    }
}
