package io.github.cptmaverick_22.zardit

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.cptmaverick_22.zardit.ui.main.MainScreen
import io.github.cptmaverick_22.zardit.ui.main.MainScreenViewModel

@Composable
fun MainNavigation(viewModel: MainScreenViewModel) {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(viewModel = viewModel)
            }
        },
    )
}
