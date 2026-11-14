package io.github.tgx.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.core.content.edit
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.tgx.android.network.TgxMtProtoProxyServer
import io.github.tgx.android.network.TgxProxyManager
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.security.SecureRandom
import kotlin.random.Random

private data class TgxWireMotion(
    val durationMillis: Int,
    val delayMillis: Int,
    val trailLength: Float
)

private data class TgxPalette(
    val background: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val success: Color,
    val danger: Color,
    val idleButton: Color,
    val runningButton: Color,
    val errorButton: Color,
)

private val TgxDarkPalette = TgxPalette(
    background = Color(0xFF050505),
    surface = Color(0xFF101116),
    border = Color(0xFF252832),
    text = Color(0xFFF5F5F7),
    secondary = Color(0xFF8B8D96),
    accent = Color(0xFF627BFF),
    success = Color(0xFF49D17D),
    danger = Color(0xFFFF4D5F),
    idleButton = Color(0xFF17264A),
    runningButton = Color(0xFF15372D),
    errorButton = Color(0xFF35191F),
)

private val TgxLightPalette = TgxPalette(
    background = Color(0xFFF4F6FB),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFD8DDE8),
    text = Color(0xFF171A24),
    secondary = Color(0xFF687083),
    accent = Color(0xFF4D63DF),
    success = Color(0xFF18764D),
    danger = Color(0xFFC52C46),
    idleButton = Color(0xFFE5EAFE),
    runningButton = Color(0xFFDDF5E8),
    errorButton = Color(0xFFFCE3E8)
)

private val LocalTgxPalette = staticCompositionLocalOf { TgxDarkPalette }
private val TgxColors: TgxPalette @Composable get() = LocalTgxPalette.current

private fun tx(language: String, russian: String, english: String): String =
    if (language == "en") english else russian



private const val TGX_PREFERENCES_NAME = "tgx_preferences"
private const val KEY_TELEGRAM_SETUP_PROMPT_SHOWN = "telegram_setup_prompt_shown_v2"
private val TGX_SECRET_REGEX = Regex("^[0-9a-f]{32}$")

private enum class TgxScreen { Home, Settings, Donate }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uiPrefs = getSharedPreferences(TgxProxyService.PREFS_NAME, MODE_PRIVATE)
        val initialThemeMode = uiPrefs.getString(TgxProxyService.KEY_THEME_MODE, "dark") ?: "dark"

        setContent {
            var language by rememberSaveable {
                mutableStateOf(uiPrefs.getString(TgxProxyService.KEY_UI_LANGUAGE, "ru") ?: "ru")
            }
            var themeMode by rememberSaveable {
                mutableStateOf(initialThemeMode)
            }

            TGXTheme(themeMode) {
                TGXHomeScreen(
                    language = language,
                    themeMode = themeMode,
                    onLanguageChange = { selected ->
                        language = selected
                        uiPrefs.edit { putString(TgxProxyService.KEY_UI_LANGUAGE, selected) }
                    },
                    onThemeChange = { selected ->
                        themeMode = selected
                        uiPrefs.edit { putString(TgxProxyService.KEY_THEME_MODE, selected) }
                    }
                )
            }
        }
    }
}

@Composable
fun TGXTheme(
    themeMode: String,
    content: @Composable () -> Unit
) {
    val palette = if (themeMode == "light") TgxLightPalette else TgxDarkPalette
    val scheme = if (themeMode == "light") {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.success,
            background = palette.background,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.success,
            background = palette.background,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text
        )
    }
    CompositionLocalProvider(LocalTgxPalette provides palette) {
        androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
            content()
        }
    }
}

@Composable
fun TGXHomeScreen(
    language: String,
    themeMode: String,
    onLanguageChange: (String) -> Unit,
    onThemeChange: (String) -> Unit
) {

    val context = LocalContext.current

    val serverRunning by TgxProxyService.serverRunningState
    val statusMessage by TgxProxyService.statusMessageState
    val startedAt by TgxProxyService.startedAtElapsedRealtimeState

    val proxyManager = remember(context) {
        TgxProxyManager(context)
    }
    var savedSecret by remember(context) {
        mutableStateOf(SecretManager.getOrCreateSecret(context))
    }
    var secretInput by rememberSaveable {
        mutableStateOf(savedSecret)
    }
    var showSecret by rememberSaveable { mutableStateOf(value = false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDonate by rememberSaveable { mutableStateOf(false) }
    var settingsFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOpenTelegram by rememberSaveable { mutableStateOf(false) }
    var showTelegramSetupPrompt by rememberSaveable { mutableStateOf(value = false) }

    val telegramProxy = remember(savedSecret) {
        TgxProxyManager.MtProtoProxy(
            server = TgxMtProtoProxyServer.DEFAULT_HOST,
            port = TgxMtProtoProxyServer.DEFAULT_PORT,
            secret = savedSecret
        )
    }

    var elapsedSeconds by remember {
        mutableLongStateOf(0L)
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) {
            // Start TGX whether the notification permission is granted or denied.
            // Foreground services may still run when this permission is denied,
            // although Android may hide the notification from the notification drawer.
            TgxProxyService.requestStart(context)
        }

    LaunchedEffect(serverRunning, startedAt) {

        if ((!serverRunning) || (startedAt == 0L)) {
            elapsedSeconds = 0L
            return@LaunchedEffect
        }

        while (true) {
            val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
            elapsedSeconds = elapsedMillis / 1000L
            delay(1.seconds)
        }
    }

    // Telegram does not expose its saved proxy list to other apps. On the first
    // successful start, offer the user a one-time way to open Telegram's proxy link.
    LaunchedEffect(serverRunning, startedAt, pendingOpenTelegram) {
        if (!serverRunning || startedAt == 0L || pendingOpenTelegram) return@LaunchedEffect

        val preferences = context.getSharedPreferences(
            TGX_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        if (preferences.getBoolean(KEY_TELEGRAM_SETUP_PROMPT_SHOWN, false)) {
            return@LaunchedEffect
        }

        // Store before displaying so recomposition does not show the dialog repeatedly.
        preferences.edit {
            putBoolean(KEY_TELEGRAM_SETUP_PROMPT_SHOWN, true)
        }
        showTelegramSetupPrompt = true
    }

    // After saving a new secret, wait until TGX is running with that exact secret
    // before asking Telegram to add the corresponding local proxy.
    LaunchedEffect(pendingOpenTelegram, serverRunning, startedAt, savedSecret, statusMessage) {
        if (!pendingOpenTelegram) return@LaunchedEffect
        if (!serverRunning || startedAt == 0L) {
            val normalizedStatus = statusMessage.trim().lowercase()
            val isTransition = normalizedStatus.startsWith("starting") ||
                    normalizedStatus.startsWith("stopping") ||
                    normalizedStatus.startsWith("applying") ||
                    normalizedStatus.startsWith("применяем") ||
                    normalizedStatus.startsWith("запуск")
            if (statusMessageIsError(statusMessage) && !isTransition) {
                settingsFeedback = tx(language, "TGX не запустился: $statusMessage", "TGX failed to start: $statusMessage")
                pendingOpenTelegram = false
            }
            return@LaunchedEffect
        }

        delay(600.milliseconds)
        val opened = proxyManager.openTelegramProxy(
            TgxProxyManager.MtProtoProxy(
                server = TgxMtProtoProxyServer.DEFAULT_HOST,
                port = TgxMtProtoProxyServer.DEFAULT_PORT,
                secret = savedSecret
            )
        )
        pendingOpenTelegram = false
        showSettings = false
        Toast.makeText(
            context,
            tx(language, "Открываем Telegram…", "Opening Telegram…")
                .takeIf { opened }
                ?: tx(language, "Не удалось открыть Telegram. Проверь, установлен ли он.", "Could not open Telegram. Check that it is installed."),
            Toast.LENGTH_LONG
        ).show()
    }

    if (showTelegramSetupPrompt) {
        AlertDialog(
            onDismissRequest = { showTelegramSetupPrompt = false },
            containerColor = TgxColors.surface,
            titleContentColor = TgxColors.text,
            textContentColor = TgxColors.secondary,
            title = {
                Text(tx(language, "Подключить Telegram?", "Connect Telegram?"), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    tx(
                        language,
                        "TGX не может прочитать список прокси, сохранённых внутри Telegram. Открыть Telegram, чтобы добавить или включить локальный прокси 127.0.0.1:1443?",
                        "TGX cannot read the proxy list saved inside Telegram. Open Telegram to add or enable the local proxy at 127.0.0.1:1443?"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTelegramSetupPrompt = false
                        val opened = proxyManager.openTelegramProxy(telegramProxy)
                        Toast.makeText(
                            context,
                            tx(language, "Открываем Telegram…", "Opening Telegram…")
                                .takeIf { opened }
                                ?: tx(language, "Не удалось открыть Telegram. Проверь, установлен ли он.", "Could not open Telegram. Check that it is installed."),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) {
                    Text(tx(language, "ОТКРЫТЬ TELEGRAM", "OPEN TELEGRAM"), color = TgxColors.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTelegramSetupPrompt = false }) {
                    Text(tx(language, "ПОЗЖЕ", "LATER"), color = TgxColors.secondary)
                }
            }
        )
    }

    val tgxScreen = when {
        showSettings -> TgxScreen.Settings
        showDonate -> TgxScreen.Donate
        else -> TgxScreen.Home
    }

    AnimatedContent(
        targetState = tgxScreen,
        modifier = Modifier
            .fillMaxSize()
            .background(TgxColors.background),
        transitionSpec = {
            if (targetState != TgxScreen.Home) {
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth / 5 },
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(animationSpec = tween(durationMillis = 220))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth / 10 },
                            animationSpec = tween(durationMillis = 300)
                        ) + fadeOut(animationSpec = tween(durationMillis = 180)))
            } else {
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 10 },
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(animationSpec = tween(durationMillis = 220))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth / 5 },
                            animationSpec = tween(durationMillis = 300)
                        ) + fadeOut(animationSpec = tween(durationMillis = 180)))
            }
        },
        label = "screenTransition"
    ) { screen ->
        if (screen == TgxScreen.Donate) {
            DonatePage(
                language = language,
                onBack = { showDonate = false }
            )
        } else if (screen == TgxScreen.Settings) {
            ProxySettingsPage(
                running = serverRunning,
                language = language,
                themeMode = themeMode,
                onLanguageChange = { selected ->
                    onLanguageChange(selected)
                    settingsFeedback = null
                },
                onThemeChange = onThemeChange,
                secretInput = secretInput,
                showSecret = showSecret,
                feedback = settingsFeedback,
                applying = pendingOpenTelegram,
                onBack = {
                    secretInput = savedSecret
                    settingsFeedback = null
                    showSettings = false
                },
                onSecretChange = { newValue ->
                    if (newValue.length <= 32 && newValue.all { it in "0123456789abcdefABCDEF" }) {
                        secretInput = newValue.lowercase()
                        settingsFeedback = null
                    }
                },
                onToggleSecretVisibility = { showSecret = !showSecret },
                onGenerateSecret = {
                    secretInput = generateProxySecret()
                    settingsFeedback = tx(
                        language,
                        "Новый секрет создан. Нажми «Сохранить и открыть Telegram» для применения.",
                        "New secret generated. Tap “Save and open Telegram” to apply it."
                    )
                },
                onApply = {
                    val normalizedSecret = secretInput.trim().lowercase()
                    if (!TGX_SECRET_REGEX.matches(normalizedSecret)) {
                        settingsFeedback = tx(
                            language,
                            "Введи секрет из 32 шестнадцатеричных символов или сгенерируй новый.",
                            "Enter a 32-character hexadecimal secret or generate a new one."
                        )
                    } else {
                        val changed = normalizedSecret != savedSecret
                        SecretManager.setSecret(context, normalizedSecret)
                        // This explicit apply/open action supersedes the first-start prompt.
                        context.getSharedPreferences(TGX_PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .edit { putBoolean(KEY_TELEGRAM_SETUP_PROMPT_SHOWN, true) }
                        savedSecret = normalizedSecret
                        settingsFeedback = tx(language, "Конфигурация сохранена. Подготавливаем Telegram…", "Configuration saved. Preparing Telegram…")
                        pendingOpenTelegram = true

                        if (changed && serverRunning) {
                            TgxProxyService.requestRestart(context)
                        } else if (!serverRunning) {
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                TgxProxyService.requestStart(context)
                            }
                        }
                    }
                }
            )
        } else { // TgxScreen.Home
            val lowerStatus = statusMessage.trim().lowercase()
            val isBusy = listOf(
                "starting", "stopping", "applying", "запуск", "применяем", "останавливаем"
            ).any { lowerStatus.contains(it) }
            val isError = !serverRunning && statusMessageIsError(statusMessage) && !isBusy
            val isActive = serverRunning && !isBusy
            val statusColorTarget = when {
                isActive -> TgxColors.success
                isError -> TgxColors.danger
                else -> TgxColors.accent
            }
            val statusColor by animateColorAsState(
                targetValue = statusColorTarget,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "mainStatusColor"
            )
            val busyLabel = when {
                lowerStatus.contains("starting") || lowerStatus.contains("запуск") -> tx(language, "Запускаем TGX…", "Starting TGX…")
                lowerStatus.contains("stopping") || lowerStatus.contains("останавливаем") -> tx(language, "Останавливаем TGX…", "Stopping TGX…")
                lowerStatus.contains("applying") || lowerStatus.contains("применяем") -> tx(language, "Применяем конфигурацию…", "Applying configuration…")
                else -> tx(language, "Операция выполняется…", "Operation in progress…")
            }
            val statusLabel = when {
                isBusy -> busyLabel
                serverRunning -> tx(language, "TGX работает", "TGX is running")
                isError -> tx(language, "Ошибка подключения", "Connection error")
                else -> tx(language, "TGX остановлен", "TGX is stopped")
            }
            val panelTitle = when {
                isBusy -> busyLabel
                serverRunning -> tx(language, "Локальный прокси активен", "Local proxy is active")
                isError -> tx(language, "Локальный прокси недоступен", "Local proxy unavailable")
                else -> tx(language, "Локальный прокси выключен", "Local proxy is off")
            }
            val panelDetail = when {
                isBusy -> tx(language, "Подготовка локального подключения", "Preparing local connection")
                serverRunning -> tx(language, "Работает через TGX", "Working through TGX")
                isError -> tx(language, "Проверь конфигурацию TGX", "Check TGX configuration")
                else -> tx(language, "Запусти TGX для маршрута Telegram", "Start TGX to enable the Telegram route")
            }
            val buttonLabel = when {
                isBusy -> busyLabel
                serverRunning -> tx(language, "Остановить TGX", "Stop TGX")
                else -> tx(language, "Запустить TGX", "Start TGX")
            }
            val appVersion = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            val statusHint = when {
                isBusy -> tx(language, "Подготовка соединения", "Preparing the connection")
                isActive -> tx(language, "Работает через TGX", "Working through TGX")
                isError -> tx(language, "Проверь настройки TGX", "Check TGX settings")
                else -> tx(language, "Запусти TGX для маршрута Telegram", "Start TGX to route Telegram")
            }
            var wireMotionSeed by rememberSaveable {
                mutableLongStateOf(SystemClock.elapsedRealtimeNanos())
            }
            var wireMotionRequested by remember { mutableStateOf(false) }

            LaunchedEffect(isActive, isError) {
                if (isActive || isError) wireMotionRequested = false
            }
            LaunchedEffect(wireMotionRequested) {
                if (wireMotionRequested) {
                    delay(12.seconds)
                    wireMotionRequested = false
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().background(TgxColors.background)
            ) {
                val compactHeight = maxHeight < 680.dp
                val headerToStatus = (maxHeight * 0.11f).coerceIn(60.dp, 80.dp)
                val statusToHero = (maxHeight * 0.03f).coerceIn(16.dp, 22.dp)
                val heroHeight = (maxHeight * 0.31f).coerceIn(188.dp, 232.dp)
                val heroToPanel = (maxHeight * 0.04f).coerceIn(22.dp, 32.dp)
                val panelToAction = (maxHeight * 0.07f).coerceIn(40.dp, 54.dp)
                var heroBounds by remember { mutableStateOf<Rect?>(null) }

                TgxMotionBackdrop(
                    modifier = Modifier.matchParentSize(),
                    active = isActive,
                    transitioning = isBusy,
                    startRequested = wireMotionRequested,
                    motionSeed = wireMotionSeed,
                    accent = TgxColors.accent,
                    heroBounds = heroBounds
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("TGX", color = TgxColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(
                                tx(language, "Транспорт Telegram", "Telegram Transport"),
                                color = TgxColors.secondary,
                                fontSize = 13.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showDonate = true },
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TgxColors.success,
                                    contentColor = Color.White
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    tx(language, "Поддержать \u2665", "Donate \u2665"),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            var settingsGearTargetRotation by rememberSaveable { mutableFloatStateOf(0f) }
                            val settingsGearRotation by animateFloatAsState(
                                targetValue = settingsGearTargetRotation,
                                animationSpec = tween(durationMillis = 260),
                                label = "settingsGearRotation"
                            )
                            val settingsScope = rememberCoroutineScope()
                            SettingsGear(
                                accessibilityLabel = tx(language, "Настройки", "Settings"),
                                color = TgxColors.text,
                                rotation = settingsGearRotation,
                                visualScale = 0.48f,
                                modifier = Modifier.size(56.dp),
                                onClick = {
                                    settingsGearTargetRotation += 90f
                                    settingsScope.launch {
                                        delay(50.milliseconds)
                                        secretInput = savedSecret
                                        settingsFeedback = null
                                        showSettings = true
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(headerToStatus))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TgxStatusDot(active = isActive, color = statusColor, diameter = 10.dp)
                        AnimatedContent(
                            targetState = statusLabel,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                            label = "mainStatus"
                        ) { label ->
                            Text(label, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    AnimatedContent(
                        targetState = statusHint,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
                        label = "mainStatusHint"
                    ) { hint ->
                        Text(hint, color = TgxColors.secondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TgxClockGlyph(color = TgxColors.secondary)
                        Text(
                            formatElapsedTime(elapsedSeconds),
                            color = TgxColors.secondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(Modifier.height(statusToHero))
                    PremiumTelegramObject(
                        height = heroHeight,
                        onPositioned = { bounds ->
                            if (heroBounds != bounds) heroBounds = bounds
                        }
                    )

                    Spacer(Modifier.height(heroToPanel))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(CircleShape)
                            .background(TgxColors.background.copy(alpha = 0.34f))
                            .border(
                                width = 1.5.dp,
                                color = if (isError) TgxColors.danger else TgxColors.accent,
                                shape = CircleShape
                            )
                            .semantics {
                                contentDescription = buttonLabel
                                role = Role.Button
                            }
                            .clickable(
                                enabled = !isBusy,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = {
                                    if (serverRunning) {
                                        TgxProxyService.requestStop(context)
                                    } else {
                                        wireMotionSeed = maxOf(
                                            wireMotionSeed + 1L,
                                            SystemClock.elapsedRealtimeNanos()
                                        )
                                        wireMotionRequested = true
                                        if (
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            TgxProxyService.requestStart(context)
                                        }
                                    }
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TgxMainActionIcon(running = serverRunning, color = TgxColors.text)
                        Spacer(Modifier.width(10.dp))
                        AnimatedContent(
                            targetState = buttonLabel,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                            label = "mainAction"
                        ) { label ->
                            Text(label, color = TgxColors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(Modifier.height(panelToAction))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(TgxColors.surface.copy(alpha = 0.72f))
                            .border(1.dp, TgxColors.border.copy(alpha = 0.72f), RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 23.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TgxStatusDot(active = isActive, color = statusColor, diameter = 10.dp)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            AnimatedContent(
                                targetState = panelTitle,
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                                label = "proxyStatus"
                            ) { title ->
                                Text(title, color = TgxColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(panelDetail, color = TgxColors.secondary, fontSize = 11.sp)
                        }
                        ProxyStatusMark(active = isActive, color = statusColor)
                    }

                    Spacer(Modifier.height(if (compactHeight) 18.dp else 24.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TgxConnectionGlyph(color = TgxColors.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isBusy -> tx(language, "Соединение меняется", "Connection updating")
                                isActive -> tx(language, "Соединение активно", "Connection active")
                                isError -> tx(language, "Соединение недоступно", "Connection unavailable")
                                else -> tx(language, "Соединение неактивно", "Connection inactive")
                            },
                            color = TgxColors.secondary,
                            fontSize = 11.sp
                        )

                    }

                    Spacer(Modifier.height(if (compactHeight) 18.dp else 24.dp))
                    Text(
                        text = if (appVersion.isBlank()) {
                            "TGX"
                        } else {
                            "TGX • v$appVersion"
                        },
                        color = TgxColors.secondary,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TgxMotionBackdrop(
    modifier: Modifier = Modifier,
    active: Boolean,
    transitioning: Boolean,
    startRequested: Boolean,
    motionSeed: Long,
    accent: Color,
    heroBounds: Rect?
) {
    val wireCount = 60
    val motionActive = active || transitioning || startRequested
    val motionSpecs = remember(motionSeed) {
        val random = Random(motionSeed)
        val immediateWire = random.nextInt(wireCount)
        List(wireCount) { wire ->
            TgxWireMotion(
                durationMillis = random.nextInt(1450, 3900),
                delayMillis = if (wire == immediateWire) 0 else random.nextInt(80, 1800),
                trailLength = 0.12f + random.nextFloat() * 0.14f
            )
        }
    }
    val wirePhases = key(motionSeed) {
        if (motionActive) {
            val transition = rememberInfiniteTransition(label = "perPressWireMotion")
            motionSpecs.mapIndexed { wire, spec ->
                val phase by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = spec.durationMillis,
                            delayMillis = spec.delayMillis,
                            easing = LinearEasing
                        ),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "screenWirePhase$wire"
                )
                phase
            }
        } else {
            emptyList()
        }
    }

    Canvas(modifier) {
        if (motionActive && heroBounds != null) {
            val boardWidth = minOf(heroBounds.width * 0.80f, 380.dp.toPx())
            val boardDepth = minOf(108.dp.toPx(), heroBounds.height * 0.62f)
            val boardSkew = minOf(boardWidth * 0.32f, boardDepth * 0.90f)
            val center = Offset(heroBounds.center.x, heroBounds.top + heroBounds.height * 0.52f)
            val topLeft = Offset(center.x - boardWidth / 2f + boardSkew, center.y - boardDepth / 2f)
            val topRight = Offset(center.x + boardWidth / 2f, center.y - boardDepth / 2f)
            val bottomRight = Offset(center.x + boardWidth / 2f - boardSkew, center.y + boardDepth / 2f)
            val bottomLeft = Offset(center.x - boardWidth / 2f, center.y + boardDepth / 2f)

            fun project(u: Float, v: Float): Offset {
                val top = Offset(
                    x = topLeft.x + (topRight.x - topLeft.x) * u,
                    y = topLeft.y + (topRight.y - topLeft.y) * u
                )
                val bottom = Offset(
                    x = bottomLeft.x + (bottomRight.x - bottomLeft.x) * u,
                    y = bottomLeft.y + (bottomRight.y - bottomLeft.y) * u
                )
                return Offset(
                    x = top.x + (bottom.x - top.x) * v,
                    y = top.y + (bottom.y - top.y) * v
                )
            }

            val topEdgeY = center.y - boardDepth / 2f
            val wireStartV = -topEdgeY / boardDepth - 0.5f
            val wireEndV = (size.height - topEdgeY) / boardDepth + 0.5f
            wirePhases.forEachIndexed { wire, phase ->
                val u = 0.025f + wire * (0.95f / (wireCount - 1))
                val spec = motionSpecs[wire]
                val headV = wireStartV + phase * (wireEndV - wireStartV)
                val tailV = maxOf(wireStartV, headV - spec.trailLength)
                val tail = project(u, tailV)
                val head = project(u, headV)
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            accent.copy(alpha = 0.34f),
                            Color.White.copy(alpha = 0.92f)
                        ),
                        start = tail,
                        end = head
                    ),
                    start = tail,
                    end = head,
                    strokeWidth = 1.35.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun TgxStatusDot(active: Boolean, color: Color, diameter: Dp = 8.dp) {
    if (active) {
        val pulseTransition = rememberInfiniteTransition(label = "tgxStatusPulse")
        val pulse by pulseTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "statusPulse"
        )
        Box(
            modifier = Modifier
                .size(diameter)
                .graphicsLayer {
                    alpha = 0.62f + 0.38f * pulse
                    scaleX = 0.82f + 0.18f * pulse
                    scaleY = 0.82f + 0.18f * pulse
                }
                .background(color, CircleShape)
        )
    } else {
        Box(Modifier.size(diameter).background(color.copy(alpha = 0.82f), CircleShape))
    }
}

@Composable
private fun PremiumTelegramObject(
    height: Dp,
    onPositioned: (Rect) -> Unit
) {
    TelegramPlatformArtwork(
        height = height,
        onPositioned = onPositioned
    )
}

@Composable
private fun TelegramPlatformArtwork(
    height: Dp,
    onPositioned: (Rect) -> Unit
) {
    val planeColor = TgxColors.accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                val bounds = Rect(
                    left = position.x,
                    top = position.y,
                    right = position.x + coordinates.size.width,
                    bottom = position.y + coordinates.size.height
                )
                onPositioned(bounds)
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(width = 160.dp, height = 135.dp)) {
            val sx = size.width / 703f
            val sy = size.height / 594f
            val plane = Path().apply {
                moveTo(31f * sx, 285f * sy)
                cubicTo(13f * sx, 295f * sy, 29f * sx, 314f * sy, 54f * sx, 324f * sy)
                cubicTo(87f * sx, 337f * sy, 140f * sx, 352f * sy, 169f * sx, 347f * sy)
                cubicTo(195f * sx, 344f * sy, 219f * sx, 330f * sy, 249f * sx, 311f * sy)
                lineTo(455f * sx, 177f * sy)
                cubicTo(470f * sx, 167f * sy, 480f * sx, 179f * sy, 465f * sx, 199f * sy)
                cubicTo(425f * sx, 247f * sy, 353f * sx, 304f * sy, 302f * sx, 368f * sy)
                cubicTo(284f * sx, 390f * sy, 289f * sx, 406f * sy, 312f * sx, 423f * sy)
                lineTo(479f * sx, 532f * sy)
                cubicTo(518f * sx, 558f * sy, 554f * sx, 541f * sy, 563f * sx, 494f * sy)
                lineTo(637f * sx, 76f * sy)
                cubicTo(646f * sx, 30f * sy, 615f * sx, 28f * sy, 566f * sx, 42f * sy)
                close()
            }
            drawPath(plane, planeColor)
        }
    }
}

@Composable
private fun ProxyStatusMark(active: Boolean, color: Color) {
    val backgroundColor = TgxColors.background
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.6.dp.toPx()
        if (active) {
            drawCircle(color = color)
            val check = Path().apply {
                moveTo(size.width * 0.27f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.35f)
            }
            drawPath(check, color = backgroundColor, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        } else {
            drawCircle(color = color.copy(alpha = 0.78f), style = Stroke(width = stroke))
        }
    }
}

@Composable
private fun TgxMainActionIcon(running: Boolean, color: Color) {
    Canvas(Modifier.size(14.dp)) {
        if (running) {
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
                size = Size(size.width * 0.64f, size.height * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
            )
        } else {
            val play = Path().apply {
                moveTo(size.width * 0.26f, size.height * 0.14f)
                lineTo(size.width * 0.82f, size.height * 0.5f)
                lineTo(size.width * 0.26f, size.height * 0.86f)
                close()
            }
            drawPath(play, color = color)
        }
    }
}

@Composable
private fun TgxConnectionGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val bolt = Path().apply {
            moveTo(size.width * 0.58f, size.height * 0.03f)
            lineTo(size.width * 0.22f, size.height * 0.55f)
            lineTo(size.width * 0.48f, size.height * 0.55f)
            lineTo(size.width * 0.38f, size.height * 0.97f)
            lineTo(size.width * 0.80f, size.height * 0.38f)
            lineTo(size.width * 0.54f, size.height * 0.38f)
            close()
        }
        drawPath(bolt, color)
    }
}

@Composable
private fun TgxClockGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.4.dp.toPx()
        drawCircle(color = color, radius = size.minDimension * 0.42f, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x, size.height * 0.25f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            color = color,
            start = center,
            end = Offset(size.width * 0.69f, center.y),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
private fun SettingsGear(
    accessibilityLabel: String,
    color: Color,
    rotation: Float,
    modifier: Modifier = Modifier,
    visualScale: Float = 1f,
    onClick: () -> Unit
) {
    val backgroundColor = TgxColors.background
    Canvas(
        modifier = modifier.semantics {
            contentDescription = accessibilityLabel
            role = Role.Button
        }.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension * visualScale
        val bodyRadius = unit * 0.30f
        val toothLength = unit * 0.115f
        val toothWidth = unit * 0.15f
        val toothOverlap = unit * 0.035f
        rotate(degrees = rotation, pivot = center) {
            repeat(8) { index ->
                rotate(degrees = index * 45f, pivot = center) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(center.x - toothWidth / 2f, center.y - bodyRadius - toothLength),
                        size = Size(toothWidth, toothLength + toothOverlap),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    )
                }
            }
            drawCircle(color = color, radius = bodyRadius, center = center)
            drawCircle(color = backgroundColor, radius = unit * 0.20f, center = center)
        }
    }
}

@Composable
private fun ProxySettingsPage(
    running: Boolean,
    language: String,
    themeMode: String,
    onLanguageChange: (String) -> Unit,
    onThemeChange: (String) -> Unit,
    secretInput: String,
    showSecret: Boolean,
    feedback: String?,
    applying: Boolean,
    onBack: () -> Unit,
    onSecretChange: (String) -> Unit,
    onToggleSecretVisibility: () -> Unit,
    onGenerateSecret: () -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TgxColors.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹",
                color = TgxColors.text,
                fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(tx(language, "Настройки прокси", "Proxy settings"), color = TgxColors.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("TGX · MTProto", color = TgxColors.secondary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(tx(language, "ИНТЕРФЕЙС", "INTERFACE"), color = TgxColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TgxColors.border, RoundedCornerShape(16.dp))
                .background(TgxColors.surface, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text(tx(language, "Язык", "Language"), color = TgxColors.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onLanguageChange("ru") },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (language == "ru") TgxColors.accent else TgxColors.background,
                        contentColor = if (language == "ru") Color.White else TgxColors.text
                    )
                ) { Text("Русский", fontSize = 12.sp) }
                Button(
                    onClick = { onLanguageChange("en") },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (language == "en") TgxColors.accent else TgxColors.background,
                        contentColor = if (language == "en") Color.White else TgxColors.text
                    )
                ) { Text("English", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Text(tx(language, "Тема", "Theme"), color = TgxColors.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onThemeChange("dark") },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (themeMode == "dark") TgxColors.accent else TgxColors.background,
                        contentColor = if (themeMode == "dark") Color.White else TgxColors.text
                    )
                ) { Text(tx(language, "Тёмная", "Dark"), fontSize = 12.sp) }
                Button(
                    onClick = { onThemeChange("light") },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (themeMode == "light") TgxColors.accent else TgxColors.background,
                        contentColor = if (themeMode == "light") Color.White else TgxColors.text
                    )
                ) { Text(tx(language, "Светлая", "Light"), fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(tx(language, "ПОДКЛЮЧЕНИЕ MTPROTO", "MTPROTO CONNECTION"), color = TgxColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TgxColors.border, RoundedCornerShape(16.dp))
                .background(TgxColors.surface, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadOnlyProxyValue(
                    label = tx(language, "IP-адрес", "IP address"),
                    value = TgxMtProtoProxyServer.DEFAULT_HOST,
                    modifier = Modifier.weight(1f)
                )
                ReadOnlyProxyValue(
                    label = tx(language, "Порт", "Port"),
                    value = TgxMtProtoProxyServer.DEFAULT_PORT.toString(),
                    modifier = Modifier.weight(0.45f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(tx(language, "Ключ / Secret", "Secret key"), color = TgxColors.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = onSecretChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !applying,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TgxColors.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TgxColors.text,
                        unfocusedTextColor = TgxColors.text,
                        focusedBorderColor = TgxColors.accent,
                        unfocusedBorderColor = TgxColors.border,
                        cursorColor = TgxColors.accent,
                        focusedContainerColor = TgxColors.background,
                        unfocusedContainerColor = TgxColors.background,
                        focusedLabelColor = TgxColors.accent,
                        unfocusedLabelColor = TgxColors.secondary
                    )
                )
                Button(
                    onClick = onGenerateSecret,
                    enabled = !applying,
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TgxColors.accent,
                        contentColor = Color.White,
                        disabledContainerColor = TgxColors.border,
                        disabledContentColor = TgxColors.secondary
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("↻", fontSize = 26.sp, fontWeight = FontWeight.Normal)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onToggleSecretVisibility, enabled = !applying) {
                    Text(if (showSecret) tx(language, "Скрыть ключ", "Hide secret") else tx(language, "Показать ключ", "Show secret"), color = TgxColors.accent, fontSize = 12.sp)
                }
                Text("${secretInput.length}/32", color = TgxColors.secondary, fontSize = 11.sp)
            }
            Text(
                text = tx(language, "Новый секрет нужно применить и в TGX, и в Telegram.", "The new secret must be applied in both TGX and Telegram."),
                color = TgxColors.secondary,
                fontSize = 11.sp
            )
        }

        if (feedback != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = feedback,
                color = if (
                    feedback.contains("не удалось", ignoreCase = true) ||
                    feedback.contains("не запустился", ignoreCase = true) ||
                    feedback.contains("could not", ignoreCase = true) ||
                    feedback.contains("failed", ignoreCase = true)
                ) TgxColors.danger else TgxColors.success,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onApply,
            enabled = !applying && TGX_SECRET_REGEX.matches(secretInput.lowercase()),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TgxColors.accent,
                contentColor = Color.White,
                disabledContainerColor = TgxColors.border,
                disabledContentColor = TgxColors.secondary
            )
        ) {
            Text(
                if (applying) tx(language, "ПРИМЕНЯЕМ КОНФИГУРАЦИЮ…", "APPLYING CONFIGURATION…") else tx(language, "СОХРАНИТЬ И ОТКРЫТЬ TELEGRAM", "SAVE AND OPEN TELEGRAM"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onBack,
            enabled = !applying,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tx(language, "Отмена", "Cancel"), color = TgxColors.secondary)
        }
        Text(
            text = if (running) tx(language, "TGX сейчас запущен", "TGX is currently running") else tx(language, "TGX будет запущен перед открытием Telegram", "TGX will start before opening Telegram"),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = TgxColors.secondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReadOnlyProxyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .border(1.dp, TgxColors.border, RoundedCornerShape(10.dp))
            .background(TgxColors.background, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(label, color = TgxColors.secondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = TgxColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatElapsedTime(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600L
    val minutes = (safeSeconds % 3600L) / 60L
    val seconds = safeSeconds % 60L
    return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun generateProxySecret(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    val hex = "0123456789abcdef"
    return buildString(32) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private fun statusMessageIsError(message: String): Boolean {
    val normalized = message.trim().lowercase()

    if (normalized.isEmpty()) return false

    // These are normal non-error states from TgxProxyService.
    val normalStates = listOf(
        "ready to start",
        "starting mtproto proxy",
        "starting mtproto proxy…",
        "stopping mtproto proxy",
        "stopping mtproto proxy…",
        "mtproto proxy stopped",
        "mtproto proxy is running",
        "proxy stopped",
        "прокси остановлен",
        "прокси активен",
        "tgx is running",
        "готов к запуску",
        "запускаем tgx…",
        "starting tgx…",
        "stopping tgx…",
        "применяем настройки прокси…",
        "applying proxy settings…"
    )

    // Anything else while the service is not running is treated as an error.
    return normalStates.none { normalized == it }
}



@Composable
private fun DonatePage(
    language: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var copiedAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedAddress) {
        if (copiedAddress != null) {
            kotlinx.coroutines.delay(2000)
            copiedAddress = null
        }
    }

    fun copyToClipboard(address: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("address", address))
        copiedAddress = address
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TgxColors.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u2039",
                color = TgxColors.text,
                fontSize = 32.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (language == "en") "Donate to TGX" else "\u041f\u043e\u0434\u0434\u0435\u0440\u0436\u0430\u0442\u044c TGX",
                    color = TgxColors.text,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (language == "en") "Telegram Transport" else "\u0422\u0440\u0430\u043d\u0441\u043f\u043e\u0440\u0442 Telegram",
                    color = TgxColors.secondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (language == "en") "Choose your preferred way to donate." else "\u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u0443\u0434\u043e\u0431\u043d\u044b\u0439 \u0441\u043f\u043e\u0441\u043e\u0431 \u043f\u043e\u0434\u0434\u0435\u0440\u0436\u043a\u0438 \u043f\u0440\u043e\u0435\u043a\u0442\u0430.",
            color = TgxColors.secondary,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(22.dp))
        Text(
            if (language == "en") "METHODS" else "\u0421\u041f\u041e\u0421\u041e\u0411\u042b",
            color = TgxColors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        // RUB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TgxColors.border, RoundedCornerShape(16.dp))
                .background(TgxColors.surface, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pay.cloudtips.ru/p/727206a2"))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (language == "en") "RUB" else "\u0420\u0443\u0431\u043b\u0438",
                    color = TgxColors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (language == "en") "Bank card" else "\u0411\u0430\u043d\u043a\u043e\u0432\u0441\u043a\u0430\u044f \u043a\u0430\u0440\u0442\u0430",
                    color = TgxColors.secondary,
                    fontSize = 12.sp
                )
            }
            Text("\u2192", color = TgxColors.accent, fontSize = 18.sp)
        }

        Spacer(Modifier.height(10.dp))

        // BTC
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TgxColors.border, RoundedCornerShape(16.dp))
                .background(TgxColors.surface, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Bitcoin", color = TgxColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("BTC", color = TgxColors.secondary, fontSize = 12.sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TgxColors.border.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { copyToClipboard("bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5") }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5",
                    color = TgxColors.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true
                )
                if (copiedAddress == "bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5") {
                    Text(
                        if (language == "en") "Copied" else "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u043d\u043e",
                        color = TgxColors.success,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ETH
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TgxColors.border, RoundedCornerShape(16.dp))
                .background(TgxColors.surface, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Ethereum", color = TgxColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("ETH", color = TgxColors.secondary, fontSize = 12.sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TgxColors.border.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { copyToClipboard("0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b") }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b",
                    color = TgxColors.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true
                )
                if (copiedAddress == "0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b") {
                    Text(
                        if (language == "en") "Copied" else "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u043d\u043e",
                        color = TgxColors.success,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // USDT
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, TgxColors.border, RoundedCornerShape(16.dp))
                .background(TgxColors.surface, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("USDT", color = TgxColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (language == "en") "Network: TRC20" else "\u0421\u0435\u0442\u044c: TRC20",
                color = TgxColors.secondary,
                fontSize = 12.sp
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TgxColors.border.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { copyToClipboard("TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg") }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg",
                    color = TgxColors.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true
                )
                if (copiedAddress == "TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg") {
                    Text(
                        if (language == "en") "Copied" else "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u043d\u043e",
                        color = TgxColors.success,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
