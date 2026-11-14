package io.github.tgx.android.network

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

class TgxProxyManager(
    private val context: Context,
) {

    data class MtProtoProxy(
        val server: String,
        val port: Int,
        val secret: String,
    )

    fun buildTelegramProxyUri(
        proxy: MtProtoProxy
    ): Uri {
        return (
            "tg://proxy" +
                    "?server=${Uri.encode(proxy.server)}" +
                    "&port=${proxy.port}" +
                    "&secret=${Uri.encode(proxy.secret)}"
        ).toUri()
    }

    fun openTelegramProxy(
        proxy: MtProtoProxy
    ): Boolean {

        val uri = buildTelegramProxyUri(proxy)

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                uri
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}