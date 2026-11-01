package com.gsmautoanswer.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.util.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class CallFlowSettingsActivity : AppCompatActivity() {

    private lateinit var graph: AppGraph
    private lateinit var autoAnswerSwitch: MaterialSwitch
    private lateinit var countdownSlider: Slider
    private lateinit var countdownValue: TextView
    private lateinit var delaySlider: Slider
    private lateinit var delayValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = (application as AutoAnswerApp).graph
        setContentView(R.layout.activity_call_flow_settings)
        SystemBars.apply(findViewById(R.id.flow_root))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        autoAnswerSwitch = findViewById(R.id.auto_answer_switch)
        countdownSlider = findViewById(R.id.countdown_slider)
        countdownValue = findViewById(R.id.countdown_value)
        delaySlider = findViewById(R.id.delay_slider)
        delayValue = findViewById(R.id.delay_value)

        countdownSlider.valueFrom = 5f
        countdownSlider.valueTo = 120f
        countdownSlider.stepSize = 5f
        delaySlider.valueFrom = 0f
        delaySlider.valueTo = 5f
        delaySlider.stepSize = 1f

        bindValues()

        autoAnswerSwitch.setOnCheckedChangeListener { _, checked ->
            graph.settings.setAutoAnswerEnabled(checked)
        }
        countdownSlider.addOnChangeListener { _, value, fromUser ->
            val seconds = value.toInt()
            countdownValue.text = getString(R.string.countdown_value, seconds)
            if (fromUser) graph.settings.setCountdownSeconds(seconds)
        }
        delaySlider.addOnChangeListener { _, value, fromUser ->
            val seconds = value.toInt()
            delayValue.text = delayLabel(seconds)
            if (fromUser) graph.settings.setAnswerDelaySeconds(seconds)
        }
    }

    override fun onResume() {
        super.onResume()
        bindValues()
    }

    private fun bindValues() {
        val settings = graph.settings.current
        autoAnswerSwitch.isChecked = settings.autoAnswerEnabled
        countdownSlider.value = settings.countdownSeconds.toFloat()
        countdownValue.text = getString(R.string.countdown_value, settings.countdownSeconds)
        delaySlider.value = settings.answerDelaySeconds.toFloat()
        delayValue.text = delayLabel(settings.answerDelaySeconds)
    }

    private fun delayLabel(seconds: Int): String = if (seconds == 0) {
        getString(R.string.delay_value_immediate)
    } else {
        getString(R.string.delay_value, seconds)
    }
}
