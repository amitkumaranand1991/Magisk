package com.topjohnwu.magisk.core.download

import android.app.Activity
import android.app.Notification
import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.utils.ProgressInputStream
import com.topjohnwu.magisk.core.view.Notifications
import com.topjohnwu.magisk.data.network.GithubRawServices
import com.topjohnwu.magisk.di.NullActivity
import com.topjohnwu.magisk.extensions.get
import com.topjohnwu.magisk.extensions.subscribeK
import com.topjohnwu.magisk.extensions.writeTo
import com.topjohnwu.magisk.model.entity.internal.DownloadSubject
import com.topjohnwu.magisk.model.entity.internal.DownloadSubject.*
import com.topjohnwu.superuser.ShellUtils
import io.reactivex.Completable
import okhttp3.ResponseBody
import org.koin.android.ext.android.inject
import org.koin.core.KoinComponent
import timber.log.Timber
import java.io.InputStream

abstract class RemoteFileService : NotificationService() {

    val service: GithubRawServices by inject()

    override val defaultNotification
        get() = Notifications.progress(this, "")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getParcelableExtra<DownloadSubject>(ARG_URL)?.let { start(it) }
        return START_REDELIVER_INTENT
    }

    // ---

    private fun start(subject: DownloadSubject) = checkExisting(subject)
        .onErrorResumeNext { download(subject) }
        .doOnSubscribe { update(subject.hashCode()) { it.setContentTitle(subject.title) } }
        .subscribeK(onError = {
            Timber.e(it)
            failNotify(subject)
        }) {
            val newId = finishNotify(subject)
            if (get<Activity>() !is NullActivity) {
                onFinished(subject, newId)
            }
        }

    private fun checkExisting(subject: DownloadSubject) = Completable.fromAction {
        check(subject is Magisk) { "Download cache is disabled" }

        subject.file.also {
            check(it.exists() && ShellUtils.checkSum("MD5", it, subject.magisk.md5)) {
                "The given file does not match checksum"
            }
        }
    }

    private fun download(subject: DownloadSubject) = service.fetchFile(subject.url)
        .map { it.toStream(subject.hashCode(), subject) }
        .flatMapCompletable { stream ->
            when (subject) {
                is Module -> service.fetchInstaller()
                    .doOnSuccess { stream.toModule(subject.file, it.byteStream()) }
                    .ignoreElement()
                else -> Completable.fromAction { stream.writeTo(subject.file) }
            }
        }.doOnComplete {
            if (subject is Manager)
                handleAPK(subject)
        }

    private fun ResponseBody.toStream(id: Int, subject: DownloadSubject): InputStream {
        val maxRaw = contentLength()
        val max = maxRaw / 1_000_000f

        return ProgressInputStream(byteStream()) {
            val progress = it / 1_000_000f
            update(id) { notification ->
                if (maxRaw > 0) {
                    send(progress / max, subject)
                    notification
                        .setProgress(maxRaw.toInt(), it.toInt(), false)
                        .setContentText("%.2f / %.2f MB".format(progress, max))
                } else {
                    send(-1f, subject)
                    notification.setContentText("%.2f MB / ??".format(progress))
                }
            }
        }
    }

    private fun failNotify(subject: DownloadSubject) = finishNotify(subject.hashCode()) {
        send(0f, subject)
        it.setContentText(getString(R.string.download_file_error))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
    }

    private fun finishNotify(subject: DownloadSubject) = finishNotify(subject.hashCode()) {
        send(1f, subject)
        it.addActions(subject)
            .setContentText(getString(R.string.download_complete))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
    }

    // ---


    @Throws(Throwable::class)
    protected abstract fun onFinished(subject: DownloadSubject, id: Int)

    protected abstract fun Notification.Builder.addActions(subject: DownloadSubject)
            : Notification.Builder

    companion object : KoinComponent {
        const val ARG_URL = "arg_url"

        private val internalProgressBroadcast = MutableLiveData<Pair<Float, DownloadSubject>>()
        val progressBroadcast: LiveData<Pair<Float, DownloadSubject>> get() = internalProgressBroadcast

        fun send(progress: Float, subject: DownloadSubject) {
            internalProgressBroadcast.postValue(progress to subject)
        }

        fun reset() {
            internalProgressBroadcast.value = null
        }
    }

}
