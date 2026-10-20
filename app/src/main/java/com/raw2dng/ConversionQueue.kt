package com.raw2dng

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class ConversionTask(
    val inputUri: Uri,
    val inputPath: String,
    val outputPath: String,
    val fileName: String,
    val outputFormat: OutputFormat = OutputFormat.DNG
)

data class ConversionResult(
    val task: ConversionTask,
    val success: Boolean,
    val errorMessage: String = "",
    /** JPEG only: true when the camera's embedded JPEG was reused instead of rendering. */
    val usedEmbeddedJpeg: Boolean = false
)

/**
 * Manages parallel conversion of RAW files.
 *
 * Tasks are fed through an unbounded channel to a fixed pool of workers. The
 * channel stays open for the whole life of the queue, so tasks may be added at
 * any time (for example after the user confirms an overwrite) even after an
 * earlier batch has finished; [onAllComplete] is reported once for every point
 * at which all tasks known so far have completed. [cancel] closes the channel
 * and stops the workers.
 *
 * @param parallelism Maximum number of concurrent conversions (default: 2)
 * @param jpegQuality JPEG quality (1-100)
 * @param jpegChroma Chroma subsampling mode
 * @param jpegOptimize Enable Huffman table optimization
 * @param jpegEmbeddedPolicy When to reuse the camera's embedded JPEG instead of rendering
 */
class ConversionQueue(
    private val converter: DNGConverter,
    private val onTaskStarting: (task: ConversionTask, current: Int, total: Int) -> Unit,
    private val onTaskComplete: (ConversionResult) -> Unit,
    private val onAllComplete: (successful: Int, failed: Int) -> Unit,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val jpegQuality: Int = DNGConverter.DEFAULT_QUALITY,
    private val jpegChroma: Int = DNGConverter.DEFAULT_SUBSAMPLING,
    private val jpegOptimize: Boolean = DNGConverter.DEFAULT_OPTIMIZE,
    private val jpegEmbeddedPolicy: Int = DNGConverter.DEFAULT_EMBEDDED_POLICY
) {
    companion object {
        private const val TAG = "ConversionQueue"

        /**
         * Default degree of parallelism for conversions.
         * This can be configured via Settings (1 to number of CPU cores).
         */
        const val DEFAULT_PARALLELISM = 2
    }

    private val parallelism = parallelism.coerceAtLeast(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = mutableListOf<ConversionTask>()
    private val taskChannel = Channel<ConversionTask>(Channel.UNLIMITED)

    @Volatile
    private var isProcessing = false
    @Volatile
    private var isCancelled = false
    private var processingJob: Job? = null

    // Thread-safe counters
    private val startedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val successfulCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)

    /** Completed-task count at which [onAllComplete] was last reported, so it fires once per completion point. */
    private val reportedCompletion = AtomicInteger(-1)

    fun addTasks(newTasks: List<ConversionTask>) {
        synchronized(tasks) {
            tasks.addAll(newTasks)
        }
    }

    /**
     * Add a single task dynamically. Picked up by the workers immediately when
     * processing has started, otherwise when [start] is called.
     */
    fun addTaskDynamic(task: ConversionTask) {
        if (isCancelled) {
            Log.w(TAG, "Ignoring task ${task.fileName}: queue is cancelled")
            return
        }
        Log.d(TAG, "Adding dynamic task: ${task.fileName}")
        synchronized(tasks) {
            tasks.add(task)
        }

        if (!isProcessing) {
            // start() will enqueue everything in `tasks`, including this one.
            Log.d(TAG, "Starting processing for dynamic task")
            start()
            return
        }
        if (!taskChannel.trySend(task).isSuccess) {
            Log.e(TAG, "Could not enqueue ${task.fileName}: channel closed")
        }
    }

    private fun startProcessing() {
        if (isProcessing) return
        isProcessing = true

        processingJob = scope.launch {
            Log.d(TAG, "Starting parallel processing with parallelism=$parallelism")

            // Launch worker coroutines. They run until the channel is closed by cancel().
            val workers = (1..parallelism).map { workerId ->
                launch {
                    Log.d(TAG, "Worker $workerId started")
                    for (task in taskChannel) {
                        processTask(task, workerId)
                        checkCompletion()
                    }
                    Log.d(TAG, "Worker $workerId finished")
                }
            }

            workers.forEach { it.join() }
            isProcessing = false
            Log.d(TAG, "All workers completed")
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun checkCompletion() {
        val totalTasks = synchronized(tasks) { tasks.size }
        val completed = completedCount.get()

        if (completed >= totalTasks && taskChannel.isEmpty) {
            // Several workers can observe the same completion point; report it once.
            val lastReported = reportedCompletion.get()
            if (completed > lastReported && reportedCompletion.compareAndSet(lastReported, completed)) {
                withContext(Dispatchers.Main) {
                    onAllComplete(successfulCount.get(), failedCount.get())
                }
            }
        }
    }

    fun start() {
        if (isCancelled) {
            Log.w(TAG, "Queue is cancelled, cannot start")
            return
        }
        if (isProcessing) {
            Log.w(TAG, "Queue is already processing")
            return
        }

        // Reset counters
        startedCount.set(0)
        completedCount.set(0)
        successfulCount.set(0)
        failedCount.set(0)
        reportedCompletion.set(-1)

        // Copy tasks to avoid holding lock during send
        val tasksCopy = synchronized(tasks) { tasks.toList() }

        startProcessing()

        // trySend on an unbounded channel never suspends and preserves order.
        tasksCopy.forEach { task ->
            if (!taskChannel.trySend(task).isSuccess) {
                Log.e(TAG, "Could not enqueue ${task.fileName}: channel closed")
            }
        }

        if (tasksCopy.isEmpty()) {
            // Nothing can ever complete, so report the (empty) completion right away;
            // callers use it to show "awaiting confirmation" style states.
            scope.launch { checkCompletion() }
        }
    }

    private suspend fun processTask(task: ConversionTask, workerId: Int) {
        try {
            val taskNum = startedCount.incrementAndGet()
            val totalTasks = synchronized(tasks) { tasks.size }

            Log.d(TAG, "Worker $workerId processing: ${task.fileName} -> ${task.outputFormat}")

            withContext(Dispatchers.Main) {
                onTaskStarting(task, taskNum, totalTasks)
            }

            // Perform the conversion based on format
            var usedEmbeddedJpeg = false
            val errorMessage = when (task.outputFormat) {
                OutputFormat.DNG -> converter.convertToDNG(task.inputPath, task.outputPath)
                OutputFormat.JPEG -> {
                    val result = converter.convertToJPEG(
                        task.inputPath,
                        task.outputPath,
                        jpegQuality,
                        jpegChroma,
                        jpegOptimize,
                        jpegEmbeddedPolicy,
                        embedExif = true
                    )
                    usedEmbeddedJpeg = result.usedEmbeddedJpeg
                    if (result.success) {
                        Log.d(TAG, "JPEG ${task.fileName}: ${if (usedEmbeddedJpeg) "embedded camera JPEG" else "rendered from sensor data"} ${result.width}x${result.height}")
                        // Backends that cannot author EXIF themselves leave it to us.
                        if (!result.exifEmbedded && !ExifData.writeExifToJpeg(task.inputPath, task.outputPath, result.orientation)) {
                            Log.w(TAG, "Could not write EXIF metadata into ${task.fileName}; the JPEG is kept without it")
                        }
                        ""
                    } else {
                        result.errorMessage
                    }
                }
            }

            completedCount.incrementAndGet()

            val result = if (errorMessage.isEmpty()) {
                successfulCount.incrementAndGet()
                Log.d(TAG, "Worker $workerId success: ${task.fileName}")
                ConversionResult(task, true, usedEmbeddedJpeg = usedEmbeddedJpeg)
            } else {
                failedCount.incrementAndGet()
                Log.e(TAG, "Worker $workerId failed: ${task.fileName} - $errorMessage")
                ConversionResult(task, false, errorMessage)
            }

            withContext(Dispatchers.Main) {
                onTaskComplete(result)
            }
        } catch (e: CancellationException) {
            // The queue is being torn down; do not report a failure for a cancelled task.
            throw e
        } catch (e: Exception) {
            completedCount.incrementAndGet()
            failedCount.incrementAndGet()
            Log.e(TAG, "Worker $workerId exception processing ${task.fileName}", e)
            withContext(Dispatchers.Main) {
                onTaskComplete(ConversionResult(task, false, e.message ?: "Unknown error"))
            }
        }
    }

    fun clear() {
        synchronized(tasks) {
            tasks.clear()
        }
        startedCount.set(0)
        completedCount.set(0)
        successfulCount.set(0)
        failedCount.set(0)
        reportedCompletion.set(-1)
    }

    fun cancel() {
        isCancelled = true
        taskChannel.close()
        processingJob?.cancel()
        scope.cancel()
        isProcessing = false
    }

    fun getTaskCount() = synchronized(tasks) { tasks.size }
}
