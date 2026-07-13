package com.springcat.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.springcat.ide.ui.screen.EditorScreen
import com.springcat.ide.ui.screen.HomeScreen
import com.springcat.ide.ui.screen.KeystoreScreen
import com.springcat.ide.ui.screen.SignerScreen
import com.springcat.ide.ui.theme.SpringCatTheme

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val KEYSTORE = "keystore"
    const val SIGNER = "signer"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SpringCatApp() }
    }
}

@Composable
private fun SpringCatApp() {
    SpringCatTheme {
        val navController = rememberNavController()

        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(onNavigate = navController::navigate)
                }
                composable(Routes.EDITOR) {
                    EditorScreen(onBack = navController::popBackStack)
                }
                composable(Routes.KEYSTORE) {
                    KeystoreScreen(onBack = navController::popBackStack)
                }
                composable(Routes.SIGNER) {
                    SignerScreen(onBack = navController::popBackStack)
                }
            }
        }
    }
}
