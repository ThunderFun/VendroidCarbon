package com.nin0dev.vendroid.utils

import com.google.gson.Gson

/**
 * Shared Gson for the JSON-for-JS interpolation sites in MainActivity and
 * VencordNative. Gson is stateless and thread-safe.
 */
internal val vdeGson = Gson()
