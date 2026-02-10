package com.sakethh.linkora.utils

import com.sakethh.linkora.Constants
import com.sakethh.linkora.domain.LWWConflictException
import com.sakethh.linkora.domain.LinkType
import com.sakethh.linkora.domain.Result
import com.sakethh.linkora.domain.tables.FoldersTable
import com.sakethh.linkora.domain.tables.LinksTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

fun useSysEnvValues(): Boolean {
    return try {
        System.getenv(SysEnvKey.LINKORA_SERVER_USE_ENV_VAL.name).toBooleanStrict()
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalTime::class)
fun getSystemEpochSeconds() = Clock.System.now().epochSeconds

fun LongIdTable.checkForLWWConflictAndThrow(id: Long, timeStamp: Long, lastModifiedColumn: Column<Long>) {
    transaction {
        this@checkForLWWConflictAndThrow.select(lastModifiedColumn).where {
            this@checkForLWWConflictAndThrow.id.eq(id)
        }.let {
            try {
                val resultRow = it.single()
                if (resultRow[lastModifiedColumn] > timeStamp) {
                    throw LWWConflictException()
                }
            } catch (e: Exception) {
                if (e is LWWConflictException) {
                    throw e
                }
                e.printStackTrace()
            }
        }
    }
}

fun LinkType.defaultFolderId(): Long? = when (this) {
    LinkType.SAVED_LINK -> Constants.SAVED_LINKS_ID
    LinkType.HISTORY_LINK -> Constants.HISTORY_ID
    LinkType.IMPORTANT_LINK -> Constants.IMPORTANT_LINKS_ID
    LinkType.ARCHIVE_LINK -> Constants.ARCHIVE_ID
    LinkType.FOLDER_LINK -> null
}


fun FoldersTable.insertNewFolders(
    source: List<ResultRow>,
    eventTimestamp: Long,
    parentFolderId: Long?
): Map<Long, Long> {
    val oldToNewIdMap = mutableMapOf<Long, Long>()
    source.forEach { sourceRow ->
        val oldId = sourceRow[FoldersTable.id].value
        val newId = insertAndGetId {
            it[folderName] = sourceRow[folderName]
            it[lastModified] = eventTimestamp
            it[note] = sourceRow[note]
            it[parentFolderID] = parentFolderId
            it[isFolderArchived] = sourceRow[isFolderArchived]
        }
        oldToNewIdMap[oldId] = newId.value
    }
    return oldToNewIdMap
}

fun LinksTable.insertNewLinks(
    source: List<ResultRow>, eventTimestamp: Long, parentFolderId: Long?, newLinkType: LinkType? = null
): Map<Long, Long> {
    val oldToNewIdMap = mutableMapOf<Long, Long>()
    source.forEach { sourceRow ->
        val oldId = sourceRow[LinksTable.id].value
        val newId = insertAndGetId {
            it[lastModified] = eventTimestamp
            it[linkType] = newLinkType?.name ?: sourceRow[LinksTable.linkType]
            it[linkTitle] = sourceRow[LinksTable.linkTitle]
            it[url] = sourceRow[LinksTable.url]
            it[baseURL] = sourceRow[LinksTable.baseURL]
            it[imgURL] = sourceRow[LinksTable.imgURL]
            it[note] = sourceRow[LinksTable.note]
            it[idOfLinkedFolder] = parentFolderId
            it[userAgent] = sourceRow[LinksTable.userAgent]
            it[mediaType] = sourceRow[LinksTable.mediaType]
            it[markedAsImportant] = sourceRow[LinksTable.markedAsImportant]
        }
        oldToNewIdMap[oldId] = newId.value
    }
    return oldToNewIdMap
}

inline fun <T> tryAndCatchResult(init: () -> Result<T>): Result<T> {
    return try {
        init()
    } catch (e: Exception) {
        e.printStackTrace()
        Result.Failure(e)
    } catch (e: Error) {
        e.printStackTrace()
        Result.Failure(Exception(e.message ?: "Something went wrong"))
    }
}

inline fun tryAndCatch(init: () -> Unit) {
    try {
        init()
    } catch (e: Exception) {
        e.printStackTrace()
    } catch (e: Error) {
        e.printStackTrace()
    }
}

fun String.host(throwOnException: Boolean = true): String {
    return try {
        this.split("/")[2]
    } catch (e: Exception) {
        if (throwOnException) {
            throw e
        }
        this
    }
}

fun String?.isNotNullOrNotBlank(): Boolean {
    return !this.isNullOrBlank()
}

fun String.isATwitterUrl(): Boolean {
    return this.trim().startsWith("http://twitter.com/") or this.trim()
        .startsWith("https://twitter.com/") or this.trim().startsWith(
        "http://x.com/"
    ) or this.trim().startsWith("https://x.com/")
}