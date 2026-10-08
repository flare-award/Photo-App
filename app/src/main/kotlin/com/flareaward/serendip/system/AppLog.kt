package com.flareaward.serendip.system

import android.util.Log
import com.flareaward.serendip.BuildConfig

/**
 * Technical logging. Users never see any of this — every failure shown in the
 * UI is a human-readable string; the raw exception goes here.
 */
object AppLog {
    private const val TAG = "Serendip"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
    }
}
