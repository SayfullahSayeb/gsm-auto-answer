package com.gsmautoanswer.app.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.ui.check.DeviceCheckFragment
import com.gsmautoanswer.app.util.SystemBars

class LiteMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_lite)
        SystemBars.apply(findViewById(R.id.lite_root))

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.lite_container, DeviceCheckFragment())
                .commit()
        }
    }
}
