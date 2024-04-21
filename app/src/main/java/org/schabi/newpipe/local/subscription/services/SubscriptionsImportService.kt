/*
 * Copyright 2018 Mauricio Colli <mauriciocolli@outlook.com>
 * SubscriptionsImportService.java is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.schabi.newpipe.local.subscription.services

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.util.Pair
import androidx.core.content.IntentCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Function
import io.reactivex.rxjava3.schedulers.Schedulers
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.schabi.newpipe.App
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import org.schabi.newpipe.ktx.isNetworkRelated
import org.schabi.newpipe.local.subscription.services.ImportExportJsonHelper.readFrom
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ExtractorHelper.getChannelInfo
import org.schabi.newpipe.util.ExtractorHelper.getChannelTab
import org.schabi.newpipe.util.KEY_SERVICE_ID
import org.schabi.newpipe.util.NO_SERVICE_ID
import java.io.IOException
import java.io.InputStream
import java.util.*

class SubscriptionsImportService : BaseImportExportService() {
    private var subscription: Subscription? = null
    private var currentMode = 0
    private var currentServiceId = 0
    private var channelUrl: String? = null
    private var inputStream: InputStream? = null
    private var inputStreamType: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || subscription != null) {
            return START_NOT_STICKY
        }

        currentMode = intent.getIntExtra(KEY_MODE, -1)
        currentServiceId = intent.getIntExtra(KEY_SERVICE_ID, NO_SERVICE_ID)

        if (currentMode == CHANNEL_URL_MODE) {
            channelUrl = intent.getStringExtra(KEY_VALUE)
        } else {
            val uri = IntentCompat.getParcelableExtra(intent, KEY_VALUE, Uri::class.java)
            if (uri == null) {
                stopAndReportError(IllegalStateException(
                    "Importing from input stream, but file path is null"),
                    "Importing subscriptions")
                return START_NOT_STICKY
            }

            try {
                val fileHelper = StoredFileHelper(this, uri, StoredFileHelper.DEFAULT_MIME)
                inputStream = SharpInputStream(fileHelper.stream)
                inputStreamType = fileHelper.type

                if (inputStreamType == null || inputStreamType == StoredFileHelper.DEFAULT_MIME) {
                    // mime type could not be determined, just take file extension
                    val name = fileHelper.name
                    val pointIndex = name!!.lastIndexOf('.')
                    inputStreamType = if (pointIndex == -1 || pointIndex >= name.length - 1) {
                        StoredFileHelper.DEFAULT_MIME // no extension, will fail in the extractor
                    } else {
                        name.substring(pointIndex + 1)
                    }
                }
            } catch (e: IOException) {
                handleError(e)
                return START_NOT_STICKY
            }
        }

        if (currentMode == -1 || currentMode == CHANNEL_URL_MODE && channelUrl == null) {
            val errorDescription = ("Some important field is null or in illegal state: "
                    + "currentMode=[" + currentMode + "], "
                    + "channelUrl=[" + channelUrl + "], "
                    + "inputStream=[" + inputStream + "]")
            stopAndReportError(IllegalStateException(errorDescription),
                "Importing subscriptions")
            return START_NOT_STICKY
        }

        startImport()
        return START_NOT_STICKY
    }

    override val notificationId: Int
        get() = 4568

    override val title: Int
        get() = R.string.import_ongoing

    override fun disposeAll() {
        super.disposeAll()
        if (subscription != null) {
            subscription!!.cancel()
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Imports
    ////////////////////////////////////////////////////////////////////////// */
    private fun startImport() {
        showToast(R.string.import_ongoing)

        var flowable: Flowable<List<SubscriptionItem?>>? = null
        when (currentMode) {
            CHANNEL_URL_MODE -> flowable = importFromChannelUrl()
            INPUT_STREAM_MODE -> flowable = importFromInputStream()
            PREVIOUS_EXPORT_MODE -> flowable = importFromPreviousExport()
        }
        if (flowable == null) {
            val message = ("Flowable given by \"importFrom\" is null "
                    + "(current mode: " + currentMode + ")")
            stopAndReportError(IllegalStateException(message), "Importing subscriptions")
            return
        }

        flowable.doOnNext(Consumer { subscriptionItems: List<SubscriptionItem?> ->
            eventListener.onSizeReceived(subscriptionItems.size)
        })
            .flatMap(Function<List<SubscriptionItem?>, Publisher<out SubscriptionItem>> { source: List<SubscriptionItem?> ->
                Flowable.fromIterable(source.filterNotNull())
            })

            .parallel(PARALLEL_EXTRACTIONS)
            .runOn(Schedulers.io())
            .map(
                Function { subscriptionItem: SubscriptionItem ->
                    try {
                        val channelInfo = getChannelInfo(subscriptionItem.serviceId,
                            subscriptionItem.url, true)
                            .blockingGet()
                        return@Function Notification.createOnNext<Pair<ChannelInfo, List<ChannelTabInfo>>>(Pair<ChannelInfo, List<ChannelTabInfo>>(
                            channelInfo,
                            listOf<ChannelTabInfo>(
                                getChannelTab(
                                    subscriptionItem.serviceId,
                                    channelInfo.tabs[0], true).blockingGet()
                            )))
                    } catch (e: Throwable) {
                        return@Function Notification.createOnError<Pair<ChannelInfo, List<ChannelTabInfo>>>(e)
                    }
                })
            .sequential()

            .observeOn(Schedulers.io())
            .doOnNext(notificationsConsumer)

            .buffer(BUFFER_COUNT_BEFORE_INSERT)
            .map(upsertBatch())

            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(subscriber)
    }

    private val subscriber: Subscriber<List<SubscriptionEntity>>
        get() = object : Subscriber<List<SubscriptionEntity>> {
            override fun onSubscribe(s: Subscription) {
                subscription = s
                s.request(Long.MAX_VALUE)
            }

            override fun onNext(successfulInserted: List<SubscriptionEntity>) {
                if (MainActivity.DEBUG) {
                    Log.d(TAG, "startImport() " + successfulInserted.size
                            + " items successfully inserted into the database")
                }
            }

            override fun onError(error: Throwable) {
                Log.e(TAG, "Got an error!", error)
                handleError(error)
            }

            override fun onComplete() {
                LocalBroadcastManager.getInstance(this@SubscriptionsImportService)
                    .sendBroadcast(Intent(IMPORT_COMPLETE_ACTION))
                showToast(R.string.import_complete_toast)
                stopService()
            }
        }

    private val notificationsConsumer: Consumer<Notification<Pair<ChannelInfo, List<ChannelTabInfo>>>>
        get() = Consumer { notification: Notification<Pair<ChannelInfo, List<ChannelTabInfo>>> ->
            if (notification.isOnNext) {
                val name = notification.value!!.first.name
                eventListener.onItemCompleted(if (!TextUtils.isEmpty(name)) name else "")
            } else if (notification.isOnError) {
                val error = notification.error
                val cause = error!!.cause
                if (error is IOException) {
                    throw error
                } else if (cause is IOException) {
                    throw cause
                } else if (error.isNetworkRelated) {
                    throw IOException(error)
                }

                eventListener.onItemCompleted("")
            }
        }

    private fun upsertBatch(): Function<List<Notification<Pair<ChannelInfo, List<ChannelTabInfo>>>>, List<SubscriptionEntity>> {
        return Function { notificationList: List<Notification<Pair<ChannelInfo, List<ChannelTabInfo>>>> ->
            val infoList: MutableList<Pair<ChannelInfo, List<ChannelTabInfo>>> =
                ArrayList(notificationList.size)
            for (n in notificationList) {
                if (n.isOnNext) {
                    infoList.add(n.value!!)
                }
            }
            subscriptionManager!!.upsertAll(infoList)
        }
    }

    private fun importFromChannelUrl(): Flowable<List<SubscriptionItem?>> {
        return Flowable.fromCallable {
            NewPipe.getService(currentServiceId)
                .subscriptionExtractor
                .fromChannelUrl(channelUrl)
        }
    }

    private fun importFromInputStream(): Flowable<List<SubscriptionItem?>> {
        Objects.requireNonNull(inputStream)
        Objects.requireNonNull(inputStreamType)

        return Flowable.fromCallable {
            NewPipe.getService(currentServiceId)
                .subscriptionExtractor
                .fromInputStream(inputStream!!, inputStreamType!!)
        }
    }

    private fun importFromPreviousExport(): Flowable<List<SubscriptionItem?>> {
        return Flowable.fromCallable { readFrom(inputStream, null) }
    }

    protected fun handleError(error: Throwable) {
        super.handleError(R.string.subscriptions_import_unsuccessful, error)
    }

    companion object {
        const val CHANNEL_URL_MODE: Int = 0
        const val INPUT_STREAM_MODE: Int = 1
        const val PREVIOUS_EXPORT_MODE: Int = 2
        const val KEY_MODE: String = "key_mode"
        const val KEY_VALUE: String = "key_value"

        /**
         * A [local broadcast][LocalBroadcastManager] will be made with this action
         * when the import is successfully completed.
         */
        const val IMPORT_COMPLETE_ACTION: String = (App.PACKAGE_NAME + ".local.subscription"
                + ".services.SubscriptionsImportService.IMPORT_COMPLETE")

        /**
         * How many extractions running in parallel.
         */
        const val PARALLEL_EXTRACTIONS: Int = 8

        /**
         * Number of items to buffer to mass-insert in the subscriptions table,
         * this leads to a better performance as we can then use db transactions.
         */
        const val BUFFER_COUNT_BEFORE_INSERT: Int = 50
    }
}
