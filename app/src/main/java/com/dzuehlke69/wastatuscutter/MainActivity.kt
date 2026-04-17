package com.dzuehlke69.wastatuscutter

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dzuehlke69.wastatuscutter.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) processVideo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Alte Reste beim Start entfernen
        clearWAcutsFolder()

        // Button: Video wählen
        binding.btnPickVideo.setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }

        // Button: BTC Spende
        binding.btnDonateBTC.setOnClickListener {
            showBTCDialog()
        }
    }

    private fun clearWAcutsFolder() {
        try {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("Movies/WAcuts%")
            contentResolver.delete(collection, selection, selectionArgs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processVideo(inputUri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Video wird zerstückelt..."
        binding.btnPickVideo.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                clearWAcutsFolder()
                splitVideo(inputUri)

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Fertig! Öffne WhatsApp..."
                    shareToWhatsApp()
                    binding.btnPickVideo.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnPickVideo.isEnabled = true
                    Toast.makeText(this@MainActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun splitVideo(inputUri: Uri) {
        val maxSegmentDurationUs = 59 * 1000000L
        var currentStartUs = 0L
        var partIndex = 1

        val extractor = MediaExtractor()
        contentResolver.openFileDescriptor(inputUri, "r")?.use { fd ->
            extractor.setDataSource(fd.fileDescriptor)
        }

        val videoTrack = findTrack(extractor, "video/")
        val audioTrack = findTrack(extractor, "audio/")
        if (videoTrack < 0) return

        val totalDurationUs = extractor.getTrackFormat(videoTrack).let {
            if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L
        }

        while (currentStartUs < totalDurationUs) {
            val outputUri =
                createVideoEntry("WA_Status_${String.format("%03d", partIndex)}.mp4") ?: break
            contentResolver.openFileDescriptor(outputUri, "rw")?.use { outFd ->
                val muxer =
                    MediaMuxer(outFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val newV = muxer.addTrack(extractor.getTrackFormat(videoTrack))
                val newA =
                    if (audioTrack >= 0) muxer.addTrack(extractor.getTrackFormat(audioTrack)) else -1
                muxer.start()

                val buffer = ByteBuffer.allocate(1024 * 1024 * 5)
                val info = MediaCodec.BufferInfo()

                extractor.selectTrack(videoTrack)
                if (audioTrack >= 0) extractor.selectTrack(audioTrack)

                extractor.seekTo(currentStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val actualStartUs = extractor.sampleTime
                var lastSampleTime = actualStartUs

                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime > actualStartUs + maxSegmentDurationUs) break

                    info.size = extractor.readSampleData(buffer, 0)
                    info.offset = 0
                    info.flags = extractor.sampleFlags
                    info.presentationTimeUs = sampleTime - actualStartUs

                    val targetTrack = if (extractor.sampleTrackIndex == videoTrack) newV else newA
                    if (targetTrack >= 0) muxer.writeSampleData(targetTrack, buffer, info)

                    if (extractor.sampleTrackIndex == videoTrack) lastSampleTime = sampleTime
                    extractor.advance()
                }
                muxer.stop()
                muxer.release()
                currentStartUs = lastSampleTime + 1000
            }
            partIndex++
            if (currentStartUs >= totalDurationUs - 100000L) break
        }
        extractor.release()
    }

    private fun shareToWhatsApp() {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Movies/WAcuts%")
        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"

        val videoUris = ArrayList<Uri>()
        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    videoUris.add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                }
            }

        if (videoUris.isNotEmpty()) {
            videoUris.reverse() // Trick für richtige Reihenfolge im Status
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, videoUris)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
            }
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith(prefix) == true
            ) return i
        }
        return -1
    }

    private fun createVideoEntry(name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WAcuts")
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    // --- NEUE FUNKTIONEN FÜR VERSION 2.0.0 ---

    private fun showBTCDialog() {
        val btcAddress = "bc1q7zgh4x5d7295rtefall4qw0sg8dl674kvsl62r"

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
            gravity = android.view.Gravity.CENTER
        }

        // Dein eigenes QR-Code Bild aus den Drawables
        val qrCodeImage = android.widget.ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(600, 600)
            setImageResource(R.drawable.btc_qr) // Hier wird dein Bild geladen!
        }

        val addressText = android.widget.TextView(this).apply {
            text = btcAddress
            textSize = 12f
            setPadding(0, 30, 0, 0)
            gravity = android.view.Gravity.CENTER
            setTextIsSelectable(true)
        }

        layout.addView(qrCodeImage)
        layout.addView(addressText)

        android.app.AlertDialog.Builder(this)
            .setTitle("BTC Spende ₿")
            .setView(layout)
            .setPositiveButton("Adresse kopieren") { _, _ ->
                val clipboard =
                    getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("BTC Address", btcAddress)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Kopiert!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fertig", null)
            .show()
    }
}