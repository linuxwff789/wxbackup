package com.nous.wxhook.sync

import java.io.File

data class DownloadJob(val remotePath: String, val name: String)

object ArchiveDownloadPlanner {
    data class Candidate(val remotePath: String, val localPath: String, val remoteSize: Long)

    fun missingOrChanged(candidates: List<Candidate>): List<DownloadJob> = candidates
        .filter { candidate ->
            val local = File(candidate.localPath)
            !local.isFile || local.length() != candidate.remoteSize
        }
        .map { candidate ->
            DownloadJob(candidate.remotePath, File(candidate.localPath).name)
        }
}
