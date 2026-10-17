package com.zen.fnftools

import android.os.Bundle
import android.view.View
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zen.fnftools.util.PackSpritesheetWorker
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.zen.fnftools.databinding.ActivityFrameManagerBinding
import com.zen.fnftools.util.OutputSaver
import com.zen.fnftools.util.TempFrameStore
import com.zen.fnftools.util.setBouncy

class FrameManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"           // "shelf" or "grid"
        const val EXTRA_OUTPUT_NAME = "outputName"
        const val EXTRA_CELL_SIZE = "cellSize"   // grid mode only
        const val EXTRA_COLUMNS = "columns"      // grid mode only
    }

    private lateinit var binding: ActivityFrameManagerBinding
    private lateinit var adapter: FrameAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFrameManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val frames = TempFrameStore.frames
        if (frames.isEmpty()) {
            Toast.makeText(this, "No frames to rename — pick some first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        adapter = FrameAdapter(frames)
        binding.rvFrames.layoutManager = LinearLayoutManager(this)
        binding.rvFrames.adapter = adapter
        // Keep the full frame list immediately available; stagger animations can make
        // long lists appear incomplete on slower mobile devices.
        binding.rvFrames.layoutAnimation = null
        binding.rvFrames.setHasFixedSize(false)
        binding.rvFrames.isNestedScrollingEnabled = true

        binding.cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) adapter.selectAll() else adapter.clearSelection()
        }

        binding.btnApplyBulk.setBouncy()
        binding.btnApplyBulk.setOnClickListener {
            val newPrefix = binding.etBulkPrefix.text.toString()
            if (adapter.selectedCount() == 0) {
                Toast.makeText(this, "Check the frames you want to rename first", Toast.LENGTH_SHORT).show()
            } else {
                val count = adapter.selectedCount()
                adapter.applyPrefixToSelected(newPrefix)
                binding.cbSelectAll.setOnCheckedChangeListener(null)
                binding.cbSelectAll.isChecked = false
                binding.cbSelectAll.setOnCheckedChangeListener { _, checked ->
                    if (checked) adapter.selectAll() else adapter.clearSelection()
                }
                Toast.makeText(this, "Renamed $count frames", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPackSave.setBouncy()
        binding.btnPackSave.setOnClickListener { packAndSave() }
    }

    private fun packAndSave() {
        if (!com.zen.fnftools.util.SaveAccess.isGranted()) {
            com.zen.fnftools.util.SaveAccess.requestAccess(this)
            return
        }
        val frames = TempFrameStore.frames.toList()
        if (frames.isEmpty()) {
            Toast.makeText(this, "No frames to pack", Toast.LENGTH_SHORT).show()
            return
        }
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.tvLoadingLabel.text = "Preparing durable packing job…"
        binding.btnPackSave.isEnabled = false

        Thread {
            try {
                val jobDir = File(filesDir, "pack-jobs/${UUID.randomUUID()}")
                if (!jobDir.mkdirs() && !jobDir.isDirectory) throw IllegalStateException("Cannot create packing job folder")
                val names = StringBuilder()
                frames.forEachIndexed { index, frame ->
                    val file = File(jobDir, "frame_${index.toString().padStart(5, '0')}.png")
                    FileOutputStream(file).use { out ->
                        if (!frame.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IllegalStateException("Could not prepare frame ${index + 1}")
                        }
                    }
                    names.append(android.util.Base64.encodeToString(frame.name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE))
                        .append('\n')
                }
                File(jobDir, "names.txt").writeText(names.toString(), Charsets.UTF_8)
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "shelf"
                val requestedName = intent.getStringExtra(EXTRA_OUTPUT_NAME)?.ifBlank { "spritesheet" } ?: "spritesheet"
                val outputName = requestedName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').ifBlank { "spritesheet" }
                val request = OneTimeWorkRequestBuilder<PackSpritesheetWorker>()
                    .setInputData(workDataOf(
                        PackSpritesheetWorker.KEY_JOB_DIR to jobDir.absolutePath,
                        PackSpritesheetWorker.KEY_OUTPUT_NAME to outputName,
                        PackSpritesheetWorker.KEY_MODE to mode,
                        PackSpritesheetWorker.KEY_CELL_SIZE to intent.getIntExtra(EXTRA_CELL_SIZE, 150),
                        PackSpritesheetWorker.KEY_COLUMNS to intent.getIntExtra(EXTRA_COLUMNS, 0)
                    ))
                    .build()
                WorkManager.getInstance(applicationContext).enqueue(request)
                runOnUiThread {
                    binding.tvLoadingLabel.text = "Packing in background…"
                    WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(request.id)
                        .observe(this) { info ->
                            if (info == null) return@observe
                            binding.tvLoadingLabel.text = when (info.state) {
                                WorkInfo.State.ENQUEUED -> "Waiting to pack…"
                                WorkInfo.State.RUNNING -> "Packing spritesheet…"
                                WorkInfo.State.SUCCEEDED -> {
                                    binding.loadingOverlay.visibility = View.GONE
                                    binding.btnPackSave.isEnabled = true
                                    val output = File(com.zen.fnftools.util.OutputSaver.saveDir(), "$outputName.png")
                                    val bitmap = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
                                    if (bitmap != null) showPreview(bitmap, outputName)
                                    else Toast.makeText(this, "Packing finished, but preview could not be loaded. Check /ZFNFTSaves.", Toast.LENGTH_LONG).show()
                                    "Finished"
                                }
                                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                    binding.loadingOverlay.visibility = View.GONE
                                    binding.btnPackSave.isEnabled = true
                                    Toast.makeText(this, info.outputData.getString(PackSpritesheetWorker.KEY_ERROR) ?: "Packing failed. Your source frames remain available; try again.", Toast.LENGTH_LONG).show()
                                    "Failed"
                                }
                                else -> "Packing spritesheet…"
                            }
                        }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnPackSave.isEnabled = true
                    Toast.makeText(this, "Couldn't prepare packing job: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPreview(packed: android.graphics.Bitmap, outputName: String) {
        binding.rvFrames.visibility = android.view.View.GONE
        binding.cbSelectAll.visibility = android.view.View.GONE
        binding.etBulkPrefix.visibility = android.view.View.GONE
        binding.btnApplyBulk.visibility = android.view.View.GONE
        binding.btnPackSave.visibility = android.view.View.GONE

        binding.ivPreview.setImageBitmap(packed)
        binding.ivPreview.visibility = android.view.View.VISIBLE

        binding.tvSavedInfo.text = "Saved to /ZFNFTSaves/$outputName.png + $outputName.xml"
        binding.tvSavedInfo.visibility = android.view.View.VISIBLE

        binding.btnDone.visibility = android.view.View.VISIBLE
        binding.btnDone.setBouncy()
        binding.btnDone.setOnClickListener {
            TempFrameStore.clear()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Make sure a cancelled rename session doesn't leak bitmaps into the next screen.
        if (isFinishing) TempFrameStore.clear()
    }
}
