package com.melcat.localfirst

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.melcat.localfirst.core.designsystem.TerminalAgentsBuilderTheme
import com.melcat.localfirst.feature.agents.presentation.AgentsRoute
import dagger.hilt.android.AndroidEntryPoint

private const val ROUTE_AGENTS = "agents"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TerminalAgentsBuilderTheme {
                AppNavHost()
            }
        }
    }
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ROUTE_AGENTS,
    ) {
        composable(ROUTE_AGENTS) {
            AgentsRoute()
        }
    }
}
