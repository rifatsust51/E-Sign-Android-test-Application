package com.rvl.myapplication

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class TicThikNotificationInterceptor : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MFA_DEBUG", "NotificationListenerService Connected successfully!")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val targetPackageName = sbn.packageName ?: ""
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        Log.d("MFA_DEBUG", "========== NEW NOTIFICATION DETECTED ==========")
        Log.d("MFA_DEBUG", "Package: $targetPackageName")
        Log.d("MFA_DEBUG", "Title: $title")
        Log.d("MFA_DEBUG", "Text: $text")

        Log.d("MFA_DEBUG", "--- INTENTS & BUTTON ACTIONS ---")
        Log.d("MFA_DEBUG", "contentIntent Exists?: ${notification.contentIntent != null}")

        val actions = notification.actions
        if (actions != null && actions.isNotEmpty()) {
            Log.d("MFA_DEBUG", "Total Action Buttons Found: ${actions.size}")
            for ((index, action) in actions.withIndex()) {
                Log.d(
                    "MFA_DEBUG",
                    "  -> Button $index Title: '${action.title}' | Intent Exists?: ${action.actionIntent != null}"
                )
            }
        } else {
            Log.d("MFA_DEBUG", "No action buttons found.")
        }
        Log.d("MFA_DEBUG", "===============================================")

        val isTargetNotification = targetPackageName.contains("ticthik", ignoreCase = true) ||
                targetPackageName.contains("reliefvalidation", ignoreCase = true) ||
                title.contains("TickTheek", ignoreCase = true) ||
                title.contains("TicThik", ignoreCase = true) ||
                text.contains("TicThik", ignoreCase = true)

        if (isTargetNotification) {
            if (PdfPreviewActivity.isActivityVisible || KeycloakWebViewActivity.isActivityVisible) {

                // STEP 1: Build intent with ALL data FIRST
                val intent = Intent(PdfPreviewActivity.ACTION_SHOW_IN_APP_PUSH)

                // FIX: Do NOT call intent.setPackage() here!
                // NotificationListenerService runs under a different UID.
                // setPackage() causes the broadcast to be silently dropped
                // on many Android 13+ devices.

                intent.putExtra("title", title)
                intent.putExtra("message", text)
                intent.putExtra("sender_package", applicationContext.packageName)
                intent.putExtra("contentIntent", notification.contentIntent)

                if (actions != null) {
                    if (actions.isNotEmpty()) {
                        intent.putExtra("action1_title", actions[0].title.toString())
                        intent.putExtra("action1_intent", actions[0].actionIntent)
                        Log.d("MFA_DEBUG", "Added action1: '${actions[0].title}'")
                    }
                    if (actions.size > 1) {
                        intent.putExtra("action2_title", actions[1].title.toString())
                        intent.putExtra("action2_intent", actions[1].actionIntent)
                        Log.d("MFA_DEBUG", "Added action2: '${actions[1].title}'")
                    }
                }

                // STEP 2: Cancel system notification
                try {
                    cancelNotification(sbn.key)
                    Log.d("MFA_DEBUG", "Cancelled system notification.")
                } catch (e: Exception) {
                    Log.e("MFA_DEBUG", "Failed to cancel: ${e.message}")
                }

                // STEP 3: Send broadcast WITHOUT setPackage
                sendBroadcast(intent)
                Log.d("MFA_DEBUG", "Broadcast SENT (no setPackage restriction).")

            } else {
                Log.d("MFA_DEBUG", "Activity not visible, letting system notification through.")
            }
        }
    }
}


