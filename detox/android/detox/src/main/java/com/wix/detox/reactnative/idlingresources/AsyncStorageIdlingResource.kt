package com.wix.detox.reactnative.idlingresources

import android.util.Log
import androidx.test.espresso.IdlingResource
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactContext
import org.joor.Reflect
import java.util.concurrent.Executor

class SerialExecutorReflected(private val executor: Reflect) {
    fun hasPendingTasks(): Boolean = pendingTasks().isNotEmpty()
    fun hasActiveTask(): Boolean = (activeTask() != null)
    fun getActiveTask(): Runnable? = activeTask()
    fun executeTask(runnable: Runnable) = (executor.get() as Executor).execute(runnable)

    private fun pendingTasks() = executor.field("mTasks").get<Collection<Any>>()
    private fun activeTask() = executor.field("mActive").get<Runnable?>()
}

private class ModuleReflected(module: NativeModule) {
    private val reflected = Reflect.on(module)
    private val executorReflected = SerialExecutorReflected(reflected.field("executor"))

    val sexecutor: SerialExecutorReflected
        get() = executorReflected
}

class AsyncStorageIdlingResource private constructor(module: NativeModule): IdlingResource {
    private val moduleReflected = ModuleReflected(module)
    private var callback: IdlingResource.ResourceCallback? = null
    private lateinit var interrogationRunnable: Runnable

    init {
        interrogationRunnable = Runnable {
            synchronized(moduleReflected.sexecutor) {
                if (!moduleReflected.sexecutor.hasPendingTasks()) {
                    this.callback?.onTransitionToIdle()
                    return@Runnable
                }
            }
            moduleReflected.sexecutor.executeTask(this.interrogationRunnable)
        }
    }

    override fun getName(): String = this.javaClass.name

    override fun isIdleNow() = checkIdle().also { result ->
        if (!result) {
            Log.d(LOG_TAG, "Async-storage is busy!")
            enqueueInterrogationTask() // TODO should we care about multiple tasks in queue, overly notifying the idle-callback?
        }
    }

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        this.callback = callback
        enqueueInterrogationTask()
    }

    private fun checkIdle(): Boolean {
        synchronized(moduleReflected.sexecutor) {
            with (moduleReflected.sexecutor) {
                return (hasActiveTask() && getActiveTask() != interrogationRunnable) || hasPendingTasks()
            }
        }
    }

    private fun enqueueInterrogationTask() {
        moduleReflected.sexecutor.executeTask(this.interrogationRunnable)
    }

    companion object {
        private const val LOG_TAG = "AsyncStorageIR"

        fun createIfNeeded(reactContext: ReactContext): AsyncStorageIdlingResource? {
            Log.d(LOG_TAG, "Checking whether custom IR for Async-Storage is required...")

            val moduleClass: Class<NativeModule>?
            try {
                moduleClass = Class.forName("com.facebook.react.modules.storage.AsyncStorageModule") as Class<NativeModule>
            } catch (ex: Exception) {
                Log.d(LOG_TAG, "Not needed: no class")
                return null
            }

            if (!reactContext.hasNativeModule(moduleClass)) {
                Log.d(LOG_TAG, "Not needed: module not registered")
                return null
            }

            Log.d(LOG_TAG, "Creating an IR!")
            return AsyncStorageIdlingResource(reactContext.getNativeModule(moduleClass))
        }
    }
}
