package com.simplemobiletools.gallery.helpers

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.gallery.extensions.config
import com.simplemobiletools.gallery.extensions.containsNoMedia
import com.simplemobiletools.gallery.extensions.doesParentHaveNoMedia
import com.simplemobiletools.gallery.models.Medium
import java.io.File
import java.util.LinkedHashMap
import kotlin.collections.ArrayList
import kotlin.collections.component1
import kotlin.collections.component2

class MediaFetcher(val context: Context) {
    var shouldStop = false

    fun getMediaByDirectories(isPickVideo: Boolean, isPickImage: Boolean): HashMap<String, ArrayList<Medium>> {
        val media = getFilesFrom("", isPickImage, isPickVideo, true)
        val excludedPaths = context.config.excludedFolders
        val includedPaths = context.config.includedFolders
        val showHidden = context.config.shouldShowHidden
        val directories = groupDirectories(media)

        val removePaths = ArrayList<String>()
        for ((path, curMedia) in directories) {
            // make sure the path has uppercase letters wherever appropriate
            val groupPath = File(curMedia.first().path).parent
            if (!File(groupPath).exists() || !shouldFolderBeVisible(groupPath, excludedPaths, includedPaths, showHidden)) {
                removePaths.add(groupPath.toLowerCase())
            }
        }

        removePaths.forEach {
            directories.remove(it)
        }

        searchNewFiles(directories, showHidden)
        return directories
    }

    // search for undiscovered media files in the folders, from which we already have some media files
    private fun searchNewFiles(directories: Map<String, ArrayList<Medium>>, showHidden: Boolean) {
        Thread {
            // try not to delay the main media file loading
            Thread.sleep(3000)
            for ((path, dirMedia) in directories) {
                if (path.contains("/.thumbnails/", true)) {
                    continue
                }

                // get the file parent this way, "path" is lowercased
                val folder = File(dirMedia.first().path).parentFile
                val files = folder.listFiles() ?: continue
                val fileCnt = files.filter { it.isFile }.size
                val newPaths = ArrayList<String>()

                if (dirMedia.size != fileCnt) {
                    val dirPaths = dirMedia.map { it.path }
                    files.forEach {
                        val filePath = it.absolutePath
                        if ((showHidden || !it.name.startsWith(".")) && !dirPaths.contains(filePath)) {
                            if (it.exists() && it.length() > 0 && it.isImageVideoGif()) {
                                newPaths.add(it.absolutePath)
                            }
                        }
                    }
                }
                context.scanPaths(newPaths)
            }
        }.start()
    }

    fun getFilesFrom(curPath: String, isPickImage: Boolean, isPickVideo: Boolean, allowRecursion: Boolean): ArrayList<Medium> {
        if (curPath.startsWith(OTG_PATH)) {
            val curMedia = ArrayList<Medium>()
            getMediaOnOTG(curPath, curMedia, isPickImage, isPickVideo, context.config.filterMedia, allowRecursion)
            return curMedia
        } else {
            val projection = arrayOf(MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.SIZE)
            val uri = MediaStore.Files.getContentUri("external")
            val selection = getSelectionQuery(curPath)
            val selectionArgs = getSelectionArgsQuery(curPath)

            return try {
                val cur = context.contentResolver.query(uri, projection, selection, selectionArgs, getSortingForFolder(curPath))
                parseCursor(context, cur, isPickImage, isPickVideo, curPath, allowRecursion)
            } catch (e: Exception) {
                ArrayList()
            }
        }
    }

    private fun getSelectionQuery(path: String): String? {
        val dataQuery = "${MediaStore.Images.Media.DATA} LIKE ?"
        return if (path.isEmpty()) {
            if (context.isAndroidFour())
                return null

            var query = "($dataQuery)"
            if (context.hasExternalSDCard()) {
                query += " OR ($dataQuery)"
            }
            query
        } else {
            "($dataQuery AND ${MediaStore.Images.Media.DATA} NOT LIKE ?)"
        }
    }

    private fun getSelectionArgsQuery(path: String): Array<String>? {
        return if (path.isEmpty()) {
            if (context.isAndroidFour()) {
                return null
            }

            if (context.hasExternalSDCard()) {
                arrayOf("${context.internalStoragePath}/%", "${context.sdCardPath}/%")
            } else {
                arrayOf("${context.internalStoragePath}/%")
            }
        } else {
            arrayOf("$path/%", "$path/%/%")
        }
    }

    private fun parseCursor(context: Context, cur: Cursor, isPickImage: Boolean, isPickVideo: Boolean, curPath: String, allowRecursion: Boolean): ArrayList<Medium> {
        val curMedia = ArrayList<Medium>()
        val config = context.config
        val filterMedia = config.filterMedia
        val showHidden = config.shouldShowHidden
        val isThirdPartyIntent = config.isThirdPartyIntent
        val doExtraCheck = config.doExtraCheck

        cur.use {
            if (cur.moveToFirst()) {
                do {
                    try {
                        if (shouldStop) {
                            break
                        }

                        val path = cur.getStringValue(MediaStore.Images.Media.DATA).trim()
                        var filename = cur.getStringValue(MediaStore.Images.Media.DISPLAY_NAME)?.trim() ?: ""
                        if (filename.isEmpty())
                            filename = path.getFilenameFromPath()

                        val isImage = filename.isImageFast()
                        val isVideo = if (isImage) false else filename.isVideoFast()
                        val isGif = if (isImage || isVideo) false else filename.isGif()

                        if (!isImage && !isVideo && !isGif)
                            continue

                        if (isVideo && (isPickImage || filterMedia and VIDEOS == 0))
                            continue

                        if (isImage && (isPickVideo || filterMedia and IMAGES == 0))
                            continue

                        if (isGif && filterMedia and GIFS == 0)
                            continue

                        if (!showHidden && filename.startsWith('.'))
                            continue

                        var size = cur.getLongValue(MediaStore.Images.Media.SIZE)
                        val file = File(path)
                        if (size == 0L) {
                            size = file.length()
                        }

                        if (size <= 0L || (doExtraCheck && !file.exists()))
                            continue

                        val dateTaken = cur.getLongValue(MediaStore.Images.Media.DATE_TAKEN)
                        val dateModified = cur.getIntValue(MediaStore.Images.Media.DATE_MODIFIED) * 1000L

                        val type = when {
                            isImage -> TYPE_IMAGE
                            isVideo -> TYPE_VIDEO
                            else -> TYPE_GIF
                        }

                        val medium = Medium(filename, path, dateModified, dateTaken, size, type)
                        curMedia.add(medium)
                    } catch (e: Exception) {
                        continue
                    }
                } while (cur.moveToNext())
            }
        }

        config.includedFolders.filter { it.isNotEmpty() && (curPath.isEmpty() || it == curPath) }.forEach {
            if (it.startsWith(OTG_PATH)) {
                getMediaOnOTG(it, curMedia, isPickImage, isPickVideo, filterMedia, allowRecursion)
            } else {
                getMediaInFolder(it, curMedia, isPickImage, isPickVideo, filterMedia, allowRecursion)
            }
        }

        if (isThirdPartyIntent && curPath.isNotEmpty() && curMedia.isEmpty()) {
            getMediaInFolder(curPath, curMedia, isPickImage, isPickVideo, filterMedia, allowRecursion)
        }

        Medium.sorting = config.getFileSorting(curPath)
        curMedia.sort()

        return curMedia
    }

    private fun groupDirectories(media: ArrayList<Medium>): HashMap<String, ArrayList<Medium>> {
        val directories = LinkedHashMap<String, ArrayList<Medium>>()
        val hasOTG = context.hasOTGConnected() && context.config.OTGBasePath.isNotEmpty()
        for (medium in media) {
            if (shouldStop) {
                break
            }

            val parentDir = (if (hasOTG && medium.path.startsWith(OTG_PATH)) medium.path.getParentPath().toLowerCase() else File(medium.path).parent?.toLowerCase())
                    ?: continue
            if (directories.containsKey(parentDir)) {
                directories[parentDir]!!.add(medium)
            } else {
                directories[parentDir] = arrayListOf(medium)
            }
        }
        return directories
    }

    private fun shouldFolderBeVisible(path: String, excludedPaths: MutableSet<String>, includedPaths: MutableSet<String>, showHidden: Boolean): Boolean {
        val file = File(path)
        return if (includedPaths.contains(path)) {
            true
        } else if (isThisOrParentExcluded(path, excludedPaths, includedPaths)) {
            false
        } else if (!showHidden && file.isDirectory && file.canonicalFile == file.absoluteFile) {
            var containsNoMediaOrDot = file.containsNoMedia() || path.contains("/.")
            if (!containsNoMediaOrDot) {
                containsNoMediaOrDot = file.doesParentHaveNoMedia()
            }
            !containsNoMediaOrDot
        } else {
            true
        }
    }

    private fun isThisOrParentExcluded(path: String, excludedPaths: MutableSet<String>, includedPaths: MutableSet<String>) =
            includedPaths.none { path.startsWith(it) } && excludedPaths.any { path.startsWith(it) }

    private fun getMediaInFolder(folder: String, curMedia: ArrayList<Medium>, isPickImage: Boolean, isPickVideo: Boolean, filterMedia: Int, allowRecursion: Boolean) {
        val files = File(folder).listFiles() ?: return
        for (file in files) {
            if (shouldStop) {
                break
            }

            if (file.isDirectory && allowRecursion) {
                getMediaInFolder(file.absolutePath, curMedia, isPickImage, isPickVideo, filterMedia, allowRecursion)
                continue
            }

            val filename = file.name
            val isImage = filename.isImageFast()
            val isVideo = if (isImage) false else filename.isVideoFast()
            val isGif = if (isImage || isVideo) false else filename.isGif()

            if (!isImage && !isVideo && !isGif)
                continue

            if (isVideo && (isPickImage || filterMedia and VIDEOS == 0))
                continue

            if (isImage && (isPickVideo || filterMedia and IMAGES == 0))
                continue

            if (isGif && filterMedia and GIFS == 0)
                continue

            val size = file.length()
            if (size <= 0L && !file.exists())
                continue

            val dateTaken = file.lastModified()
            val dateModified = file.lastModified()

            val type = when {
                isImage -> TYPE_IMAGE
                isVideo -> TYPE_VIDEO
                else -> TYPE_GIF
            }

            val medium = Medium(filename, file.absolutePath, dateModified, dateTaken, size, type)
            val isAlreadyAdded = curMedia.any { it.path == file.absolutePath }
            if (!isAlreadyAdded) {
                curMedia.add(medium)
                context.scanPath(file.absolutePath)
            }
        }
    }

    private fun getMediaOnOTG(folder: String, curMedia: ArrayList<Medium>, isPickImage: Boolean, isPickVideo: Boolean, filterMedia: Int, allowRecursion: Boolean) {
        val files = context.getDocumentFile(folder)?.listFiles() ?: return
        for (file in files) {
            if (shouldStop) {
                return
            }

            if (file.isDirectory && allowRecursion) {
                getMediaOnOTG("$folder${file.name}", curMedia, isPickImage, isPickVideo, filterMedia, allowRecursion)
                continue
            }

            val filename = file.name
            val isImage = filename.isImageFast()
            val isVideo = if (isImage) false else filename.isVideoFast()
            val isGif = if (isImage || isVideo) false else filename.isGif()

            if (!isImage && !isVideo && !isGif)
                continue

            if (isVideo && (isPickImage || filterMedia and VIDEOS == 0))
                continue

            if (isImage && (isPickVideo || filterMedia and IMAGES == 0))
                continue

            if (isGif && filterMedia and GIFS == 0)
                continue

            val size = file.length()
            if (size <= 0L && !file.exists())
                continue

            val dateTaken = file.lastModified()
            val dateModified = file.lastModified()

            val type = when {
                isImage -> TYPE_IMAGE
                isVideo -> TYPE_VIDEO
                else -> TYPE_GIF
            }

            val path = Uri.decode(file.uri.toString().replaceFirst("${context.config.OTGBasePath}%3A", OTG_PATH))
            val medium = Medium(filename, path, dateModified, dateTaken, size, type)
            val isAlreadyAdded = curMedia.any { it.path == path }
            if (!isAlreadyAdded) {
                curMedia.add(medium)
            }
        }
    }

    private fun getSortingForFolder(path: String): String {
        val sorting = context.config.getFileSorting(path)
        val sortValue = when {
            sorting and SORT_BY_NAME > 0 -> MediaStore.Images.Media.DISPLAY_NAME
            sorting and SORT_BY_SIZE > 0 -> MediaStore.Images.Media.SIZE
            sorting and SORT_BY_DATE_MODIFIED > 0 -> MediaStore.Images.Media.DATE_MODIFIED
            else -> MediaStore.Images.Media.DATE_TAKEN
        }

        return if (sorting and SORT_DESCENDING > 0) {
            "$sortValue DESC"
        } else {
            "$sortValue ASC"
        }
    }
}
