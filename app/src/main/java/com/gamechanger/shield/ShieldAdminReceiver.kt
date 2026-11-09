package com.gamechanger.shield

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin: Android won't uninstall the app while this is active, and turning it
 * off goes through Settings, which the accessibility service guards while locked.
 */
class ShieldAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "כיבוי ההגנה אפשרי רק אחרי פתיחת נעילה באפליקציה."
}
