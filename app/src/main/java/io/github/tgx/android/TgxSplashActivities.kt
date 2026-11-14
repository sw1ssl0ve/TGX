package io.github.tgx.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowInsetsControllerCompat
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Short, theme-aware launcher screen.
 *
 * A single, permanently enabled launcher Activity. Android's native launch splash
 * uses its static manifest theme; this first app frame reads the saved TGX theme and
 * immediately paints the matching background before opening MainActivity.
 *
 * Keep this launcher component enabled: toggling launcher Activity components while
 * the task is active can make Android remove the task from Recents on some launchers.
 */
abstract class TgxSplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val themeMode = getSharedPreferences(TgxProxyService.PREFS_NAME, MODE_PRIVATE)
            .getString(TgxProxyService.KEY_THEME_MODE, "dark") ?: "dark"
        val light = themeMode == "light"
        val background = if (light) Color.rgb(244, 246, 251) else Color.rgb(5, 5, 5)

        window.setBackgroundDrawable(background.toDrawable())
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(background)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "TGX"
        }
        val logoSize = ((160 * resources.displayMetrics.density) + 0.5f).toInt()
        root.addView(
            logo,
            FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER),
        )
        setContentView(root)

        if (savedInstanceState == null) {
            root.post {
                if (isFinishing) return@post
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                })
                overridePendingTransition(0, 0)
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }
}

class TgxSplashLauncherActivity : TgxSplashActivity()
