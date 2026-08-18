package com.robonix.client.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.robonix.client.ui.audio.AudioScreen
import com.robonix.client.ui.chat.ChatScreen
import com.robonix.client.ui.chat.RtdlScreen
import com.robonix.client.ui.settings.SettingsScreen
import com.robonix.client.ui.theme.*
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Rtdl : Screen("rtdl", "RTDL", Icons.Default.AccountTree)
    object Audio : Screen("audio", "Audio", Icons.Default.MusicNote)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    companion object {
        val items = listOf(Chat, Rtdl, Audio, Settings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val connectionState by sharedViewModel.connectionState.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()
    val unreadRtdl by sharedViewModel.unreadRtdlCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect events for snackbar
    LaunchedEffect(Unit) {
        sharedViewModel.events.collect { event ->
            when (event) {
                is AppEvent.Snackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Robonix Client", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.width(12.dp))
                        ConnectionChip(connectionState)
                    }
                },
                actions = {
                    IconButton(onClick = { sharedViewModel.refreshSystem() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Panel.copy(alpha = 0.96f),
                    titleContentColor = Text,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Panel,
                contentColor = Text,
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                Screen.items.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (screen == Screen.Rtdl && unreadRtdl > 0) {
                                        Badge(
                                            containerColor = Red,
                                            contentColor = Text,
                                        ) { Text("$unreadRtdl", fontSize = 10.sp) }
                                    }
                                },
                            ) {
                                Icon(
                                    screen.icon,
                                    contentDescription = screen.label,
                                    tint = if (selected) Text else Dim,
                                )
                            }
                        },
                        label = {
                            Text(
                                screen.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Text else Dim,
                            )
                        },
                        selected = selected,
                        onClick = {
                            if (screen == Screen.Rtdl) sharedViewModel.resetRtdlCount()
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Cyan.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(tween(300)) { it / 4 } },
            exitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally(tween(300)) { -it / 4 } },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideInHorizontally(tween(300)) { -it / 4 } },
            popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutHorizontally(tween(300)) { it / 4 } },
        ) {
            composable(Screen.Chat.route) { ChatScreen() }
            composable(Screen.Rtdl.route) { RtdlScreen() }
            composable(Screen.Audio.route) { AudioScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}

@Composable
fun ConnectionChip(state: ConnectionState) {
    val color = when {
        state.isConnecting -> Amber
        state.isOnline -> Green
        else -> Red
    }
    val label = when {
        state.isConnecting -> "connecting..."
        else -> state.statusLabel
    }
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
