package dev.shipyu.mindverse.core.pipeline.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.shipyu.mindverse.core.pipeline.PipelineProcessor
import dev.shipyu.mindverse.core.pipeline.PipelineNotifications
import dev.shipyu.mindverse.core.pipeline.PipelineScheduler
import dev.shipyu.mindverse.core.pipeline.StageOutcome
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduler (ticket 04): one unique extract→embed chain per Capture.
 * Expedited with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback; exponential backoff with a
 * hard attempt cap enforced by [PipelineProcessor] (WorkManager itself retries forever).
 *
 * No NetworkType constraint: extraction may run keyless/offline (dead-letters with a
 * visible reason) and embedding is purely local; the AI call's failure is the signal, not
 * a job constraint (ticket: "constrained: network-not-required for pre/post steps").
 */
class WorkManagerPipelineScheduler(
    private val context: Context,
) : PipelineScheduler {

    override fun enqueue(captureId: String) {
        val extract = OneTimeWorkRequestBuilder<ExtractWorker>()
            .setInputData(workDataOf(ExtractWorker.KEY_CAPTURE_ID to captureId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        val embed = OneTimeWorkRequestBuilder<EmbedWorker>()
            .setInputData(workDataOf(EmbedWorker.KEY_CAPTURE_ID to captureId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(uniqueName(captureId), ExistingWorkPolicy.KEEP, extract)
            .then(embed)
            .enqueue()
    }

    companion object {
        const val TAG = "capture-pipeline"
        private const val BACKOFF_SECONDS = 30L

        fun uniqueName(captureId: String): String = "pipeline:$captureId"
    }
}

/** Expedited-work foreground notification (the processing chip), typed per API level. */
internal object PipelineForeground {
    fun info(context: Context): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        val notification = PipelineNotifications.build(
            context = context,
            title = "Processing capture",
            text = "Turning a Capture into a Memory…",
            uri = dev.shipyu.mindverse.core.pipeline.MindverseLinks.pipeline(),
            ongoing = true,
        )
        return ForegroundInfo(PipelineNotifications.NOTIFICATION_ID, notification, type)
    }
}

/** Extract stage worker: capture id in, structured Memory + Pin out. */
@HiltWorker
class ExtractWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val processor: PipelineProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val captureId = inputData.getString(KEY_CAPTURE_ID) ?: return Result.failure()
        return processor.extract(captureId, runAttemptCount).toResult()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = PipelineForeground.info(applicationContext)

    companion object {
        const val KEY_CAPTURE_ID = "capture_id"
    }
}

/** Embed stage worker: on-device vector indexing, then DONE. */
@HiltWorker
class EmbedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val processor: PipelineProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val captureId = inputData.getString(KEY_CAPTURE_ID) ?: return Result.failure()
        return processor.embed(captureId, runAttemptCount).toResult()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = PipelineForeground.info(applicationContext)

    companion object {
        const val KEY_CAPTURE_ID = "capture_id"
    }
}

private fun StageOutcome.toResult(): Result = when (this) {
    is StageOutcome.Success -> Result.success()
    StageOutcome.Retry -> Result.retry()
    is StageOutcome.DeadLetter -> Result.failure()
}
