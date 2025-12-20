package com.sakethh.linkora.presentation.routing.http

import com.sakethh.linkora.authenticate
import com.sakethh.linkora.domain.Route
import com.sakethh.linkora.domain.dto.IDBasedDTO
import com.sakethh.linkora.domain.dto.tag.CreateTagDTO
import com.sakethh.linkora.domain.dto.tag.RenameTagDTO
import com.sakethh.linkora.domain.repository.TagsRepo
import com.sakethh.linkora.utils.respondWithResult
import io.ktor.server.routing.*

fun Routing.tagsRouting(tagsRepo: TagsRepo) {
    authenticate {
        post<CreateTagDTO>(path = Route.CREATE_TAG.name) {
            respondWithResult(tagsRepo.createATag(it))
        }
        post<RenameTagDTO>(path = Route.RENAME_TAG.name) {
            respondWithResult(tagsRepo.renameATag(it))
        }
        post<IDBasedDTO>(path = Route.DELETE_TAG.name) {
            respondWithResult(tagsRepo.deleteATag(it))
        }
        get(path = Route.GET_TAGS.name) {
            respondWithResult(tagsRepo.getTags())
        }
    }
}