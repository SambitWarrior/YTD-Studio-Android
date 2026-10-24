package com.ytdstudio.app

import org.json.JSONObject

class Job(
    val id: String,
    val url: String,
    val format: String,
    val playlist: Boolean,
    val added: Double = System.currentTimeMillis() / 1000.0
) {
    @Volatile var title: String = url
    @Volatile var thumb: String = ""
    @Volatile var status: String = "queued"
    @Volatile var progress: Double = 0.0
    @Volatile var speed: Double? = null
    @Volatile var eta: Long? = null
    @Volatile var downloaded: Long = 0
    @Volatile var total: Long = 0
    @Volatile var index: Int? = null
    @Volatile var count: Int? = null
    @Volatile var itemsDone: Int = 0
    @Volatile var itemsFailed: Int = 0
    @Volatile var error: String = ""
    @Volatile var detail: String = ""
    @Volatile var file: String = ""
    @Volatile var fileUri: String = ""
    @Volatile var dir: String = "Downloads/YTD Studio"
    @Volatile var cancel: Boolean = false
    @Volatile var cookieWarn: Boolean = false

    fun reset() {
        status = "queued"; resetProgress()
        itemsDone = 0; itemsFailed = 0; error = ""; detail = ""
        file = ""; fileUri = ""; cancel = false; cookieWarn = false
    }

    fun resetProgress() {
        progress = 0.0; speed = null; eta = null; downloaded = 0; total = 0; index = null; count = null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("format", format); put("playlist", playlist)
        put("title", title); put("thumb", thumb); put("status", status); put("progress", progress)
        put("speed", speed ?: JSONObject.NULL); put("eta", eta ?: JSONObject.NULL)
        put("downloaded", downloaded); put("total", total)
        put("index", index ?: JSONObject.NULL); put("count", count ?: JSONObject.NULL)
        put("items_done", itemsDone); put("items_failed", itemsFailed)
        put("error", error); put("detail", detail); put("file", file); put("dir", dir)
        put("added", added); put("cookie_warn", cookieWarn)
    }

    /** What survives an app restart. */
    fun toStore(): JSONObject = toJson().apply { put("file_uri", fileUri) }

    companion object {
        fun fromStore(o: JSONObject): Job = Job(
            o.getString("id"), o.getString("url"), o.optString("format", "best"),
            o.optBoolean("playlist"), o.optDouble("added", System.currentTimeMillis() / 1000.0)
        ).apply {
            title = o.optString("title", url); thumb = o.optString("thumb")
            status = o.optString("status", "error"); progress = o.optDouble("progress", 0.0)
            itemsDone = o.optInt("items_done"); itemsFailed = o.optInt("items_failed")
            error = o.optString("error"); detail = o.optString("detail")
            file = o.optString("file"); fileUri = o.optString("file_uri")
            dir = o.optString("dir", dir)
        }
    }
}
