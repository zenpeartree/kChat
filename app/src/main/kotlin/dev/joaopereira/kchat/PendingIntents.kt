package dev.joaopereira.kchat

import android.app.PendingIntent
import android.content.Context

object PendingIntents {
    fun authComplete(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            10,
            MainActivity.launchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun authCancelled(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            11,
            MainActivity.launchIntent(context).putExtra("auth_cancelled", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun openSetup(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            12,
            MainActivity.launchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
