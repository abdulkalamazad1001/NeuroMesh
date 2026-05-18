package com.neuromesh.crisis.util

import android.app.ActivityManager
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether this device can realistically host the on-device Gemma model.
 *
 * A 2B-parameter LLM (~1.3 GB on disk) needs the file mmap'd plus KV-cache and
 * activation buffers on top. On phones with less than ~4 GB total RAM the OS
 * low-memory killer terminates the process during model init or first inference
 * — which is the "opens and immediately closes" crash. We detect that up front
 * and run in heuristic-only mode instead of crashing.
 */
@Singleton
class DeviceCapability @Inject constructor(private val context: Context) {

    fun totalRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /** True only when there is enough headroom to load the LLM safely. */
    fun canHostLlm(): Boolean = totalRamMb() >= MIN_RAM_FOR_LLM_MB

    fun isLowMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

    companion object {
        // Adjusted from 1800 to 3000. 
        // 2GB (2048MB) total RAM is physically insufficient to load a 1.3GB LLM
        // plus Android OS, Camera, and Mesh buffers without being killed.
        // This ensures 2GB devices run stable in HEURISTIC mode, while 
        // 4GB+ devices (which usually have ~3800MB available) can still use the LLM.
        const val MIN_RAM_FOR_LLM_MB = 3000L
    }
}
