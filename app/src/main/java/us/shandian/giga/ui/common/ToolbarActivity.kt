package us.shandian.giga.ui.common

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.schabi.newpipe.R

abstract class ToolbarActivity : AppCompatActivity() {
    protected var mToolbar: Toolbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutResource)

        mToolbar = this.findViewById(R.id.toolbar)

        setSupportActionBar(mToolbar)
    }

    protected abstract val layoutResource: Int
        get
}
