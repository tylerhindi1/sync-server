package com.sakethh.linkora.presentation.routing.http

import com.sakethh.linkora.authenticate
import com.sakethh.linkora.domain.DeleteMultipleItemsDTO
import com.sakethh.linkora.domain.Route
import com.sakethh.linkora.domain.dto.ArchiveMultipleItemsDTO
import com.sakethh.linkora.domain.dto.CopyItemsDTO
import com.sakethh.linkora.domain.dto.MoveItemsDTO
import com.sakethh.linkora.domain.dto.folder.MarkItemsRegularDTO
import com.sakethh.linkora.domain.repository.MultiActionRepo
import com.sakethh.linkora.utils.respondWithResult
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Routing.multiActionRouting(multiActionRepo: MultiActionRepo) {
    authenticate {
        post<ArchiveMultipleItemsDTO>(Route.ARCHIVE_MULTIPLE_ITEMS.name) {
            respondWithResult(multiActionRepo.archiveMultipleItems(it))
        }
        post<DeleteMultipleItemsDTO>(Route.DELETE_MULTIPLE_ITEMS.name) {
            respondWithResult(multiActionRepo.deleteMultipleItems(it))
        }
        post<MoveItemsDTO>(Route.MOVE_EXISTING_ITEMS.name) {
            respondWithResult(multiActionRepo.moveMultipleItems(it))
        }
        post<CopyItemsDTO>(Route.COPY_EXISTING_ITEMS.name) {
            respondWithResult(multiActionRepo.copyMultipleItems(it))
        }
        post<MarkItemsRegularDTO>(Route.UNARCHIVE_MULTIPLE_ITEMS.name) {
            respondWithResult(multiActionRepo.markItemsAsRegular(it))
        }
    }
}