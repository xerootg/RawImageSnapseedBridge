package com.raw2dng

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*

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

class ConversionQueue(
    private val converter: DNGConverter,
    private val onTaskStarting: (task: ConversionTask, current: Int, total: Int) -> Unit,
    private val onTaskComplete: (ConversionResult) -> Unit,
    private val onAllComplete: (successful: Int, failed: Int) -> Unit
) {
    private val tag = "ConversionQueue"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = mutableListOf<ConversionTask>()
    private var isProcessing = false
    private var completed = 0
    private var successful = 0
    private var failed = 0

    fun addTasks(newTasks: List<ConversionTask>) {
        tasks.addAll(newTasks)
    }
    
    /**
     * Add a single task dynamically. Will be picked up by the processing loop.
     */
    fun addTaskDynamic(task: ConversionTask) {
        Log.d(tag, "Adding dynamic task: ${task.fileName}")
        synchronized(tasks) {
            tasks.add(task)
        }
        
        // If not currently processing, start processing
        if (!isProcessing) {
            Log.d(tag, "Starting processing for dynamic task")
            processPendingTasks()
        }
    }
    
    private fun processPendingTasks() {
        if (isProcessing) return
        
        isProcessing = true
        scope.launch {
            while (true) {
                val task = synchronized(tasks) {
                    if (completed < tasks.size) {
                        tasks[completed]
                    } else {
                        null
                    }
                }
                
                if (task != null) {
                    processTask(task)
                } else {
                    break
                }
            }
            
            isProcessing = false
            withContext(Dispatchers.Main) {
                onAllComplete(successful, failed)
            }
        }
    }

    fun start() {
        if (isProcessing) {
            Log.w(tag, "Queue is already processing")
            return
        }

        completed = 0
        successful = 0
        failed = 0

        processPendingTasks()
    }

    private suspend fun processTask(task: ConversionTask) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Processing: ${task.fileName} -> ${task.outputFormat}")

                completed++
                withContext(Dispatchers.Main) {
                    onTaskStarting(task, completed, tasks.size)
                }

                // Perform the conversion based on format
                val errorMessage = when (task.outputFormat) {
                    OutputFormat.DNG -> converter.convertToDNG(task.inputPath, task.outputPath)
                    OutputFormat.JPEG -> converter.convertToJPEG(task.inputPath, task.outputPath, 90)
                }

                val result = if (errorMessage.isEmpty()) {
                    successful++
                    Log.d(tag, "Success: ${task.fileName}")
                    ConversionResult(task, true)
                } else {
                    failed++
                    Log.e(tag, "Failed: ${task.fileName} - $errorMessage")
                    ConversionResult(task, false, errorMessage)
                }

                withContext(Dispatchers.Main) {
                    onTaskComplete(result)
                }

            } catch (e: Exception) {
                failed++
                Log.e(tag, "Exception processing ${task.fileName}", e)
                withContext(Dispatchers.Main) {
                    onTaskComplete(ConversionResult(task, false, e.message ?: "Unknown error"))
                }
            }
        }
    }

    fun clear() {
        tasks.clear()
        completed = 0
        successful = 0
        failed = 0
    }

    fun cancel() {
        scope.cancel()
        isProcessing = false
    }

    fun getTaskCount() = tasks.size
}
