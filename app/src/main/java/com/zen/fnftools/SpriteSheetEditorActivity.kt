package com.zen.fnftools

import android.graphics.Bitmap
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zen.fnftools.databinding.ActivitySpriteSheetEditorBinding
import com.zen.fnftools.util.NamedBitmap
import com.zen.fnftools.util.TempFrameStore
import com.zen.fnftools.util.setBouncy
import org.xmlpull.v1.XmlPullParser

/** Imports an existing TextureAtlas PNG/XML pair, extracts frames, and lets the existing rename/pack screen rebuild the atlas. */
class SpriteSheetEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySpriteSheetEditorBinding
    private var sheetUri: Uri? = null
    private var xmlUri: Uri? = null
    private var addedUris: List<Uri> = emptyList()

    private val pickSheet = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        sheetUri = uri
        binding.tvSheetStatus.text = if (uri == null) "No spritesheet selected" else "Spritesheet PNG selected"
        updateReadyState()
    }
    private val pickXml = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        xmlUri = uri
        binding.tvXmlStatus.text = if (uri == null) "No XML selected" else "TextureAtlas XML selected"
        updateReadyState()
    }
    private val pickAdditionalImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        addedUris = uris
        binding.tvAddedStatus.text = "${uris.size} additional image${if (uris.size == 1) "" else "s"} selected"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpriteSheetEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnPickSheet.setBouncy()
        binding.btnPickXml.setBouncy()
        binding.btnAddImages.setBouncy()
        binding.btnOpenEditor.setBouncy()
        binding.btnPickSheet.setOnClickListener { pickSheet.launch("image/png") }
        binding.btnPickXml.setOnClickListener { pickXml.launch("*/*") }
        binding.btnAddImages.setOnClickListener { pickAdditionalImages.launch("image/png") }
        binding.btnOpenEditor.setOnClickListener { loadFramesAndOpenEditor() }
        updateReadyState()
    }

    private fun updateReadyState() {
        binding.btnOpenEditor.isEnabled = sheetUri != null && xmlUri != null
    }

    private fun loadFramesAndOpenEditor() {
        val sheet = sheetUri ?: return
        val xml = xmlUri ?: return
        binding.btnOpenEditor.isEnabled = false
        val requestedOutputName = binding.etOutputName.text.toString().trim().ifBlank { "spritesheet_edited" }
        val outputName = requestedOutputName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').ifBlank { "spritesheet_edited" }
        Thread {
            try {
                val sheetBitmap = contentResolver.openInputStream(sheet)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalArgumentException("Couldn't decode the spritesheet PNG")
                val xmlText = contentResolver.openInputStream(xml)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("Couldn't read the XML file")
                val atlasFrames = parseFrames(xmlText)
                if (atlasFrames.isEmpty()) throw IllegalArgumentException("The XML contains no SubTexture frames")

                val frames = ArrayList<NamedBitmap>(atlasFrames.size + addedUris.size)
                try {
                    for (frame in atlasFrames) {
                        if (frame.x < 0 || frame.y < 0 || frame.width <= 0 || frame.height <= 0 ||
                            frame.x + frame.width > sheetBitmap.width || frame.y + frame.height > sheetBitmap.height) {
                            throw IllegalArgumentException("Frame '${frame.name}' has coordinates outside the PNG")
                        }
                        if (frame.rotated) throw IllegalArgumentException("Rotated frame '${frame.name}' isn't supported yet. Use a non-rotated atlas XML.")
                        val crop = Bitmap.createBitmap(sheetBitmap, frame.x, frame.y, frame.width, frame.height)
                        if (frame.frameWidth > 0 && frame.frameHeight > 0 &&
                            (frame.frameWidth != frame.width || frame.frameHeight != frame.height || frame.frameX != 0 || frame.frameY != 0)) {
                            val restored = Bitmap.createBitmap(frame.frameWidth, frame.frameHeight, Bitmap.Config.ARGB_8888)
                            android.graphics.Canvas(restored).drawBitmap(crop, (-frame.frameX).toFloat(), (-frame.frameY).toFloat(), null)
                            crop.recycle()
                            frames.add(NamedBitmap(frame.name, restored))
                        } else {
                            frames.add(NamedBitmap(frame.name, crop))
                        }
                    }
                    for ((index, uri) in addedUris.withIndex()) {
                        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                            ?: continue
                        val base = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                            ?.takeIf { it.isNotBlank() } ?: "added_frame_${index + 1}"
                        frames.add(NamedBitmap(base, bitmap))
                    }
                    if (frames.isEmpty()) throw IllegalArgumentException("No usable frames were found")
                    TempFrameStore.frames = frames
                    val intent = Intent(this, FrameManagerActivity::class.java).apply {
                        putExtra(FrameManagerActivity.EXTRA_MODE, "shelf")
                        putExtra(FrameManagerActivity.EXTRA_OUTPUT_NAME, outputName)
                    }
                    runOnUiThread { startActivity(intent); binding.btnOpenEditor.isEnabled = true }
                } catch (e: Exception) {
                    frames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    throw e
                } finally {
                    if (!sheetBitmap.isRecycled) sheetBitmap.recycle()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnOpenEditor.isEnabled = true
                    Toast.makeText(this, e.message ?: "Couldn't load spritesheet", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private data class XmlFrame(
        val name: String, val x: Int, val y: Int, val width: Int, val height: Int,
        val frameX: Int, val frameY: Int, val frameWidth: Int, val frameHeight: Int, val rotated: Boolean
    )

    private fun parseFrames(xml: String): List<XmlFrame> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val frames = mutableListOf<XmlFrame>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("SubTexture", ignoreCase = true)) {
                fun attr(name: String, default: Int = 0) = parser.getAttributeValue(null, name)?.toIntOrNull() ?: default
                val name = parser.getAttributeValue(null, "name") ?: "frame_${frames.size}"
                frames.add(XmlFrame(name, attr("x"), attr("y"), attr("width"), attr("height"),
                    attr("frameX"), attr("frameY"), attr("frameWidth"), attr("frameHeight"),
                    parser.getAttributeValue(null, "rotated").equals("true", ignoreCase = true)))
            }
            event = parser.next()
        }
        return frames
    }
}
