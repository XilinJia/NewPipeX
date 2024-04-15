package org.schabi.newpipe.download

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentTransaction
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityDownloaderBinding
import org.schabi.newpipe.util.DeviceUtils.isTv
import org.schabi.newpipe.util.Localization.assureCorrectAppLanguage
import org.schabi.newpipe.util.ThemeHelper.setTheme
import org.schabi.newpipe.views.FocusOverlayView.Companion.setupFocusObserver
import us.shandian.giga.service.DownloadManagerService
import us.shandian.giga.ui.fragment.MissionsFragment

class DownloadActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Service
        val i = Intent()
        i.setClass(this, DownloadManagerService::class.java)
        startService(i)

        assureCorrectAppLanguage(this)
        setTheme(this)

        super.onCreate(savedInstanceState)

        val downloaderBinding =
            ActivityDownloaderBinding.inflate(layoutInflater)
        setContentView(downloaderBinding.root)

        setSupportActionBar(downloaderBinding.toolbarLayout.toolbar)

        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setTitle(R.string.downloads_title)
            actionBar.setDisplayShowTitleEnabled(true)
        }

        window.decorView.viewTreeObserver
            .addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    updateFragments()
                    window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })

        if (isTv(this)) {
            setupFocusObserver(this)
        }
    }

    private fun updateFragments() {
        val fragment = MissionsFragment()

        supportFragmentManager.beginTransaction()
            .replace(R.id.frame, fragment, MISSIONS_FRAGMENT_TAG)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        val inflater = menuInflater

        inflater.inflate(R.menu.download_menu, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val MISSIONS_FRAGMENT_TAG = "fragment_tag"
    }
}
