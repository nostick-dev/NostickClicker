/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ProfileStore {
    private const val PREFS = "control_profiles"
    private const val KEY = "profiles"
    private const val BACKUP_KEY = "profiles_backup"

    fun load(context: Context): MutableList<ControlProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val parsed = parse(prefs.getString(KEY, null))
        if (parsed != null) return parsed
        // Primary copy is missing or corrupt: fall back to the backup copy.
        val restored = parse(prefs.getString(BACKUP_KEY, null)) ?: return mutableListOf()
        save(context, restored)
        return restored
    }

    private fun parse(raw: String?): MutableList<ControlProfile>? = runCatching {
        val array = JSONArray(raw ?: "[]")
        val list = mutableListOf<ControlProfile>()
        for (i in 0 until array.length()) {
            // One broken profile must never wipe the others.
            runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()?.let { list.add(it) }
        }
        list
    }.getOrNull()

    fun save(context: Context, profiles: List<ControlProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(toJson(it)) }
        val json = array.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).putString(BACKUP_KEY, json).commit()
    }

    fun upsert(context: Context, profile: ControlProfile) {
        val profiles = load(context)
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        save(context, profiles)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    fun find(context: Context, id: String): ControlProfile? =
        load(context).firstOrNull { it.id == id }

    private fun toJson(profile: ControlProfile) = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("packageName", profile.packageName)
        put("screenEventsEnabled", profile.screenEventsEnabled)
        put("keyEventsEnabled", profile.keyEventsEnabled)
        put("controls", JSONArray().also { array -> profile.controls.forEach { array.put(eventJson(it)) } })
    }

    private fun eventJson(event: ControlEvent) = JSONObject().apply {
        put("id", event.id); put("label", event.label)
        put("type", event.type.name); put("action", event.action.name)
        put("widget", event.widget.name)
        put("keys", JSONArray(event.keySequence))
        put("x", event.x.toDouble()); put("y", event.y.toDouble())
        put("width", event.width.toDouble()); put("height", event.height.toDouble())
        put("sensitivity", event.sensitivity.toDouble()); put("intervalMs", event.intervalMs)
        put("enabled", event.enabled)
        put("targetX", event.targetX.toDouble()); put("targetY", event.targetY.toDouble())
    }

    private fun fromJson(json: JSONObject): ControlProfile {
        val controls = mutableListOf<ControlEvent>()
        val array = json.optJSONArray("controls") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = runCatching { ControlEventType.valueOf(item.optString("type")) }
                .getOrDefault(ControlEventType.SCREEN)
            // Real key injection is impossible for an accessibility service, so old
            // "key event" controls are dropped instead of showing buttons that do nothing.
            if (type == ControlEventType.KEY) continue
            controls.add(
                ControlEvent(
                    id = item.optLong("id", System.nanoTime()),
                    label = item.optString("label", "Control"),
                    type = type,
                    action = runCatching { ControlAction.valueOf(item.optString("action")) }
                        .getOrDefault(ControlAction.CLICK),
                    widget = runCatching { ControlWidget.valueOf(item.optString("widget")) }
                        .getOrDefault(ControlWidget.BUTTON),
                    x = item.optDouble("x", .5).toFloat(),
                    y = item.optDouble("y", .5).toFloat(),
                    width = item.optDouble("width", .16).toFloat(),
                    height = item.optDouble("height", .12).toFloat(),
                    sensitivity = item.optDouble("sensitivity", 1.0).toFloat(),
                    intervalMs = item.optLong("intervalMs", 120L).coerceIn(40L, 10000L),
                    enabled = item.optBoolean("enabled", true),
                    targetX = item.optDouble("targetX", -1.0).toFloat(),
                    targetY = item.optDouble("targetY", -1.0).toFloat()
                )
            )
        }
        return ControlProfile(
            id = json.optString("id", "default"),
            name = json.optString("name", "Default"),
            packageName = json.optString("packageName", ""),
            screenEventsEnabled = json.optBoolean("screenEventsEnabled", true),
            keyEventsEnabled = json.optBoolean("keyEventsEnabled", false),
            controls = controls
        )
    }
}
