package com.sakethh.linkora.presentation.routing.http

import com.sakethh.linkora.authenticate
import com.sakethh.linkora.domain.Route
import com.sakethh.linkora.domain.dto.IDBasedDTO
import com.sakethh.linkora.domain.dto.folder.*
import com.sakethh.linkora.domain.repository.FoldersRepo
import com.sakethh.linkora.utils.respondWithResult
import io.ktor.server.routing.*

fun Routing.foldersRouting(foldersRepo: FoldersRepo) {
    authenticate {
        post<AddFolderDTO>(Route.CREATE_FOLDER.name) { folderDTO ->
            respondWithResult(foldersRepo.createFolder(folderDTO))
        }

        post<IDBasedDTO>(Route.DELETE_FOLDER.name) {
            respondWithResult(foldersRepo.deleteFolder(it))
        }

        post<IDBasedDTO>(Route.GET_CHILD_FOLDERS.name) {
            respondWithResult(foldersRepo.getChildFolders(it))
        }

        post<String>(Route.SEARCH_FOR_FOLDERS.name) {
            respondWithResult(foldersRepo.search(it))
        }

        get(Route.GET_ROOT_FOLDERS.name) {
            respondWithResult(foldersRepo.getRootFolders())
        }

        post<IDBasedDTO>(Route.MARK_FOLDER_AS_ARCHIVE.name) {
            respondWithResult(foldersRepo.markAsArchive(it))
        }

        post<IDBasedDTO>(Route.MARK_AS_REGULAR_FOLDER.name) {
            respondWithResult(foldersRepo.markAsRegularFolder(it))
        }

        post<UpdateFolderNameDTO>(Route.UPDATE_FOLDER_NAME.name) {
            respondWithResult(
                foldersRepo.updateFolderName(it)
            )
        }

        post<UpdateFolderNoteDTO>(Route.UPDATE_FOLDER_NOTE.name) {
            respondWithResult(foldersRepo.updateFolderNote(it))
        }

        post<IDBasedDTO>(Route.DELETE_FOLDER_NOTE.name) {
            respondWithResult(foldersRepo.deleteFolderNote(it))
        }

        post<MarkSelectedFoldersAsRootDTO>(Route.MARK_FOLDERS_AS_ROOT.name) {
            respondWithResult(foldersRepo.markSelectedFoldersAsRoot(it))
        }

        post<FolderDTO>(Route.UPDATE_FOLDER.name) {
            respondWithResult(foldersRepo.updateFolder(folderDTO = it))
        }
    }
}
