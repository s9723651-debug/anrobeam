package com.anro.beam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val direction: String, // "SEND" or "RECEIVE"
    val fileNames: List<String>,
    val totalSize: Long,
    val status: String // "SUCCESS", "FAILED", "DECLINED"
)

object TransferHistoryManager {
    private const val FILE_NAME = "transfer_history.json"

    fun getHistory(context: Context): List<HistoryEntry> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<HistoryEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val fileNamesJson = obj.getJSONArray("fileNames")
                val fileNames = mutableListOf<String>()
                for (j in 0 until fileNamesJson.length()) {
                    fileNames.add(fileNamesJson.getString(j))
                }
                list.add(
                    HistoryEntry(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        direction = obj.getString("direction"),
                        fileNames = fileNames,
                        totalSize = obj.getLong("totalSize"),
                        status = obj.getString("status")
                    )
                )
            }
            list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun addEntry(context: Context, entry: HistoryEntry) {
        val history = getHistory(context).toMutableList()
        history.add(0, entry) // Add to top
        saveHistory(context, history)
    }

    private fun saveHistory(context: Context, history: List<HistoryEntry>) {
        try {
            val jsonArray = JSONArray()
            for (entry in history) {
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("direction", entry.direction)
                    put("totalSize", entry.totalSize)
                    put("status", entry.status)
                    val fileNamesJson = JSONArray()
                    entry.fileNames.forEach { fileNamesJson.put(it) }
                    put("fileNames", fileNamesJson)
                }
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearHistory(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
