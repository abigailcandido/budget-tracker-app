package com.abigailcandido.budgettracker.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class HomeTab {
    Trackers,
    Categories,
}

@Composable
fun HomeBottomBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == HomeTab.Trackers,
            onClick = { onSelect(HomeTab.Trackers) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Trackers") },
        )
        NavigationBarItem(
            selected = selected == HomeTab.Categories,
            onClick = { onSelect(HomeTab.Categories) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Categories") },
        )
    }
}