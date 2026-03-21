package com.brainplaner.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple user authentication for beta testing.
 * Stores user_id in SharedPreferences.
 */
object UserAuth {
    private const val PREFS_NAME = "brainplaner_user"
    private const val KEY_USER_ID = "user_id"
    
    fun getUserId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER_ID, null)
    }
    
    fun saveUserId(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    fun clearUserId(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_USER_ID).apply()
    }
    
    fun isLoggedIn(context: Context): Boolean {
        return getUserId(context) != null
    }
}
