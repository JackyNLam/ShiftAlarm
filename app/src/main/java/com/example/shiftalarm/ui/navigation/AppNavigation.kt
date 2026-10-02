package com.example.shiftalarm.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.shiftalarm.data.repository.ShiftRepository
import com.example.shiftalarm.ui.screen.calendar.CalendarScreen
import com.example.shiftalarm.ui.screen.home.HomeScreen
import com.example.shiftalarm.ui.screen.template.TemplateListScreen

sealed class Screen(
    val title: String,
    val icon: ImageVector
) {
    data object Home : Screen("首頁 / Home", Icons.Default.Home)
    data object Calendar : Screen("日曆 / Calendar", Icons.Default.CalendarMonth)
    data object Templates : Screen("模板 / Templates", Icons.Default.List)
}

private val bottomNavItems = listOf(Screen.Home, Screen.Calendar, Screen.Templates)

@Composable
fun AppNavigation(
    repository: ShiftRepository,
    onNavigateToTemplateEdit: (Long?) -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedIndex) {
            0 -> HomeScreen(
                repository = repository,
                modifier = Modifier.padding(innerPadding)
            )
            1 -> CalendarScreen(
                repository = repository,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> TemplateListScreen(
                repository = repository,
                onEditTemplate = onNavigateToTemplateEdit,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}