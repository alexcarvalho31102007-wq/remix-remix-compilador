package com.example.data.repository

import android.content.Context
import com.example.data.model.BuildHistoryRecord
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class BuildHistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("compiler_history_prefs", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, BuildHistoryRecord::class.java)
    private val adapter = moshi.adapter<List<BuildHistoryRecord>>(listType)

    @Synchronized
    fun getAllRecords(): List<BuildHistoryRecord> {
        val json = prefs.getString("history_records_json", null) ?: return emptyList()
        return try {
            adapter.fromJson(json) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addOrUpdateRecord(record: BuildHistoryRecord) {
        val currentList = getAllRecords().toMutableList()
        val index = currentList.indexOfFirst { it.id == record.id }
        if (index != -1) {
            currentList[index] = record
        } else {
            currentList.add(0, record)
        }
        saveList(currentList)
    }

    @Synchronized
    fun deleteRecord(recordId: String): Boolean {
        val currentList = getAllRecords().toMutableList()
        val removed = currentList.removeAll { it.id == recordId }
        if (removed) {
            saveList(currentList)
        }
        return removed
    }

    @Synchronized
    fun clearAll(): Boolean {
        prefs.edit().remove("history_records_json").apply()
        return true
    }

    private fun saveList(list: List<BuildHistoryRecord>) {
        try {
            val json = adapter.toJson(list)
            prefs.edit().putString("history_records_json", json).apply()
        } catch (_: Exception) {}
    }
}
