package org.schabi.newpipe.ui.list

import android.os.Bundle
import android.view.*
import icepick.State
import io.reactivex.rxjava3.core.Single
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.services.media_ccc.extractors.MediaCCCLiveStreamKiosk
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.ExtractorHelper.getKioskInfo
import org.schabi.newpipe.util.ExtractorHelper.getMoreKioskItems
import org.schabi.newpipe.util.KioskTranslator.getTranslatedKioskName
import org.schabi.newpipe.util.Localization.getPreferredContentCountry
import org.schabi.newpipe.util.Logd

/**
 * Created by Christian Schabesberger on 23.09.17.
 *
 *
 * Copyright (C) Christian Schabesberger 2017 <chris.schabesberger></chris.schabesberger>@mailbox.org>
 * KioskFragment.java is part of NewPipe.
 *
 *
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe. If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
open class KioskFragment : BaseListInfoFragment<StreamInfoItem, KioskInfo>(UserAction.REQUESTED_KIOSK) {
    @JvmField
    @State
    var kioskId: String = ""
    @JvmField
    var kioskTranslatedName: String? = null

    @JvmField
    @State
    var contentCountry: ContentCountry? = null

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kioskTranslatedName = getTranslatedKioskName(kioskId, requireActivity())
        name = kioskTranslatedName
        contentCountry = getPreferredContentCountry(requireContext())
    }

    override fun onResume() {
        super.onResume()
        if (getPreferredContentCountry(requireContext()) != contentCountry) {
            reloadContent()
        }
        if (useAsFrontPage && activity != null) {
            try {
                setTitle(kioskTranslatedName!!)
            } catch (e: Exception) {
                showSnackBarError(ErrorInfo(e, UserAction.UI_ERROR, "Setting kiosk title"))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_kiosk, container, false)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    ////////////////////////////////////////////////////////////////////////// */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        val supportActionBar = activity?.supportActionBar
        if (useAsFrontPage) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    ////////////////////////////////////////////////////////////////////////// */
    public override fun loadResult(forceReload: Boolean): Single<KioskInfo> {
        contentCountry = getPreferredContentCountry(requireContext())
        return getKioskInfo(serviceId, url!!, forceReload)
    }

    public override fun loadMoreItemsLogic(): Single<InfoItemsPage<StreamInfoItem>> {
        return getMoreKioskItems(serviceId, url, currentNextPage)
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    ////////////////////////////////////////////////////////////////////////// */
    override fun handleResult(result: KioskInfo) {
        super.handleResult(result)

        name = kioskTranslatedName
        setTitle(kioskTranslatedName!!)
    }

    override fun showEmptyState() {
        // show "no live streams" for live stream kiosk
        super.showEmptyState()
        if (MediaCCCLiveStreamKiosk.KIOSK_ID == currentInfo!!.id && ServiceList.MediaCCC.serviceId == currentInfo!!.serviceId) {
            setEmptyStateMessage(R.string.no_live_streams)
        }
    }

    companion object {
        /*//////////////////////////////////////////////////////////////////////////
    // Views
    ////////////////////////////////////////////////////////////////////////// */
        @Throws(ExtractionException::class)
        fun getInstance(serviceId: Int): KioskFragment {
            return getInstance(serviceId, NewPipe.getService(serviceId).kioskList.defaultKioskId)
        }

        @Throws(ExtractionException::class)
        fun getInstance(serviceId: Int, kioskId: String): KioskFragment {
            val instance = KioskFragment()
            val service = NewPipe.getService(serviceId)
            val kioskLinkHandlerFactory = service.kioskList.getListLinkHandlerFactoryByType(kioskId)
            instance.setInitialData(serviceId, kioskLinkHandlerFactory.fromId(kioskId).url, kioskId)
            instance.kioskId = kioskId
            return instance
        }
    }
}
