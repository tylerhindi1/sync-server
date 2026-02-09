package com.sakethh.linkora.data.repository

import com.sakethh.linkora.domain.DeleteMultipleItemsDTO
import com.sakethh.linkora.domain.LinkType
import com.sakethh.linkora.domain.Result
import com.sakethh.linkora.domain.Route
import com.sakethh.linkora.domain.dto.*
import com.sakethh.linkora.domain.dto.folder.MarkItemsRegularDTO
import com.sakethh.linkora.domain.model.Folder
import com.sakethh.linkora.domain.model.WebSocketEvent
import com.sakethh.linkora.domain.repository.FoldersRepo
import com.sakethh.linkora.domain.repository.MultiActionRepo
import com.sakethh.linkora.domain.tables.FoldersTable
import com.sakethh.linkora.domain.tables.FoldersTable.folderName
import com.sakethh.linkora.domain.tables.FoldersTable.isFolderArchived
import com.sakethh.linkora.domain.tables.FoldersTable.lastModified
import com.sakethh.linkora.domain.tables.FoldersTable.note
import com.sakethh.linkora.domain.tables.FoldersTable.parentFolderID
import com.sakethh.linkora.domain.tables.LinkTagTable
import com.sakethh.linkora.domain.tables.LinksTable
import com.sakethh.linkora.domain.tables.helper.TombStoneHelper
import com.sakethh.linkora.utils.checkForLWWConflictAndThrow
import com.sakethh.linkora.utils.getSystemEpochSeconds
import com.sakethh.linkora.utils.insertNewLinks
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class MultiActionRepoImpl(
    private val foldersRepo: FoldersRepo
) : MultiActionRepo {
    override suspend fun archiveMultipleItems(archiveMultipleItemsDTO: ArchiveMultipleItemsDTO): Result<TimeStampBasedResponse> {
        return try {
            if (archiveMultipleItemsDTO.linkIds.isNotEmpty()) {
                LinksTable.checkForLWWConflictAndThrow(
                    id = archiveMultipleItemsDTO.linkIds.last(),
                    timeStamp = archiveMultipleItemsDTO.eventTimestamp,
                    lastModifiedColumn = LinksTable.lastModified
                )
            }
            if (archiveMultipleItemsDTO.folderIds.isNotEmpty()) {
                FoldersTable.checkForLWWConflictAndThrow(
                    id = archiveMultipleItemsDTO.folderIds.last(),
                    timeStamp = archiveMultipleItemsDTO.eventTimestamp,
                    lastModifiedColumn = lastModified
                )
            }
            val eventTimestamp = getSystemEpochSeconds()
            var updatedRowsCount = 0
            coroutineScope {
                awaitAll(async {
                    transaction {
                        updatedRowsCount += LinksTable.update(where = {
                            LinksTable.id.inList(archiveMultipleItemsDTO.linkIds)
                        }) {
                            it[lastModified] = eventTimestamp
                            it[linkType] = LinkType.ARCHIVE_LINK.name
                        }
                    }
                }, async {
                    transaction {
                        updatedRowsCount += FoldersTable.update(where = {
                            FoldersTable.id.inList(archiveMultipleItemsDTO.folderIds)
                        }) {
                            it[lastModified] = eventTimestamp
                            it[isFolderArchived] = true
                        }
                    }
                })
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp, message = "Archived $updatedRowsCount items."
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.ARCHIVE_MULTIPLE_ITEMS.name,
                    payload = Json.encodeToJsonElement(archiveMultipleItemsDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun deleteMultipleItems(deleteMultipleItemsDTO: DeleteMultipleItemsDTO): Result<TimeStampBasedResponse> {
        val eventTimestamp = getSystemEpochSeconds()
        return try {
            deleteMultipleItemsDTO.folderIds.forEach {
                foldersRepo.deleteFolder(
                    IDBasedDTO(
                        id = it,
                        correlation = deleteMultipleItemsDTO.correlation,
                        eventTimestamp = deleteMultipleItemsDTO.eventTimestamp
                    )
                )
            }
            transaction {
                TombStoneHelper.insert(
                    payload = Json.encodeToString(deleteMultipleItemsDTO.copy(eventTimestamp = eventTimestamp)),
                    operation = Route.DELETE_MULTIPLE_ITEMS.name,
                    deletedAt = eventTimestamp
                )

                LinksTable.deleteWhere {
                    LinksTable.id.inList(deleteMultipleItemsDTO.linkIds)
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp, message = "Deleted."
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.DELETE_MULTIPLE_ITEMS.name,
                    payload = Json.encodeToJsonElement(deleteMultipleItemsDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun moveMultipleItems(moveItemsDTO: MoveItemsDTO): Result<TimeStampBasedResponse> {
        return try {
            if (moveItemsDTO.folderIds.isNotEmpty()) {
                FoldersTable.checkForLWWConflictAndThrow(
                    id = moveItemsDTO.folderIds.last(),
                    timeStamp = moveItemsDTO.eventTimestamp,
                    lastModifiedColumn = lastModified
                )
            }
            if (moveItemsDTO.linkIds.isNotEmpty()) {
                LinksTable.checkForLWWConflictAndThrow(
                    id = moveItemsDTO.linkIds.last(),
                    timeStamp = moveItemsDTO.eventTimestamp,
                    lastModifiedColumn = LinksTable.lastModified
                )
            }
            val eventTimestamp = getSystemEpochSeconds()
            var rowsUpdated = 0
            transaction {
                rowsUpdated += LinksTable.update(where = {
                    LinksTable.id.inList(moveItemsDTO.linkIds)
                }) {
                    it[lastModified] = eventTimestamp
                    it[idOfLinkedFolder] = moveItemsDTO.newParentFolderId
                    it[linkType] = moveItemsDTO.linkType.name
                }
                rowsUpdated += FoldersTable.update(where = { FoldersTable.id.inList(moveItemsDTO.folderIds) }) {
                    it[lastModified] = eventTimestamp
                    it[parentFolderID] = moveItemsDTO.newParentFolderId
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "Number of rows affected by the update = $rowsUpdated", eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.MOVE_EXISTING_ITEMS.name,
                    payload = Json.encodeToJsonElement(
                        moveItemsDTO.copy(
                            eventTimestamp = eventTimestamp
                        )
                    ),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun copyMultipleItems(copyItemsDTO: CopyItemsDTO): Result<CopyItemsHTTPResponseDTO> {
        return try {
            val eventTimestamp = getSystemEpochSeconds()
            lateinit var globalLinksOldToNewIdsMap: Map<Long, Long>
            val copiedFolderResponse = mutableListOf<CopiedFolderResponse>()
            transaction {

                // 1. Copy the root links that aren't connected to anything
                val sourceGlobalSelectedLinks = LinksTable.selectAll().where {
                    LinksTable.id inList copyItemsDTO.linkIds.values
                }.toList()

                globalLinksOldToNewIdsMap = LinksTable.insertNewLinks(
                    source = sourceGlobalSelectedLinks,
                    eventTimestamp = eventTimestamp,
                    parentFolderId = copyItemsDTO.newParentFolderId,
                    newLinkType = copyItemsDTO.linkType
                )

                insertNewLinkTags(oldToNewLinkIdsMap = globalLinksOldToNewIdsMap)

                val localToNewFolderCreationMap = mutableMapOf<Long, Long>() // local to remote

                copyItemsDTO.folders.forEach { (currentFolder, links) ->

                    val sourceFolder = FoldersTable.selectAll().where {
                        FoldersTable.id.eq(currentFolder.sourceRemoteId)
                    }.limit(1).map {
                        Folder(
                            id = it[FoldersTable.id].value,
                            name = it[folderName],
                            note = it[note],
                            parentFolderId = it[parentFolderID],
                            isArchived = it[isFolderArchived],
                            eventTimestamp = it[lastModified]
                        )
                    }.first()

                    val parentFolderOfCopiedFolder = if (currentFolder.isRootFolderForTheDestination) {
                        copyItemsDTO.newParentFolderId
                    } else {
                        localToNewFolderCreationMap.getOrElse(
                            key = currentFolder.parentOfNewlyCopiedLocalId, defaultValue = {
                                null
                            })
                    }

                    val copiedFolderId = FoldersTable.insertAndGetId { folder ->
                        folder[lastModified] = eventTimestamp
                        folder[folderName] = sourceFolder.name
                        folder[note] = sourceFolder.note
                        folder[parentFolderID] = parentFolderOfCopiedFolder
                        folder[isFolderArchived] = sourceFolder.isArchived
                    }.value

                    localToNewFolderCreationMap[currentFolder.newlyCopiedLocalId] = copiedFolderId

                    val sourceLinks = LinksTable.selectAll().where {
                        LinksTable.id.inList(links.map {
                            it.sourceRemoteId
                        })
                    }.toList()

                    val oldToNewLinkIdsMap = LinksTable.insertNewLinks(
                        source = sourceLinks,
                        eventTimestamp = eventTimestamp,
                        parentFolderId = copiedFolderId,
                        newLinkType = LinkType.FOLDER_LINK
                    )

                    insertNewLinkTags(oldToNewLinkIdsMap)

                    copiedFolderResponse.add(
                        CopiedFolderResponse(
                            currentFolder = CurrentFolder(
                                newlyCopiedLocalId = currentFolder.newlyCopiedLocalId,
                                sourceRemoteId = copiedFolderId,
                                sourceRemoteParentId = -45454, // the client doesn't give a damn about this value
                                isRootFolderForTheDestination = false, // the client doesn't give a damn about this value
                                parentOfNewlyCopiedLocalId = -45454, // the client doesn't give a damn about this value
                            ), links = links.mapNotNull { link ->
                                val newRemoteId =
                                    oldToNewLinkIdsMap[link.sourceRemoteId] ?: return@mapNotNull null

                                FolderLink(
                                    newlyCopiedLocalId = link.newlyCopiedLocalId,
                                    sourceRemoteId = newRemoteId,
                                    sourceRemoteParentId = -454545, // the client doesn't give a damn about this value
                                    isRootFolderForTheDestination = false, // the client doesn't give a damn about this value
                                    parentOfNewlyCopiedLocalId = -45454, // the client doesn't give a damn about this value
                                )
                            })
                    )
                }
            }
            Result.Success(
                response = CopyItemsHTTPResponseDTO(
                    folders = copiedFolderResponse.toList(),
                    linkIds = copyItemsDTO.linkIds.mapNotNull { (localId, oldRemoteId) ->
                        globalLinksOldToNewIdsMap[oldRemoteId]?.let { newRemoteId ->
                            localId to newRemoteId
                        }
                    }.toMap(),
                    correlation = copyItemsDTO.correlation,
                    eventTimestamp = eventTimestamp
                ),
                webSocketEvent = WebSocketEvent(
                    operation = Route.COPY_EXISTING_ITEMS.name, payload = Json.encodeToJsonElement(
                        CopyItemsSocketResponseDTO(
                            eventTimestamp = eventTimestamp, correlation = copyItemsDTO.correlation
                        )
                    )
                ),
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    private fun insertNewLinkTags(oldToNewLinkIdsMap: Map<Long, Long>) {
        val eventTimestamp = getSystemEpochSeconds()
        val previousAssignedTags = LinkTagTable.selectAll().where {
            LinkTagTable.linkId inList oldToNewLinkIdsMap.keys
        }.groupBy {
            it[LinkTagTable.linkId]
        }.mapValues {
            it.value.map {
                it[LinkTagTable.tagId]
            }
        }

        previousAssignedTags.forEach { (linkId, tagsIds) ->
            val newLinkId = oldToNewLinkIdsMap[linkId]
            if (newLinkId != null) {
                LinkTagTable.batchInsert(tagsIds) {
                    this[LinkTagTable.tagId] = it
                    this[LinkTagTable.linkId] = newLinkId
                    this[LinkTagTable.lastModified] = eventTimestamp
                }
            }
        }
    }

    override suspend fun markItemsAsRegular(markItemsRegularDTO: MarkItemsRegularDTO): Result<TimeStampBasedResponse> {
        return try {
            val eventTimestamp = getSystemEpochSeconds()
            if (markItemsRegularDTO.foldersIds.isNotEmpty()) {
                FoldersTable.checkForLWWConflictAndThrow(
                    id = markItemsRegularDTO.foldersIds.random(),
                    timeStamp = markItemsRegularDTO.eventTimestamp,
                    lastModifiedColumn = lastModified
                )
            }
            if (markItemsRegularDTO.linkIds.isNotEmpty()) {
                LinksTable.checkForLWWConflictAndThrow(
                    id = markItemsRegularDTO.linkIds.random(),
                    timeStamp = markItemsRegularDTO.eventTimestamp,
                    lastModifiedColumn = LinksTable.lastModified
                )
            }
            transaction {
                FoldersTable.update(where = {
                    FoldersTable.id.inList(markItemsRegularDTO.foldersIds)
                }) {
                    it[isFolderArchived] = false
                    it[lastModified] = eventTimestamp
                }
                +LinksTable.update(where = {
                    LinksTable.id.inList(markItemsRegularDTO.linkIds)
                }) {
                    it[linkType] = LinkType.SAVED_LINK.name
                    it[lastModified] = eventTimestamp
                }
            }.let {
                Result.Success(
                    response = TimeStampBasedResponse(
                        eventTimestamp = eventTimestamp, message = "Unarchived $it items."
                    ), webSocketEvent = WebSocketEvent(
                        operation = Route.UNARCHIVE_MULTIPLE_ITEMS.name,
                        payload = Json.encodeToJsonElement(markItemsRegularDTO.copy(eventTimestamp = eventTimestamp))
                    )
                )
            }
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }
}