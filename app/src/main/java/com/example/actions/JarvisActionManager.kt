package com.example.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ContactMatch(
    val id: String,
    val name: String,
    val phoneNumber: String
)

data class ActionResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val contacts: List<ContactMatch> = emptyList()
)

object JarvisActionManager {
    private const val TAG = "JarvisActionManager"

    fun openWhatsApp(context: Context): ActionResult {
        Log.d(TAG, "Executing openWhatsApp action")
        val pm = context.packageManager
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in packages) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ActionResult(
                    success = true,
                    action = "openWhatsApp",
                    message = "Opening WhatsApp now, sir."
                )
            }
        }

        // Web / deep link fallback
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send"))
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(webIntent)
            return ActionResult(
                success = true,
                action = "openWhatsApp",
                message = "WhatsApp application is not installed; opening the web service, sir."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp", e)
            return ActionResult(
                success = false,
                action = "openWhatsApp",
                message = "Unable to open WhatsApp on this device, sir."
            )
        }
    }

    fun openApp(context: Context, rawAppName: String): ActionResult {
        Log.d(TAG, "Executing openApp for: $rawAppName")
        val cleanName = rawAppName.trim().lowercase(Locale.ROOT)
        val pm = context.packageManager

        // Known mapping
        val packageMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android"
        )

        // Check special system shortcuts
        if (cleanName.contains("camera")) {
            return try {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "openApp", "Launching device camera, sir.")
            } catch (e: Exception) {
                ActionResult(false, "openApp", "Could not open camera, sir.")
            }
        }

        if (cleanName.contains("setting")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "openApp", "Accessing system settings, sir.")
            } catch (e: Exception) {
                ActionResult(false, "openApp", "Could not open settings, sir.")
            }
        }

        val targetPackage = packageMap.entries.firstOrNull { cleanName.contains(it.key) }?.value

        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ActionResult(true, "openApp", "Opening $rawAppName now, sir.")
            }
        }

        // Try searching installed applications
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installedApps) {
                val appLabel = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
                if (appLabel.contains(cleanName) || cleanName.contains(appLabel)) {
                    val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ActionResult(true, "openApp", "Launching $rawAppName, sir.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying applications", e)
        }

        return ActionResult(
            success = false,
            action = "openApp",
            message = "$rawAppName does not appear to be installed on this unit, sir."
        )
    }

    fun openUrl(context: Context, rawUrl: String): ActionResult {
        Log.d(TAG, "Executing openUrl for: $rawUrl")
        var validUrl = rawUrl.trim()
        if (!validUrl.startsWith("http://") && !validUrl.startsWith("https://")) {
            validUrl = "https://$validUrl"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult(true, "openUrl", "Navigating to $validUrl, sir.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL", e)
            ActionResult(false, "openUrl", "Unable to load the requested address, sir.")
        }
    }

    fun makeCall(context: Context, phoneNumber: String): ActionResult {
        Log.d(TAG, "Executing makeCall for: $phoneNumber")
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) {
            return ActionResult(false, "makeCall", "The provided phone number is invalid, sir.")
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult(true, "makeCall", "Initiating call to $cleanNumber, sir.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call", e)
            ActionResult(false, "makeCall", "Could not start telephone dialer, sir.")
        }
    }

    fun callContact(context: Context, contactName: String): ActionResult {
        Log.d(TAG, "Executing callContact for: $contactName")
        val search = contactName.trim().lowercase(Locale.ROOT)
        val matches = mutableListOf<ContactMatch>()

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""

                    if (name.lowercase(Locale.ROOT).contains(search) && number.isNotBlank()) {
                        if (matches.none { m -> m.phoneNumber == number }) {
                            matches.add(ContactMatch(id, name, number))
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Contacts permission not granted", e)
            return ActionResult(
                success = false,
                action = "callContact",
                message = "I require contact permissions to look up '$contactName', sir."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        }

        return when {
            matches.isEmpty() -> {
                ActionResult(
                    success = false,
                    action = "callContact",
                    message = "No contact matching '$contactName' was found in your records, sir."
                )
            }
            matches.size == 1 -> {
                val target = matches.first()
                makeCall(context, target.phoneNumber).copy(
                    message = "Calling ${target.name} now, sir."
                )
            }
            else -> {
                ActionResult(
                    success = true,
                    action = "callContact",
                    message = "I found ${matches.size} contacts matching '$contactName', sir. Which one would you prefer?",
                    contacts = matches.take(5)
                )
            }
        }
    }

    fun getDeviceStatus(context: Context): ActionResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())

            val status = "All systems operational, sir. The current time is $time on $date. Battery reserves are at $batteryLevel percent."
            ActionResult(true, "getDeviceStatus", status)
        } catch (e: Exception) {
            ActionResult(true, "getDeviceStatus", "Systems are online and functioning normally, sir.")
        }
    }
}
