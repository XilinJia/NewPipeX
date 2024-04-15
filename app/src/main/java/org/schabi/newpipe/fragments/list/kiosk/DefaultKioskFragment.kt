package org.schabi.newpipe.fragments.list.kiosk

import android.os.Bundle
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.util.KioskTranslator.getTranslatedKioskName
import org.schabi.newpipe.util.ServiceHelper.getSelectedServiceId

class DefaultKioskFragment : KioskFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (serviceId < 0) {
            updateSelectedDefaultKiosk()
        }
    }

    override fun onResume() {
        super.onResume()

        if (serviceId != getSelectedServiceId(requireContext())) {
            currentWorker?.dispose()
            updateSelectedDefaultKiosk()
            reloadContent()
        }
    }

    private fun updateSelectedDefaultKiosk() {
        try {
            serviceId = getSelectedServiceId(requireContext())

            val kioskList = NewPipe.getService(serviceId).kioskList
            kioskId = kioskList.defaultKioskId
            url = kioskList.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).url

            kioskTranslatedName = getTranslatedKioskName(kioskId, requireContext())
            name = kioskTranslatedName

            currentInfo = null
            currentNextPage = null
        } catch (e: ExtractionException) {
            showError(ErrorInfo(e, UserAction.REQUESTED_KIOSK, "Loading default kiosk for selected service"))
        }
    }
}
