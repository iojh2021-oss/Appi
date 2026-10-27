package com.morsefinder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppRoot(morse: MorseViewModel, sign: SignViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Pal.bg)) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) MorseApp(morse) else SignScreen(sign)
        }
        Row(
            Modifier.fillMaxWidth().background(Pal.panel).navigationBarsPadding()
        ) {
            NavItem("مورس", Icons.Filled.GraphicEq, tab == 0, Modifier.weight(1f)) {
                if (tab != 0) { sign.stop(); tab = 0 }
            }
            NavItem("زبان اشاره", Icons.Filled.PanTool, tab == 1, Modifier.weight(1f)) {
                if (tab != 1) { morse.stop(); tab = 1 }
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (selected) Pal.sig else Pal.muted)
        Text(label, color = if (selected) Pal.sig else Pal.muted, fontSize = 12.sp)
    }
}
