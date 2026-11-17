package com.pascalh.overnightbot.core

import java.io.File

/** Keeps the bot state in state.json and the log in log.jsonl inside one folder. */
class FileStore(private val dir: File) : Store {
    private val stateFile = File(dir, "state.json")
    private val logFile = File(dir, "log.jsonl")

    @Synchronized
    override fun load(): State {
        if (!stateFile.exists()) return State()
        return try {
            State.fromJson(MiniJson.parse(stateFile.readText(Charsets.UTF_8)).asObj())
        } catch (e: Exception) {
            // Keep the unreadable file for inspection and start clean.
            stateFile.copyTo(File(dir, "state.broken.json"), overwrite = true)
            State()
        }
    }

    @Synchronized
    override fun save(state: State) {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(MiniJson.stringify(state.toJson()), Charsets.UTF_8)
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    @Synchronized
    override fun appendLog(row: LogRow) {
        dir.mkdirs()
        logFile.appendText(MiniJson.stringify(row.toJson()) + "\n", Charsets.UTF_8)
    }

    /** Newest rows first. */
    @Synchronized
    override fun readLog(limit: Int): List<LogRow> {
        if (!logFile.exists()) return emptyList()
        val lines = logFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        return lines.takeLast(limit).reversed().mapNotNull {
            try {
                LogRow.fromJson(MiniJson.parse(it).asObj())
            } catch (e: Exception) {
                null
            }
        }
    }
}
