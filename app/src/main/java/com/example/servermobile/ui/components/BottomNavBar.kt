package com.example.servermobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.servermobile.ui.theme.*

@Composable
fun BottomNavBar(currentTab: Int = 0, onTabSelected: (Int) -> Unit = {}) {
    NavigationBar(
        containerColor = CardBackground,
        contentColor = TextSecondary
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Home, contentDescription = "Início") },
            label = { Text("Início") },
            selected = currentTab == 0,
            onClick = { onTabSelected(0) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentColor,
                selectedTextColor = AccentColor,
                indicatorColor = DarkBackground
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Analytics, contentDescription = "Conectar") }, // You can use a better icon like Icons.Filled.Link later
            label = { Text("Conectar") },
            selected = currentTab == 1,
            onClick = { onTabSelected(1) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentColor,
                selectedTextColor = AccentColor,
                indicatorColor = DarkBackground
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.List, contentDescription = "Logs") },
            label = { Text("Logs") },
            selected = currentTab == 2,
            onClick = { onTabSelected(2) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentColor,
                selectedTextColor = AccentColor,
                indicatorColor = DarkBackground
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.People, contentDescription = "Conexões") },
            label = { Text("Conexões") },
            selected = currentTab == 3,
            onClick = { onTabSelected(3) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentColor,
                selectedTextColor = AccentColor,
                indicatorColor = DarkBackground
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Settings, contentDescription = "Configurações") },
            label = { Text("Config.") },
            selected = currentTab == 4,
            onClick = { onTabSelected(4) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentColor,
                selectedTextColor = AccentColor,
                indicatorColor = DarkBackground
            )
        )
    }
}
