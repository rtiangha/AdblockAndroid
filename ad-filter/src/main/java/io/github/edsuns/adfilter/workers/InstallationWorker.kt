package io.github.edsuns.adfilter.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.edsuns.adblockclient.AdBlockClient
import io.github.edsuns.adfilter.AdFilter
import io.github.edsuns.adfilter.impl.AdFilterImpl
import io.github.edsuns.adfilter.impl.Constants.KEY_ALREADY_UP_TO_DATE
import io.github.edsuns.adfilter.impl.Constants.KEY_CHECK_LICENSE
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOADED_DATA
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTERS_COUNT
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTER_ID
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTER_NAME
import io.github.edsuns.adfilter.impl.Constants.KEY_RAW_CHECKSUM
import io.github.edsuns.adfilter.util.Checksum
import timber.log.Timber

/**
 * Created by Edsuns@qq.com on 2021/1/5.
 */
internal class InstallationWorker(context: Context, params: WorkerParameters) : Worker(
    context,
    params
) {
    private val binaryDataStore = (AdFilter.get(applicationContext) as AdFilterImpl).binaryDataStore

    override fun doWork(): Result {
        val id = inputData.getString(KEY_FILTER_ID) ?: return Result.failure()
        val downloadedDataName = inputData.getString(KEY_DOWNLOADED_DATA) ?: return Result.failure()
        val rawChecksum = inputData.getString(KEY_RAW_CHECKSUM) ?: return Result.failure()
        val checkLicense = inputData.getBoolean(KEY_CHECK_LICENSE, false)
        val rawData = binaryDataStore.loadData(downloadedDataName)

        // --- FIX: PREVENT CRASH ---
        // If the file was empty or download failed silently, rawData might be empty.
        // Passing empty bytes to AdBlockClient causes the SIGSEGV.
        if (rawData.isEmpty()) {
            Timber.e("Fatal: Downloaded filter data is empty for ID: $id")
            return Result.failure()
        }
        // --------------------------

        val dataStr = String(rawData)
        val name = extractTitle(dataStr)
        val checksum = Checksum(dataStr)
        // Reject text that is obviously not a filter list (HTML error pages, captive portals, ...). Lists are not
        // required to carry any particular header: many have no checksum and no license line.
        // Fail instead of "succeeding" with no data, which used to reset the list's rule count to 0.
        if (checksum.checksumIn == null && checkLicense && !looksLikeFilterList(dataStr)) {
            Timber.w("Not a filter list (HTML page or no list header/metadata): $id")
            return Result.failure()
        }
        Timber.v("Checksum: $rawChecksum, ${checksum.checksumIn}, ${checksum.checksumCalc}, ${checksum.validate()}")
        if (!checksum.validate()) {
            return Result.failure()
        }
        if (checksum.validate(rawChecksum)) {
            Timber.v("Filter is up to date: $id")
            return Result.success(
                workDataOf(
                    KEY_FILTER_NAME to name,
                    KEY_ALREADY_UP_TO_DATE to true
                )
            )
        }

        try {
            val filtersCount = persistFilterData(id, rawData)
            binaryDataStore.clearData(downloadedDataName)

            return Result.success(
                workDataOf(
                    KEY_FILTERS_COUNT to filtersCount,
                    KEY_FILTER_NAME to name,
                    KEY_RAW_CHECKSUM to checksum.checksumCalc
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist filter data for ID: $id")
            return Result.failure()
        }
    }

    // "[Adblock Plus 2.0]", "[uBlock Origin]", ...
    private val listHeaderRegexp = Regex(
        "^\\s*\\[(?:Adblock Plus|uBlock Origin|Adblock|AdGuard)[^\\]\\r\\n]*\\]",
        RegexOption.IGNORE_CASE
    )

    // "! Title: ...", "! Version: ...", "! Licence: ..." and similar header comments
    private val metadataRegexp = Regex(
        "^\\s*!\\s*(?:title|version|homepage|expires|description|last modified|licen[sc]e)\\b",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /**
     * Cheap sanity check that the downloaded text is a filter list: not an HTML/XML page, and it has either a list
     * header line or at least one of the usual metadata comments near the top.
     */
    private fun looksLikeFilterList(data: String): Boolean {
        val text = data.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.startsWith("<")) return false
        val head = text.take(8192)
        return listHeaderRegexp.containsMatchIn(head) || metadataRegexp.containsMatchIn(head)
    }

    private val titleRegexp = Regex(
        "^\\s*!\\s*title[\\s\\-:]+([\\S ]+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    private fun extractTitle(data: String): String? = titleRegexp.find(data)?.groupValues?.get(1)

    private fun persistFilterData(id: String, rawBytes: ByteArray): Int {

        // --- FIX: EXTRA SAFETY ---
        if (rawBytes.isEmpty()) {
            Timber.w("Skipping persistFilterData: rawBytes is empty.")
            return 0
        }

        val client = AdBlockClient(id)
        client.loadBasicData(rawBytes, true)
        binaryDataStore.saveData(id, client.getProcessedData())
        return client.getFiltersCount()
    }
}
