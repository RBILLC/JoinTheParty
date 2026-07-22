package com.jointheparty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.ui.components.NudgeWheel
import com.jointheparty.app.ui.components.SyncMeter
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

/**
 * SCAF-03/UI-03 bring-up screen: engine alive + the phase-horizon meter on
 * a scripted DEMO stream (drift → converge → lock, looping). Replaced by
 * the real session screen (UI-05), which feeds SyncCore.meterFrames.
 */
class MainActivity : ComponentActivity() {

    private var engine: SyncCore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SyncCore()
        setContent {
            BilletTheme {
                val latency = remember { engine?.commandLatencyMs() ?: -1 }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DT.Colors.void)
                        .padding(DT.Space.gutter),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("JoinTheParty", style = BilletType.title, color = DT.Colors.ink)
                    Text(
                        "SyncCore up — seek lead $latency ms",
                        style = BilletType.fine,
                        color = DT.Colors.ink3,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SyncMeter(frames = remember { demoFrames() })
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    var trim by remember { mutableIntStateOf(-180) }
                    NudgeWheel(
                        trimMs = trim,
                        routeName = "AirPods Pro",
                        onTrimChange = { trim = it },
                        onTrimCommit = { engine?.setUserNudgeMs(it) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }
}

/** DEMO ONLY: 15 Hz scripted session — drifting, converging, locking. */
private fun demoFrames(): Flow<MeterFrame> = flow {
    val dt = 66L
    while (true) {
        // Drifting: cold, uncertain, far off.
        var t = 0.0
        while (t < 3.0) {
            emit(MeterFrame(-190.0 + 30.0 * sin(t * 2.1), 120.0, 0.35f, false))
            delay(dt); t += dt / 1000.0
        }
        // Converging: exponential approach, warming.
        var e = -160.0
        var conf = 0.5f
        t = 0.0
        while (t < 3.0) {
            e *= 0.93
            conf = (conf + 0.01f).coerceAtMost(0.9f)
            emit(MeterFrame(e, 40.0, conf, false))
            delay(dt); t += dt / 1000.0
        }
        // Locked: fused line, jitter inside the deadband.
        t = 0.0
        while (t < 4.0) {
            emit(MeterFrame(1.5 * sin(t * 3.0), 10.0, 0.97f, true))
            delay(dt); t += dt / 1000.0
        }
    }
}
