package com.raw2dng

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
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
    val errorMessage: String = ""
)

/**
 * Manages parallel conversion of RAW files.
 * 
 * @param parallelism Maximum number of concurrent conversions (default: 2)
 */
class ConversionQueue(
    private val converter: DNGConverter,
    private val onTaskStarting: (task: ConversionTask, current: Int, total: Int) -> Unit,
    private val onTaskComplete: (ConversionResult) -> Unit,
    private val onAllComplete: (successful: Int, failed: Int) -> Unit,
    private val parallelism: Int = DEFAULT_PARALLELISM
) {
    companion object {
        private const val TAG = "ConversionQueue"
        
        /**
         * Default degree of parallelism for conversions.
         * TODO: Make this configurable via settings.
         */
        const val DEFAULT_PARALLELISM = 2
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = mutableListOf<ConversionTask>()
    private val taskChannel = Channel<ConversionTask>(Channel.UNLIMITED)
    private val semaphore = Semaphore(parallelism)
    
    private var isProcessing = false
    private var processingJob: Job? = null
    
    // Thread-safe counters
    private val startedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val successfulCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)

    fun addTasks(newTasks: List<ConversionTask>) {
        synchronized(tasks) {
            tasks.addAll(newTasks)
        }
    }
    
    /**
     * Add a single task dynamically. Will be picked up by workers if processing.
     */
    fun addTaskDynamic(task: ConversionTask) {
        Log.d(TAG, "Adding dynamic task: ${task.fileName}")
        synchronized(tasks) {
            tasks.add(task)
        }
        
        // Send to channel for processing
        scope.launch {
            taskChannel.send(task)
        }
        
        // If not currently processing, start processing
        if (!isProcessing) {
            Log.d(TAG, "Starting processing for dynamic task")
            startProcessing()
        }
    }
    
    private fun startProcessing() {
        if (isProcessing) return
        
        isProcessing = true
        
        processingJob = scope.launch {
            Log.d(TAG, "Starting parallel processing with parallelism=$parallelism")
            
            // Launch worker coroutines
            val workers = (1..parallelism).map { workerId ->
                launch {
                    Log.d(TAG, "Worker $workerId started")
                    for (task in taskChannel) {
                        semaphore.acquire()
                        try {
                            processTask(task, workerId)
                        } finally {
                            semaphore.release()
                        }
                        
                        // Check if all tasks are complete
                        checkCompletion()
                    }
                    Log.d(TAG, "Worker $workerId finished")
                }
            }
            
            // Wait for all workers to complete
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
            // All tasks done, close channel and report
            taskChannel.close()
            
            withContext(Dispatchers.Main) {
                onAllComplete(successfulCount.get(), failedCount.get())
            }
        }
    }

    fun start() {
        if (isProcessing) {
            Log.w(TAG, "Queue is already processing")
            return
        }

        // Reset counters
        startedCount.set(0)
        completedCount.set(0)
        successfulCount.set(0)
        failedCount.set(0)
        
        // Copy tasks to avoid holding lock during send
        val tasksCopy = synchronized(tasks) { tasks.toList() }
        
        // Send all tasks to channel
        scope.launch {
            tasksCopy.forEach { task ->
                taskChannel.send(task)
            }
        }

        startProcessing()
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
            val errorMessage = when (task.outputFormat) {
                OutputFormat.DNG -> converter.convertToDNG(task.inputPath, task.outputPath)
                OutputFormat.JPEG -> converter.convertToJPEG(
                    task.inputPath, 
                    task.outputPath, 
                    DNGConverter.DEFAULT_QUALITY,
                    DNGConverter.DEFAULT_SUBSAMPLING,
                    DNGConverter.DEFAULT_OPTIMIZE
                )
            }

            completedCount.incrementAndGet()
            
            val result = if (errorMessage.isEmpty()) {
                successfulCount.incrementAndGet()
                Log.d(TAG, "Worker $workerId success: ${task.fileName}")
                ConversionResult(task, true)
            } else {
                failedCount.incrementAndGet()
                Log.e(TAG, "Worker $workerId failed: ${task.fileName} - $errorMessage")
                ConversionResult(task, false, errorMessage)
            }

            withContext(Dispatchers.Main) {
                onTaskComplete(result)
            }

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
    }

    fun cancel() {
        processingJob?.cancel()
        taskChannel.close()
        scope.cancel()
        isProcessing = false
    }

    fun getTaskCount() = synchronized(tasks) { tasks.size }
}
