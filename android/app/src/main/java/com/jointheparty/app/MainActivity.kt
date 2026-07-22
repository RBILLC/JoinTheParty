package com.jointheparty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.ui.theme.DT

/**
 * SCAF-03 bring-up screen: proves the JNI bridge end-to-end (engine
 * instantiated, command-latency readback) on Billet tokens. Replaced by the
 * real session screen in UI-05.
 */
class MainActivity : ComponentActivity() {

    private var engine: SyncCore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SyncCore()
        setContent {
            val latency = remember { engine?.commandLatencyMs() ?: -1 }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DT.Colors.void)
                    .padding(DT.Space.gutter),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "JoinTheParty",
                    color = DT.Colors.ink,
                    fontSize = DT.Type.title.sizeSp.sp,
                    fontWeight = FontWeight(DT.Type.title.weight),
                )
                Text(
                    text = "SyncCore up — seek lead $latency ms",
                    color = DT.Colors.ink2,
                    fontSize = DT.Type.body.sizeSp.sp,
                )
            }
        }
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
