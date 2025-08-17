// File: MainActivity.kt
// Description: Main activity for the MKTwo app, handling mantra recognition and recording.
package com.example.mktwo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.mktwo.databinding.ActivityMainBinding
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("mantra_matcher")
        }

        // Audio processing constants
        private const val MFCC_SIZE = 13
        private const val MFCC_WINDOW_SIZE = 50
        private const val SIMILARITY_THRESHOLD = 0.7f // Note: Changed to Float
    }

    // Native methods
    external fun extractMFCC(audioData: FloatArray): FloatArray
    external fun computeDTW(mfccSeq1: Array<FloatArray>, mfccSeq2: Array<FloatArray>): Float

    // App logic variables
    private val isRecognizingMantra = AtomicBoolean(false)
    private val isRecordingMantra = AtomicBoolean(false)
    private val matchCount = AtomicInteger(0)
    private var matchLimit = 0
    private var targetMantra = ""

    @Volatile
    private var referenceMFCCs: Map<String, List<FloatArray>> = emptyMap()
    private val referenceMFCCsLock = Any() // Lock for synchronizing access to referenceMFCCs

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val stopRecordingFlag = AtomicBoolean(false)

    // Audio constants
    private val sampleRate = 48000
    private val audioChannelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormatEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val tarsosProcessingBufferSizeSamples = 2048 // For MFCC extraction

    // Buffer size calculation
    private val audioRecordMinBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, audioChannelConfig, audioFormatEncoding).let { size ->
            if (size <= 0 || size == AudioRecord.ERROR || size == AudioRecord.ERROR_BAD_VALUE) {
                Log.w("MainActivity", "AudioRecord.getMinBufferSize returned error or invalid size: $size. Defaulting.")
                8192 // A reasonable default
            } else {
                size
            }
        }
    }
    private val actualRecordingBufferSize: Int by lazy {
        // It's often recommended to use a buffer size that's a multiple of the minBufferSize
        // and also suitable for your processing needs (e.g., tarsosProcessingBufferSizeSamples)
        val desiredSize = tarsosProcessingBufferSizeSamples * 2 // Example: ensure it's at least double processing buffer
        maxOf(audioRecordMinBufferSize * 2, desiredSize, 4096) // Ensure it's at least min * 2, desired, and a common power of 2
    }


    // Storage
    private val storageDir: File by lazy { File(filesDir, "mantras").apply { mkdirs() } }
    private val inbuiltMantraName = "testhello" // Consider making this a const if it never changes
    private val savedMantras: List<String>
        get() = storageDir.listFiles { file -> file.extension == "wav" && isValidWavFile(file) }
            ?.map { it.nameWithoutExtension }
            ?.sorted() ?: emptyList()

    // UI
    private lateinit var binding: ActivityMainBinding

    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "RECORD_AUDIO permission granted.")
            // Optionally, you might want to auto-start an action here if it was pending permission
        } else {
            Toast.makeText(this, "Microphone permission denied. Some features may not work.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUIListeners()

        copyInbuiltMantraToStorage()
        checkPermissionAndStart() // Request permission if not already granted
        loadReferenceMFCCs()    // Load initial set of mantras
    }

    private fun setupUIListeners() {
        binding.mantraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                targetMantra = parent.getItemAtPosition(position)?.toString() ?: ""
                Log.d("MainActivity", "Selected mantra: $targetMantra")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                targetMantra = ""
            }
        }

        binding.startStopButton.setOnClickListener {
            if (isRecognizingMantra.get()) {
                stopListening()
            } else {
                startListening()
            }
        }

        binding.recordButton.setOnClickListener {
            val mantraName = binding.mantraNameEditText.text.toString().trim()
            recordMantra(mantraName)
        }

        binding.stopRecordingButton.setOnClickListener { stopRecordingMantra() }

        binding.deleteButton.setOnClickListener {
            val mantraToDelete = targetMantra // Use the currently selected mantra for deletion
            if (mantraToDelete.isNotBlank()) {
                deleteMantra(mantraToDelete)
            } else {
                Toast.makeText(this, "Please select a mantra to delete.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun startListening() {
        if (targetMantra.isBlank()) {
            Toast.makeText(this, "Please select a mantra.", Toast.LENGTH_SHORT).show()
            return
        }
        val limitText = binding.matchLimitEditText.text.toString()
        matchLimit = limitText.toIntOrNull() ?: 0
        if (matchLimit <= 0) {
            Toast.makeText(this, "Enter a valid match limit (greater than 0).", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isMicrophoneOpAllowed()) {
            Toast.makeText(this, "Microphone access is currently blocked by the system.", Toast.LENGTH_LONG).show()
            // Consider directing the user to settings or providing more info
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required to start listening.", Toast.LENGTH_SHORT).show()
            checkPermissionAndStart() // Re-trigger permission request
            return
        }


        matchCount.set(0)
        binding.matchCountText.text = "Matches: 0"
        isRecognizingMantra.set(true)
        binding.startStopButton.text = getString(R.string.stop_listening_button_text) // Assuming you have this in strings.xml
        binding.statusText.text = getString(R.string.status_listening)
        startListeningWithDelay()
    }


    private fun copyInbuiltMantraToStorage() {
        val inbuiltFile = File(storageDir, "$inbuiltMantraName.wav")
        if (inbuiltFile.exists() && isValidWavFile(inbuiltFile)) {
            Log.d("MainActivity", "Inbuilt mantra already exists and is valid.")
            return
        }
        try {
            assets.open("$inbuiltMantraName.wav").use { inputStream ->
                FileOutputStream(inbuiltFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (!isValidWavFile(inbuiltFile)) {
                Log.e("MainActivity", "Copied inbuilt mantra is invalid. Deleting.")
                inbuiltFile.delete()
                Toast.makeText(this, "Error: Inbuilt mantra file format is invalid.", Toast.LENGTH_LONG).show()
            } else {
                Log.d("MainActivity", "Inbuilt mantra copied successfully.")
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Failed to copy inbuilt mantra from assets.", e)
            Toast.makeText(this, "Failed to load inbuilt mantra.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isValidWavFile(file: File): Boolean {
        if (!file.exists() || file.length() < 44) return false // Standard WAV header is 44 bytes
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) != 44) return false

                // Check RIFF and WAVE tags
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return false
                // Check "fmt " subchunk
                if (String(header, 12, 4) != "fmt ") return false

                val audioFormat = ByteBuffer.wrap(header, 20, 2).order(ByteOrder.LITTLE_ENDIAN).short
                val numChannels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short
                val fileSampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val bitsPerSample = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short

                return audioFormat.toInt() == 1 && // PCM
                        numChannels.toInt() == 1 && // Mono
                        fileSampleRate == this.sampleRate && // Match app's sample rate
                        bitsPerSample.toInt() == 16   // 16-bit
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "IOException during WAV file validation for ${file.name}", e)
            return false
        }
    }

    private fun loadWavToFloatArray(file: File): FloatArray? { // Return nullable for error cases
        if (!isValidWavFile(file)) {
            Log.e("MainActivity", "Attempted to load invalid WAV file: ${file.name}")
            return null
        }
        try {
            FileInputStream(file).use { inputStream ->
                inputStream.skip(44) // Skip WAV header
                val bytes = inputStream.readBytes()
                if (bytes.isEmpty()) return FloatArray(0)

                val shortBuffer = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

                return FloatArray(shortBuffer.size) { i ->
                    shortBuffer[i] / Short.MAX_VALUE.toFloat()
                }
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Error loading WAV file to FloatArray: ${file.name}", e)
            return null
        }
    }


    private fun writeWavHeader(outputStream: FileOutputStream, channels: Int, sampleRate: Int, bitsPerSample: Int, dataSize: Int = 0) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val overallSize = dataSize + 36 // 36 is header size without RIFF chunk size and data chunk size

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(overallSize) // File size - 8 bytes
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM (16 bytes)
        header.putShort(1) // AudioFormat for PCM (1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize) // Subchunk2Size (data size)

        outputStream.write(header.array())
    }


    @SuppressLint("MissingPermission") // Permissions are checked before calling
    private fun startListeningWithDelay() {
        // Double check permission just in case, though startListening() should cover it
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("MainActivity", "startListeningWithDelay called without RECORD_AUDIO permission.")
            isRecognizingMantra.set(false) // Ensure state is correct
            runOnUiThread {
                binding.startStopButton.text = getString(R.string.start_listening_button_text)
                binding.statusText.text = getString(R.string.status_stopped)
                Toast.makeText(this, "Microphone permission error.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isRecognizingMantra.get()) return@postDelayed // Check if still supposed to be recognizing

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    audioChannelConfig,
                    audioFormatEncoding,
                    actualRecordingBufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("MainActivity", "AudioRecord initialization failed. State: ${audioRecord?.state}")
                    throw IllegalStateException("AudioRecord initialization failed")
                }

                audioRecord?.startRecording()
                Log.d("MainActivity", "AudioRecord started recording.")

                val buffer = ShortArray(tarsosProcessingBufferSizeSamples)
                val mfccQueue = ArrayDeque<FloatArray>(MFCC_WINDOW_SIZE) // Use ArrayDeque

                recordingThread = Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) // Request higher priority
                    while (isRecognizingMantra.get() && !Thread.currentThread().isInterrupted) {
                        val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (shortsRead <= 0) { // Error or no data
                            if (shortsRead == AudioRecord.ERROR_INVALID_OPERATION || shortsRead == AudioRecord.ERROR_BAD_VALUE) {
                                Log.e("MainActivity", "AudioRecord read error: $shortsRead")
                                break // Exit loop on critical error
                            }
                            continue // Try reading again if just no data for a moment
                        }

                        val floatBuffer = FloatArray(shortsRead) { i -> buffer[i] / Short.MAX_VALUE.toFloat() }
                        val mfccs = extractMFCC(floatBuffer) // Native call

                        if (mfccs.size == MFCC_SIZE) {
                            synchronized(mfccQueue) { // Synchronize access to mfccQueue
                                if (mfccQueue.size == MFCC_WINDOW_SIZE) {
                                    mfccQueue.removeFirst()
                                }
                                mfccQueue.addLast(mfccs)
                            }
                        } else if (mfccs.isNotEmpty()) { // MFCCs might be empty if not enough audio data for a frame
                            Log.w("MainActivity", "MFCCs extracted with unexpected size: ${mfccs.size}")
                        }


                        val currentMfccSnapshot: Array<FloatArray>
                        synchronized(mfccQueue) { // Synchronize for consistent read
                            if (mfccQueue.size < MFCC_WINDOW_SIZE) continue // Not enough MFCCs yet
                            currentMfccSnapshot = mfccQueue.toTypedArray()
                        }


                        val refMfccList: List<FloatArray>?
                        synchronized(referenceMFCCsLock) {
                            refMfccList = referenceMFCCs[targetMantra]
                        }

                        if (refMfccList != null && refMfccList.isNotEmpty()) {
                            val similarity = computeDTW(currentMfccSnapshot, refMfccList.toTypedArray()) // Native call
                            // Log.d("MainActivity", "DTW Similarity for $targetMantra: $similarity")

                            if (similarity > SIMILARITY_THRESHOLD) {
                                val currentCount = matchCount.incrementAndGet()
                                runOnUiThread {
                                    binding.matchCountText.text = "Matches: $currentCount"
                                    Log.i("MainActivity", "Mantra '$targetMantra' matched! Count: $currentCount, Similarity: $similarity")
                                    if (currentCount >= matchLimit) {
                                        triggerAlarm()
                                    }
                                }
                                synchronized(mfccQueue) { // Clear queue after a match to reset context
                                    mfccQueue.clear()
                                }
                            }
                        }
                    }
                    Log.d("AudioProcessingThread", "Exiting listening loop.")
                }, "AudioProcessingThread")
                recordingThread?.start()

            } catch (e: Exception) { // Catch IllegalStateException from AudioRecord or others
                Log.e("MainActivity", "Error in audio processing or starting AudioRecord", e)
                runOnUiThread { Toast.makeText(this, "Failed to start listening: ${e.message}", Toast.LENGTH_LONG).show() }
                stopListening() // Clean up
            }
        }, 500) // Delay before starting to allow UI to settle or user to prepare
    }

    private fun stopListening() {
        if (!isRecognizingMantra.getAndSet(false) && recordingThread == null) {
            // Already stopped or never started
            return
        }
        Log.d("MainActivity", "Stopping listening...")

        recordingThread?.interrupt() // Signal the thread to stop
        try {
            recordingThread?.join(500) // Wait for the thread to finish for a short time
        } catch (e: InterruptedException) {
            Log.w("MainActivity", "Interrupted while joining recording thread.", e)
            Thread.currentThread().interrupt() // Restore interrupted status
        }
        recordingThread = null

        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    stop()
                } catch (e: IllegalStateException) {
                    Log.e("MainActivity", "IllegalStateException on audioRecord.stop()", e)
                }
            }
            try {
                release()
            } catch (e: Exception) {
                Log.e("MainActivity", "Exception on audioRecord.release()", e)
            }
        }
        audioRecord = null

        runOnUiThread {
            binding.statusText.text = getString(R.string.status_stopped)
            binding.startStopButton.text = getString(R.string.start_listening_button_text)
            // binding.matchCountText.text = "Matches: 0" // Optionally reset count display here or on start
        }
        Log.d("MainActivity", "Listening stopped.")
    }

    @SuppressLint("MissingPermission") // Permission check is done at the beginning
    private fun recordMantra(mantraName: String) {
        if (!isMicrophoneOpAllowed()) {
            Toast.makeText(this, "Microphone access blocked.", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required to record.", Toast.LENGTH_SHORT).show()
            checkPermissionAndStart() // Re-trigger permission request
            return
        }
        if (isRecognizingMantra.get() || isRecordingMantra.get()) {
            Toast.makeText(this, "Already processing audio. Please stop other operations first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (mantraName.isBlank()) {
            Toast.makeText(this, "Mantra name cannot be empty.", Toast.LENGTH_SHORT).show()
            return
        }
        if (mantraName == inbuiltMantraName) {
            Toast.makeText(this, "Cannot use the name of the inbuilt mantra.", Toast.LENGTH_SHORT).show()
            return
        }

        val sanitizedMantraName = mantraName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        var file = File(storageDir, "$sanitizedMantraName.wav")
        var uniqueFileName = sanitizedMantraName
        var counter = 1
        while (file.exists()) {
            uniqueFileName = "${sanitizedMantraName}_$counter"
            file = File(storageDir, "$uniqueFileName.wav")
            counter++
        }

        var localAudioRecord: AudioRecord? = null
        var outputStream: FileOutputStream? = null
        val recordingFile = file // Use a final variable for the lambda

        try {
            localAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                audioChannelConfig,
                audioFormatEncoding,
                actualRecordingBufferSize
            )

            if (localAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "Mantra recording AudioRecord initialization failed.")
                throw IllegalStateException("AudioRecord initialization failed for recording")
            }

            outputStream = FileOutputStream(recordingFile)
            writeWavHeader(outputStream, 1, sampleRate, 16) // Write initial header (data size 0)

            localAudioRecord.startRecording()
            isRecordingMantra.set(true)
            stopRecordingFlag.set(false) // Ensure flag is reset

            Log.d("MainActivity", "Started recording mantra: $uniqueFileName")
            runOnUiThread {
                binding.statusText.text = "Recording: $uniqueFileName..."
                // Consider disabling other buttons here
            }


            val buffer = ShortArray(actualRecordingBufferSize / 2) // Read in chunks that fit the buffer
            val byteDataBuffer = ByteBuffer.allocate(actualRecordingBufferSize).order(ByteOrder.LITTLE_ENDIAN) // Re-use this buffer

            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var totalShortsWritten: Long = 0
                try {
                    while (isRecordingMantra.get() && !stopRecordingFlag.get() && !Thread.currentThread().isInterrupted) {
                        val shortsRead = localAudioRecord.read(buffer, 0, buffer.size)
                        if (shortsRead > 0) {
                            byteDataBuffer.clear() // Prepare buffer for new data
                            // Put only the data that was read into the ShortBuffer view
                            byteDataBuffer.asShortBuffer().put(buffer, 0, shortsRead)
                            // Write the actual number of bytes
                            outputStream.write(byteDataBuffer.array(), 0, shortsRead * 2)
                            totalShortsWritten += shortsRead
                        } else if (shortsRead < 0) {
                            Log.e("MantraRecordingThread", "AudioRecord read error: $shortsRead")
                            break // Exit on error
                        }
                    }
                } catch (e: IOException) {
                    Log.e("MantraRecordingThread", "IOException during recording", e)
                } catch (e: Exception) {
                    Log.e("MantraRecordingThread", "Exception during recording", e)
                } finally {
                    Log.d("MantraRecordingThread", "Recording loop finished. Total shorts written: $totalShortsWritten")
                    if (localAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        localAudioRecord.stop()
                    }
                    localAudioRecord.release()

                    val recordedDataSize = totalShortsWritten * 2 // Each short is 2 bytes

                    try {
                        outputStream.channel.use { channel -> // Use .use for auto-close
                            if (recordedDataSize > 0) {
                                // Update ChunkSize (overall file size - 8)
                                val overallSize = recordedDataSize + 36
                                channel.position(4) // Position for ChunkSize
                                channel.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(overallSize.toInt()))

                                // Update Subchunk2Size (data size)
                                channel.position(40) // Position for Subchunk2Size
                                channel.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(recordedDataSize.toInt()))
                                Log.d("MantraRecordingThread", "WAV header updated. Data size: $recordedDataSize")
                            }
                        }
                    } catch (e: IOException) {
                        Log.e("MantraRecordingThread", "Error updating WAV header", e)
                    } finally {
                        try {
                            outputStream.close()
                        } catch (e: IOException) {
                            Log.e("MantraRecordingThread", "Error closing output stream", e)
                        }
                    }

                    runOnUiThread {
                        if (recordedDataSize > 0) {
                            Toast.makeText(applicationContext, "Mantra recorded: $uniqueFileName", Toast.LENGTH_SHORT).show()
                            loadReferenceMFCCs() // Reload and update spinner
                        } else {
                            if (recordingFile.exists()) recordingFile.delete() // Delete empty/failed recording
                            Toast.makeText(applicationContext, "Recording failed or was empty.", Toast.LENGTH_SHORT).show()
                        }
                        isRecordingMantra.set(false)
                        binding.statusText.text = getString(R.string.status_stopped)
                        // Re-enable buttons if they were disabled
                    }
                }
            }, "MantraRecordingThread")
            recordingThread?.start()

        } catch (e: Exception) { // Catch IllegalStateException, FileNotFoundException, etc.
            Log.e("MainActivity", "Error starting mantra recording", e)
            localAudioRecord?.release() // Ensure release if initialized
            try {
                outputStream?.close() // Ensure close if opened
            } catch (ioe: IOException) {
                Log.e("MainActivity", "Error closing stream on recording start failure", ioe)
            }
            // If the file was created but is essentially empty (only header), delete it
            if (file.exists() && file.length() <= 44L) { // 44L for the header size
                file.delete()
            }
            isRecordingMantra.set(false) // Reset state
            runOnUiThread {
                Toast.makeText(this, "Error starting recording: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.statusText.text = getString(R.string.status_stopped)
            }
        }
    }

    private fun stopRecordingMantra() {
        if (isRecordingMantra.get()) { // Only proceed if actually recording
            Log.d("MainActivity", "Stopping mantra recording...")
            stopRecordingFlag.set(true) // Signal the recording thread to stop writing data
            isRecordingMantra.set(false) // Set this early to prevent new recordings from starting

            // Interrupting and joining is handled by the finally block in the recording thread now.
            // The thread will stop AudioRecord and close the stream.
            // We just need to wait for it to finish gracefully.
            recordingThread?.interrupt() // Still good to interrupt in case it's in a blocking call
            try {
                recordingThread?.join(1000) // Wait a bit longer for file operations
                if (recordingThread?.isAlive == true) {
                    Log.w("MainActivity", "Mantra recording thread did not finish in time.")
                }
            } catch (e: InterruptedException) {
                Log.w("MainActivity", "Interrupted while waiting for mantra recording thread to finish.")
                Thread.currentThread().interrupt()
            }
            recordingThread = null

            runOnUiThread {
                binding.statusText.text = getString(R.string.status_stopped)
                Toast.makeText(this, "Recording stopped.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("MainActivity", "Stop recording called, but not currently recording.")
        }
    }


    private fun deleteMantra(mantraName: String) {
        if (mantraName.isBlank()) {
            Toast.makeText(this, "No mantra selected to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        if (mantraName == inbuiltMantraName) {
            Toast.makeText(this, "Cannot delete the inbuilt mantra.", Toast.LENGTH_SHORT).show()
            return
        }

        val fileToDelete = File(storageDir, "$mantraName.wav")
        if (!fileToDelete.exists()) {
            Toast.makeText(this, "Mantra file '$mantraName' not found.", Toast.LENGTH_SHORT).show()
            // It might be good to refresh the list in case of external changes
            loadReferenceMFCCs()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Mantra")
            .setMessage("Are you sure you want to delete the mantra '$mantraName'?")
            .setPositiveButton("Delete") { _, _ ->
                if (fileToDelete.delete()) {
                    Toast.makeText(this, "Mantra '$mantraName' deleted.", Toast.LENGTH_SHORT).show()
                    Log.i("MainActivity", "Deleted mantra: $mantraName")
                    if (targetMantra == mantraName) targetMantra = "" // Clear selection if deleted
                    loadReferenceMFCCs() // Refresh the list and spinner
                } else {
                    Toast.makeText(this, "Failed to delete mantra '$mantraName'.", Toast.LENGTH_SHORT).show()
                    Log.e("MainActivity", "Failed to delete mantra file: ${fileToDelete.absolutePath}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadReferenceMFCCs() {
        Log.d("MainActivity", "Loading reference MFCCs...")
        val tempReferenceMFCCs = mutableMapOf<String, List<FloatArray>>()
        val currentSavedMantras = savedMantras // Get a consistent list for this load operation

        // Add inbuilt mantra first if it exists and is valid
        val inbuiltFile = File(storageDir, "$inbuiltMantraName.wav")
        if (inbuiltFile.exists()) { // No need to check isValidWavFile here, savedMantras already does
            loadWavToFloatArray(inbuiltFile)?.let { floats ->
                if (floats.isNotEmpty()) {
                    val mfccs = mutableListOf<FloatArray>()
                    val chunkSize = tarsosProcessingBufferSizeSamples
                    for (i in floats.indices step chunkSize) {
                        val chunkEnd = min(i + chunkSize, floats.size)
                        val chunk = floats.copyOfRange(i, chunkEnd)
                        if (chunk.size == chunkSize) { // Process only full chunks for consistency
                            val mfcc = extractMFCC(chunk)
                            if (mfcc.size == MFCC_SIZE) mfccs.add(mfcc)
                        }
                    }
                    if (mfccs.isNotEmpty()) {
                        tempReferenceMFCCs[inbuiltMantraName] = mfccs
                        Log.d(
                            "MainActivity",
                            "Loaded ${mfccs.size} MFCC frames for inbuilt: $inbuiltMantraName"
                        )
                    } else {
                        Log.w(
                            "MainActivity",
                            "No MFCCs extracted for inbuilt mantra: $inbuiltMantraName (was valid WAV)"
                        )
                    }
                } else {
                    Log.w(
                        "MainActivity",
                        "Inbuilt mantra file loaded empty float array: $inbuiltMantraName"
                    )
                }
            } ?: Log.e(
                "MainActivity",
                "Failed to load float array for inbuilt mantra: $inbuiltMantraName"
            )
        } else if (assets.list("")?.contains("$inbuiltMantraName.wav") == true) {
            // This case might indicate copyInbuiltMantraToStorage failed or was skipped
            Log.w(
                "MainActivity",
                "Inbuilt mantra file not found in storage, but exists in assets. Consider re-copying."
            )
        }


        currentSavedMantras.forEach { mantraName ->
            if (mantraName == inbuiltMantraName && tempReferenceMFCCs.containsKey(inbuiltMantraName)) {
                // Already processed inbuilt mantra
                return@forEach
            }
            val file = File(storageDir, "$mantraName.wav")
            // isValidWavFile check is implicitly done by `savedMantras` getter, but double check doesn't hurt if paranoid
            // if (!isValidWavFile(file)) return@forEach

            loadWavToFloatArray(file)?.let { floats ->
                if (floats.isNotEmpty()) {
                    val mfccs = mutableListOf<FloatArray>()
                    val chunkSize = tarsosProcessingBufferSizeSamples
                    for (i in floats.indices step chunkSize) {
                        val chunkEnd =
                            min(i + chunkSize, floats.size) // Ensure we don't go out of bounds
                        val chunk = floats.copyOfRange(i, chunkEnd)
                        // For reference MFCCs, it's common to require the chunk to be of a specific size
                        // to ensure consistency with the live processing.
                        if (chunk.size == chunkSize) {
                            val mfcc = extractMFCC(chunk)
                            if (mfcc.size == MFCC_SIZE) { // Ensure MFCC vector is of expected size
                                mfccs.add(mfcc)
                            } else if (mfcc.isNotEmpty()) {
                                Log.w(
                                    "MainActivity",
                                    "MFCC for $mantraName (chunk $i) has unexpected size: ${mfcc.size}"
                                )
                            }
                        }
                    }
                    if (mfccs.isNotEmpty()) {
                        tempReferenceMFCCs[mantraName] = mfccs
                        Log.d("MainActivity", "Loaded ${mfccs.size} MFCC frames for: $mantraName")
                    } else {
                        Log.w("MainActivity", "No MFCCs extracted for $mantraName (was valid WAV)")
                    }
                } else {
                    Log.w("MainActivity", "Mantra file loaded empty float array: $mantraName")
                }
            } ?: Log.e("MainActivity", "Failed to load float array for $mantraName")
        }
    }
    private fun updateMantraSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, savedMantras)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.mantraSpinner.adapter = adapter
    }

    private fun triggerAlarm() {
        isRecognizingMantra.set(false)
        stopListening()
        ToneGenerator(AudioManager.STREAM_MUSIC, 100).apply {
            startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            Handler(Looper.getMainLooper()).postDelayed({ release() }, 500)
        }
        AlertDialog.Builder(this)
            .setTitle("Mantra Limit Reached")
            .setMessage("The target number of mantra recitations ($matchLimit) has been reached.")
            .setPositiveButton("OK & Continue") { _, _ ->
                matchCount.set(0)
                if (targetMantra.isNotBlank() && matchLimit > 0) {
                    isRecognizingMantra.set(true)
                    binding.startStopButton.text = "Stop Listening"
                    binding.statusText.text = "Listening for mantra..."
                    startListeningWithDelay()
                }
            }
            .setNegativeButton("Stop") { _, _ -> matchCount.set(0) }
            .setCancelable(false)
            .show()
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun isMicrophoneOpAllowed(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow("android:record_audio", Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow("android:record_audio", Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onPause() {
        super.onPause()
        if (isRecognizingMantra.get()) stopListening()
        if (isRecordingMantra.get()) stopRecordingMantra()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        stopRecordingMantra()
    }
}