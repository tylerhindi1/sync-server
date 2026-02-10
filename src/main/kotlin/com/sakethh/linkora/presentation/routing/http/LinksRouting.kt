package com.sakethh.linkora.presentation.routing.http

import com.sakethh.linkora.authenticate
import com.sakethh.linkora.domain.Route
import com.sakethh.linkora.domain.dto.IDBasedDTO
import com.sakethh.linkora.domain.dto.link.*
import com.sakethh.linkora.domain.repository.LinksRepo
import com.sakethh.linkora.utils.respondWithResult
import io.ktor.server.routing.*

fun Routing.linksRouting(linksRepo: LinksRepo) {
    authenticate {
        post<AddLinkDTO>(Route.CREATE_A_NEW_LINK.name) {
            respondWithResult(linksRepo.createANewLink(it))
        }

        post<IDBasedDTO>(Route.DELETE_A_LINK.name) {
            respondWithResult(linksRepo.deleteALink(it))
        }

        post<UpdateLinkedFolderIDDto>(Route.UPDATE_LINKED_FOLDER_ID.name) {
            respondWithResult(
                linksRepo.updateLinkedFolderIdOfALink(it)
            )
        }

        post<UpdateTitleOfTheLinkDTO>(Route.UPDATE_LINK_TITLE.name) {
            respondWithResult(
                linksRepo.updateTitleOfTheLink(it)
            )
        }

        post<UpdateNoteOfALinkDTO>(Route.UPDATE_LINK_NOTE.name) {
            respondWithResult(
                linksRepo.updateNote(it)
            )
        }

        post<UpdateLinkUserAgentDTO>(Route.UPDATE_USER_AGENT.name) {
            respondWithResult(
                linksRepo.updateUserAgent(it)
            )
        }

        post<IDBasedDTO>(Route.ARCHIVE_LINK.name) {
            respondWithResult(linksRepo.archiveALink(it))
        }

        post<IDBasedDTO>(Route.UNARCHIVE_LINK.name) {
            respondWithResult(linksRepo.unArchiveALink(it))
        }

        post<IDBasedDTO>(Route.MARK_AS_IMP.name) {
            respondWithResult(linksRepo.markALinkAsImp(it))
        }

        post<IDBasedDTO>(Route.UNMARK_AS_IMP.name) {
            respondWithResult(linksRepo.markALinkAsNonImp(it))
        }

        post<LinkDTO>(Route.UPDATE_LINK.name) {
            respondWithResult(linksRepo.updateLink(it))
        }

        post<DeleteDuplicateLinksDTO>(Route.DELETE_DUPLICATE_LINKS.name) {
            respondWithResult(linksRepo.deleteDuplicateLinks(it))
        }

        get(Route.FORCE_SET_DEFAULT_FOLDER_TO_INTERNAL_IDS.name) {
            respondWithResult(linksRepo.forceSetDefaultFolderToInternalIds())
        }

    }
}