package dev.ocfox.ims.privileged

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.LSPass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ImsInstrumentation : Instrumentation() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        LSPass.setHiddenApiExemptions("")
        val requestId = arguments?.getLong(EXTRA_REQUEST_ID, -1L) ?: -1L
        val task = pendingTasks.remove(requestId)
        if (task == null) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }
        val hostContext = targetContext ?: context
        scope.launch {
            try {
                val result = runCatching {
                    TelephonyBridge.executeDelegated {
                        task.block(hostContext)
                    }
                }
                task.resultDeferred.complete(result)
            } finally {
                finish(Activity.RESULT_OK, Bundle())
            }
        }
    }

    internal class PendingTask(
        val block: suspend (Context) -> Any?,
        val resultDeferred: CompletableDeferred<Result<Any?>> = CompletableDeferred(),
    )

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        private val nextId = AtomicLong(1L)
        private val pendingTasks = ConcurrentHashMap<Long, PendingTask>()

        internal fun enqueue(block: suspend (Context) -> Any?): Pair<Long, PendingTask> {
            val id = nextId.getAndIncrement()
            val task = PendingTask(block)
            pendingTasks[id] = task
            return id to task
        }

        internal fun cancel(requestId: Long) {
            pendingTasks.remove(requestId)
        }
    }
}
