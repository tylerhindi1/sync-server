package com.sakethh.linkora.data.repository

import com.sakethh.linkora.Constants
import com.sakethh.linkora.domain.LinkType
import com.sakethh.linkora.domain.MediaType
import com.sakethh.linkora.domain.Result
import com.sakethh.linkora.domain.Route
import com.sakethh.linkora.domain.dto.IDBasedDTO
import com.sakethh.linkora.domain.dto.NewItemResponseDTO
import com.sakethh.linkora.domain.dto.TimeStampBasedResponse
import com.sakethh.linkora.domain.dto.TwitterMetaDataDTO
import com.sakethh.linkora.domain.dto.link.*
import com.sakethh.linkora.domain.dto.tag.LinkTagDTO
import com.sakethh.linkora.domain.model.ScrapedLinkInfo
import com.sakethh.linkora.domain.model.WebSocketEvent
import com.sakethh.linkora.domain.repository.LinksRepo
import com.sakethh.linkora.domain.tables.LinkTagTable
import com.sakethh.linkora.domain.tables.LinksTable
import com.sakethh.linkora.domain.tables.LinksTable.lastModified
import com.sakethh.linkora.domain.tables.TombstoneTable
import com.sakethh.linkora.domain.tables.helper.TombStoneHelper
import com.sakethh.linkora.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jsoup.Jsoup
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class LinksRepoImpl : LinksRepo {


    private suspend fun scrapeLinkData(
        linkUrl: String, userAgent: String
    ): ScrapedLinkInfo {
        val linkHost: String = linkUrl.host()
        val rawHTML = withContext(Dispatchers.IO) {
            Jsoup.connect(
                "http" + linkUrl.substringAfter("http").substringBefore(" ").trim()
            ).userAgent(userAgent).followRedirects(true).header("Accept", "text/html")
                .header("Accept-Encoding", "gzip,deflate").header("Accept-Language", "en;q=1.0")
                .ignoreContentType(true).maxBodySize(0)
                .ignoreHttpErrors(true).get()
        }.toString()

        val document = Jsoup.parse(rawHTML)
        val ogImage = document.select("meta[property=og:image]").attr("content")
        val twitterImage = document.select("meta[name=twitter:image]").attr("content")
        val favicon = document.select("link[rel=icon]").attr("href")
        val ogTitle = document.select("meta[property=og:title]").attr("content")
        val pageTitle = document.title()

        val imgURL = when {
            ogImage.isNotNullOrNotBlank() -> {
                if (ogImage.startsWith("/")) {
                    "https://$linkHost$ogImage"
                } else {
                    ogImage
                }
            }

            ogImage.isBlank() && twitterImage.isNotNullOrNotBlank() -> if (twitterImage.startsWith(
                    "/"
                )
            ) {
                "https://$linkHost$twitterImage"
            } else {
                twitterImage
            }

            ogImage.isBlank() && twitterImage.isBlank() && favicon.isNotNullOrNotBlank() -> {
                if (favicon.startsWith("/")) {
                    "https://$linkHost$favicon"
                } else {
                    favicon
                }
            }

            else -> ""
        }

        val title = when {
            ogTitle.isNotNullOrNotBlank() -> ogTitle
            else -> pageTitle
        }
        return ScrapedLinkInfo(title, imgURL)
    }

    private suspend fun retrieveFromVxTwitterApi(tweetURL: String): ScrapedLinkInfo {
        val httpRequest =
            HttpRequest.newBuilder(URI("https://api.vxtwitter.com/${tweetURL.substringAfter(".com/")}")).build()

        val vxTwitterResponseBody = withContext(Dispatchers.IO) {
            HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString())
                .body().run {
                    Json.decodeFromString<TwitterMetaDataDTO>(this)
                }
        }

        return ScrapedLinkInfo(
            title = vxTwitterResponseBody.text,
            imgUrl = vxTwitterResponseBody.media.takeIf { vxTwitterResponseBody.hasMedia && it.isNotEmpty() }
                ?.find { it.type in listOf("image", "video", "gif") }
                ?.let { if (it.type == "image") it.url else it.thumbnailUrl }
                ?: vxTwitterResponseBody.userPfp,
            mediaType = if (vxTwitterResponseBody.media.isNotEmpty() && vxTwitterResponseBody.media.first().type == "video") MediaType.VIDEO else MediaType.IMAGE)
    }

    override suspend fun createANewLink(addLinkDTO: AddLinkDTO): Result<NewItemResponseDTO> {
        return try {
            val eventTimestamp = getSystemEpochSeconds()

            val addLinkDTO = if (addLinkDTO.forceRetrieveOGMetaInfo) {
                try {
                    val ogMetaInfo = if (addLinkDTO.url.isATwitterUrl()) {
                        retrieveFromVxTwitterApi(addLinkDTO.url)
                    } else {
                        scrapeLinkData(
                            addLinkDTO.url, addLinkDTO.userAgent ?: Constants.DEFAULT_USER_AGENT
                        )
                    }
                    println("Retrieved OG:\n$ogMetaInfo")
                    addLinkDTO.copy(
                        title = addLinkDTO.title ?: ogMetaInfo.title,
                        imgURL = ogMetaInfo.imgUrl,
                        mediaType = ogMetaInfo.mediaType
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    addLinkDTO
                }
            } else {
                addLinkDTO
            }

            val idOfNewlyAddedLink = transaction {
                if (addLinkDTO.linkType == LinkType.HISTORY_LINK) {
                    val historyLinkMatch =
                        LinksTable.url.eq(addLinkDTO.url).and(LinksTable.linkType.eq(LinkType.HISTORY_LINK.name))
                    val historyLinkRecords = LinksTable.selectAll().where {
                        historyLinkMatch
                    }.toList()
                    TombstoneTable.batchInsert(historyLinkRecords) {
                        it[TombstoneTable.deletedAt] = eventTimestamp
                        it[TombstoneTable.operation] = Route.DELETE_A_LINK.name
                        it[TombstoneTable.payload] = Json.encodeToString(
                            IDBasedDTO(
                                id = it[LinksTable.id].value,
                                correlation = addLinkDTO.correlation,
                                eventTimestamp = eventTimestamp
                            )
                        )
                    }
                    LinksTable.deleteWhere {
                        historyLinkMatch
                    }
                }
                LinksTable.insertAndGetId { link ->
                    link[lastModified] = eventTimestamp
                    link[linkType] = addLinkDTO.linkType.name
                    link[linkTitle] = addLinkDTO.title ?: ""
                    link[url] = addLinkDTO.url
                    link[baseURL] = addLinkDTO.baseURL
                    link[imgURL] = addLinkDTO.imgURL
                    link[note] = addLinkDTO.note
                    link[idOfLinkedFolder] = addLinkDTO.idOfLinkedFolder
                    link[userAgent] = addLinkDTO.userAgent
                    link[mediaType] = addLinkDTO.mediaType.name
                    link[markedAsImportant] = addLinkDTO.markedAsImportant
                }.also { newLinkEntityID ->
                    LinkTagTable.batchInsert(addLinkDTO.tags) { tagId ->
                        this[LinkTagTable.lastModified] = eventTimestamp
                        this[LinkTagTable.linkId] = newLinkEntityID.value
                        this[LinkTagTable.tagId] = tagId
                    }
                }
            }.value
            Result.Success(
                response = NewItemResponseDTO(
                    timeStampBasedResponse = TimeStampBasedResponse(
                        message = "Link created successfully for ${addLinkDTO.linkType.name} with id = ${idOfNewlyAddedLink}.",
                        eventTimestamp = eventTimestamp
                    ), id = idOfNewlyAddedLink, correlation = addLinkDTO.correlation
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.CREATE_A_NEW_LINK.name, payload = Json.encodeToJsonElement(
                        LinkDTO(
                            id = idOfNewlyAddedLink,
                            linkType = addLinkDTO.linkType,
                            title = addLinkDTO.title ?: "",
                            url = addLinkDTO.url,
                            baseURL = addLinkDTO.baseURL,
                            imgURL = addLinkDTO.imgURL,
                            note = addLinkDTO.note,
                            idOfLinkedFolder = addLinkDTO.idOfLinkedFolder,
                            userAgent = addLinkDTO.userAgent,
                            markedAsImportant = addLinkDTO.markedAsImportant,
                            mediaType = addLinkDTO.mediaType,
                            correlation = addLinkDTO.correlation,
                            eventTimestamp = eventTimestamp,
                            linkTags = addLinkDTO.tags.map {
                                LinkTagDTO(
                                    linkId = idOfNewlyAddedLink, tagId = it
                                )
                            })
                    )
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }


    override suspend fun deleteALink(idBasedDTO: IDBasedDTO): Result<TimeStampBasedResponse> {
        return try {
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                TombStoneHelper.insert(
                    payload = Json.encodeToString(idBasedDTO.copy(eventTimestamp = eventTimestamp)),
                    operation = Route.DELETE_A_LINK.name,
                    deletedAt = eventTimestamp
                )
                LinksTable.deleteWhere {
                    id.eq(idBasedDTO.id)
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp, message = "Link deleted successfully."
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.DELETE_A_LINK.name,
                    payload = Json.encodeToJsonElement(idBasedDTO.copy(eventTimestamp = eventTimestamp)),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun updateLinkedFolderIdOfALink(updateLinkedFolderIDDto: UpdateLinkedFolderIDDto): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = updateLinkedFolderIDDto.linkId,
                timeStamp = updateLinkedFolderIDDto.eventTimestamp,
                lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(updateLinkedFolderIDDto.linkId) and LinksTable.linkType.eq(
                        updateLinkedFolderIDDto.linkType.name
                    )
                }) {
                    it[lastModified] = eventTimestamp
                    it[idOfLinkedFolder] = updateLinkedFolderIDDto.linkId
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp, message = "idOfLinkedFolder Updated Successfully."
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UPDATE_LINKED_FOLDER_ID.name,
                    payload = Json.encodeToJsonElement(updateLinkedFolderIDDto.copy(eventTimestamp = eventTimestamp)),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun updateTitleOfTheLink(updateTitleOfTheLinkDTO: UpdateTitleOfTheLinkDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = updateTitleOfTheLinkDTO.linkId,
                timeStamp = updateTitleOfTheLinkDTO.eventTimestamp,
                lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(updateTitleOfTheLinkDTO.linkId)
                }) {
                    it[lastModified] = eventTimestamp
                    it[linkTitle] = updateTitleOfTheLinkDTO.newTitleOfTheLink
                }

            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "Title was updated successfully.", eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UPDATE_LINK_TITLE.name,
                    payload = Json.encodeToJsonElement(updateTitleOfTheLinkDTO.copy(eventTimestamp = eventTimestamp)),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun updateNote(updateNoteOfALinkDTO: UpdateNoteOfALinkDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = updateNoteOfALinkDTO.linkId,
                timeStamp = updateNoteOfALinkDTO.eventTimestamp,
                lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(updateNoteOfALinkDTO.linkId)
                }) {
                    it[lastModified] = eventTimestamp
                    it[note] = updateNoteOfALinkDTO.newNote
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "Note was updated successfully.", eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UPDATE_LINK_NOTE.name,
                    payload = Json.encodeToJsonElement(updateNoteOfALinkDTO.copy(eventTimestamp = eventTimestamp)),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun updateUserAgent(updateLinkUserAgentDTO: UpdateLinkUserAgentDTO): Result<TimeStampBasedResponse> {
        return try {
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(updateLinkUserAgentDTO.linkId) and LinksTable.linkType.eq(
                        updateLinkUserAgentDTO.linkType.name
                    )
                }) {
                    it[lastModified] = eventTimestamp
                    it[this.userAgent] = userAgent
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "User agent was updated successfully.", eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UPDATE_USER_AGENT.name,
                    payload = Json.encodeToJsonElement(updateLinkUserAgentDTO.copy(eventTimestamp = eventTimestamp)),
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun archiveALink(idBasedDTO: IDBasedDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = idBasedDTO.id, timeStamp = idBasedDTO.eventTimestamp, lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(idBasedDTO.id)
                }) {
                    it[lastModified] = eventTimestamp
                    it[linkType] = LinkType.ARCHIVE_LINK.name
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "Archived link with id : ${idBasedDTO.id} successfully", eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.ARCHIVE_LINK.name,
                    payload = Json.encodeToJsonElement(idBasedDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun unArchiveALink(idBasedDTO: IDBasedDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = idBasedDTO.id, timeStamp = idBasedDTO.eventTimestamp, lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(idBasedDTO.id)
                }) {
                    it[lastModified] = eventTimestamp
                    it[linkType] = LinkType.SAVED_LINK.name
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp,
                    message = "Unarchived link with id : ${idBasedDTO.id} successfully as ${LinkType.SAVED_LINK.name}"
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UNARCHIVE_LINK.name,
                    payload = Json.encodeToJsonElement(idBasedDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun markALinkAsImp(idBasedDTO: IDBasedDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = idBasedDTO.id, timeStamp = idBasedDTO.eventTimestamp, lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(idBasedDTO.id)
                }) {
                    it[lastModified] = eventTimestamp
                    it[markedAsImportant] = true
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "Marked link with id : ${idBasedDTO.id} as Important.", eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.MARK_AS_IMP.name,
                    payload = Json.encodeToJsonElement(idBasedDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun markALinkAsNonImp(idBasedDTO: IDBasedDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = idBasedDTO.id, timeStamp = idBasedDTO.eventTimestamp, lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(idBasedDTO.id)
                }) {
                    it[lastModified] = eventTimestamp
                    it[markedAsImportant] = false
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    message = "Marked link with id : ${idBasedDTO.id} as Non-Important.",
                    eventTimestamp = eventTimestamp
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UNMARK_AS_IMP.name,
                    payload = Json.encodeToJsonElement(idBasedDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun updateLink(linkDTO: LinkDTO): Result<TimeStampBasedResponse> {
        return try {
            LinksTable.checkForLWWConflictAndThrow(
                id = linkDTO.id, timeStamp = linkDTO.eventTimestamp, lastModifiedColumn = lastModified
            )
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.update(where = {
                    LinksTable.id.eq(linkDTO.id)
                }) {
                    it[lastModified] = eventTimestamp
                    it[linkType] = linkDTO.linkType.name
                    it[linkTitle] = linkDTO.title
                    it[url] = linkDTO.url
                    it[baseURL] = linkDTO.baseURL
                    it[imgURL] = linkDTO.imgURL
                    it[note] = linkDTO.note
                    it[idOfLinkedFolder] = linkDTO.idOfLinkedFolder
                    it[userAgent] = linkDTO.userAgent
                    it[mediaType] = linkDTO.mediaType.name
                    it[markedAsImportant] = linkDTO.markedAsImportant
                }

                val existingSelectedTags =
                    LinkTagTable.selectAll().where { LinkTagTable.linkId eq linkDTO.id }.toList().map {
                        LinkTagDTO(linkId = it[LinkTagTable.linkId], tagId = it[LinkTagTable.tagId])
                    }

                val newlySelectedTags = linkDTO.linkTags.filter {
                    it !in existingSelectedTags
                }

                val unselectedTags = existingSelectedTags.filter {
                    it !in linkDTO.linkTags
                }

                LinkTagTable.deleteWhere {
                    (LinkTagTable.linkId.eq(linkDTO.id)) and (LinkTagTable.tagId inList unselectedTags.map { it.tagId })
                }

                LinkTagTable.batchInsert(newlySelectedTags) {
                    this[LinkTagTable.tagId] = it.tagId
                    this[LinkTagTable.linkId] = it.linkId
                    this[LinkTagTable.lastModified] = eventTimestamp
                }
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp, message = "Updated the link (id : ${linkDTO.id}) successfully."
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.UPDATE_LINK.name,
                    payload = Json.encodeToJsonElement(linkDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    override suspend fun deleteDuplicateLinks(deleteDuplicateLinksDTO: DeleteDuplicateLinksDTO): Result<TimeStampBasedResponse> {
        return try {
            val eventTimestamp = getSystemEpochSeconds()
            transaction {
                LinksTable.deleteWhere {
                    LinksTable.id.inList(deleteDuplicateLinksDTO.linkIds)
                }
                TombStoneHelper.insert(
                    payload = Json.encodeToString(deleteDuplicateLinksDTO),
                    operation = Route.DELETE_DUPLICATE_LINKS.name,
                    deletedAt = eventTimestamp
                )
            }
            Result.Success(
                response = TimeStampBasedResponse(
                    eventTimestamp = eventTimestamp, message = "Deleted Duplicate links."
                ), webSocketEvent = WebSocketEvent(
                    operation = Route.DELETE_DUPLICATE_LINKS.name,
                    payload = Json.encodeToJsonElement(deleteDuplicateLinksDTO.copy(eventTimestamp = eventTimestamp))
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }
}