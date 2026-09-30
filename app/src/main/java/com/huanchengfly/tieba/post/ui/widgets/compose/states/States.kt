package com.huanchengfly.tieba.post.ui.widgets.compose.states

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ui.common.theme.compose.onCase
import com.huanchengfly.tieba.post.ui.widgets.compose.ErrorScreen
import com.huanchengfly.tieba.post.ui.widgets.compose.TipScreen
import kotlinx.coroutines.delay

/**
 * 加载占位延迟显示：快速加载（如缓存命中、网络良好）时不闪出动画，
 * 加载持续超过阈值才出现纸飞机兜底。
 */
private const val LOADING_SCREEN_DELAY_MS = 400L

val DefaultLoadingScreen: @Composable StateScreenScope.() -> Unit = {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_SCREEN_DELAY_MS)
        show = true
    }
    if (show) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.lottie_loading_paperplane))
        Box(
            modifier = Modifier.requiredWidthIn(max = 500.dp)
        ) {
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
            )
        }
    }
}

@Composable
fun StateScreenScope.DefaultEmptyScreen(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    @StringRes titleRes: Int = R.string.title_empty,
    @StringRes messageRes: Int? = null,
) {
    TipScreen(
        title = { Text(text = stringResource(id = titleRes)) },
        scrollable = scrollable,
        image = {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.lottie_empty_box))
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
            )
        },
        message = messageRes?.let { { Text(text = stringResource(id = it)) } },
        actions = {
            if (canReload) {
                FilledTonalButton(
                    onClick = ::reload,
                    content = { Text(text = stringResource(R.string.btn_refresh)) }
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

val DefaultErrorScreen: @Composable StateScreenScope.() -> Unit = {
    Text(
        text = stringResource(id = R.string.error_tip),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun StateScreen(
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    isError: Boolean,
    isLoading: Boolean,
    onReload: (() -> Unit)? = null,
    emptyScreen: @Composable StateScreenScope.() -> Unit = { DefaultEmptyScreen() },
    errorScreen: @Composable StateScreenScope.() -> Unit = DefaultErrorScreen,
    loadingScreen: @Composable StateScreenScope.() -> Unit = DefaultLoadingScreen,
    screenPadding: PaddingValues = WindowInsets.systemBars.asPaddingValues(),
    content: @Composable StateScreenScope.() -> Unit,
) {
    val stateScreenScope = remember(key1 = onReload) { StateScreenScope(onReload) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onCase(isError || isLoading || isEmpty) {
                padding(screenPadding).consumeWindowInsets(screenPadding)
            },
        contentAlignment = Alignment.Center
    ) {
        if (isError) {
            stateScreenScope.errorScreen()
        } else if (isLoading) {
            stateScreenScope.loadingScreen()
        } else if (isEmpty) {
            stateScreenScope.emptyScreen()
        } else {
            stateScreenScope.content()
        }
    }
}

@NonRestartableComposable
@Composable
fun StateScreen(
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    isLoading: Boolean,
    error: Throwable?,
    onReload: (() -> Unit)? = null,
    emptyScreen: @Composable StateScreenScope.() -> Unit = { DefaultEmptyScreen() },
    errorScreen: @Composable StateScreenScope.() -> Unit = { ErrorScreen(error) },
    loadingScreen: @Composable StateScreenScope.() -> Unit = DefaultLoadingScreen,
    screenPadding: PaddingValues = WindowInsets.systemBars.asPaddingValues(),
    content: @Composable StateScreenScope.() -> Unit,
) =
    StateScreen(
        modifier = modifier,
        isEmpty = isEmpty,
        isError = error != null,
        isLoading = isLoading,
        onReload = onReload,
        emptyScreen = emptyScreen,
        errorScreen = errorScreen,
        loadingScreen = loadingScreen,
        screenPadding = screenPadding,
        content = content
    )

class StateScreenScope(
    private val onReload: (() -> Unit)? = null
) {
    val canReload: Boolean
        get() = onReload != null

    fun reload() {
        onReload?.invoke()
    }
}