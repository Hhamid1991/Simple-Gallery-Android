package com.simplemobiletools.gallery.adapters

import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.adapters.MyRecyclerViewAdapter
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.dialogs.RenameItemDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.views.FastScroller
import com.simplemobiletools.commons.views.MyRecyclerView
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.dialogs.PickMediumDialog
import com.simplemobiletools.gallery.extensions.*
import com.simplemobiletools.gallery.helpers.TYPE_GIF
import com.simplemobiletools.gallery.helpers.TYPE_IMAGE
import com.simplemobiletools.gallery.helpers.TYPE_VIDEO
import com.simplemobiletools.gallery.helpers.VIEW_TYPE_LIST
import com.simplemobiletools.gallery.models.AlbumCover
import com.simplemobiletools.gallery.models.Directory
import kotlinx.android.synthetic.main.directory_item_list.view.*
import java.io.File
import java.util.*

class DirectoryAdapter(activity: BaseSimpleActivity, var dirs: MutableList<Directory>, val listener: DirOperationsListener?, recyclerView: MyRecyclerView,
                       val isPickIntent: Boolean, fastScroller: FastScroller? = null, itemClick: (Any) -> Unit) :
        MyRecyclerViewAdapter(activity, recyclerView, fastScroller, itemClick) {

    private val config = activity.config
    private val isListViewType = config.viewTypeFolders == VIEW_TYPE_LIST
    private var pinnedFolders = config.pinnedFolders
    private var scrollHorizontally = config.scrollHorizontally
    private var showMediaCount = config.showMediaCount
    private var animateGifs = config.animateGifs
    private var cropThumbnails = config.cropThumbnails
    private var currentDirectoriesHash = dirs.hashCode()

    override fun getActionMenuId() = R.menu.cab_directories

    override fun prepareItemSelection(view: View) {
        view.dir_check?.background?.applyColorFilter(primaryColor)
    }

    override fun markItemSelection(select: Boolean, view: View?) {
        view?.dir_check?.beVisibleIf(select)
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): ViewHolder {
        val layoutType = if (isListViewType) R.layout.directory_item_list else R.layout.directory_item_grid
        return createViewHolder(layoutType, parent)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val dir = dirs[position]
        val view = holder.bindView(dir, !isPickIntent) { itemView, layoutPosition ->
            setupView(itemView, dir)
        }
        bindViewHolder(holder, position, view)
    }

    override fun getItemCount() = dirs.size

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_rename).isVisible = isOneItemSelected()
            findItem(R.id.cab_change_cover_image).isVisible = isOneItemSelected()

            checkHideBtnVisibility(this)
            checkPinBtnVisibility(this)
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedPositions.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_properties -> showProperties()
            R.id.cab_rename -> renameDir()
            R.id.cab_pin -> pinFolders(true)
            R.id.cab_unpin -> pinFolders(false)
            R.id.cab_hide -> toggleFoldersVisibility(true)
            R.id.cab_unhide -> toggleFoldersVisibility(false)
//            R.id.cab_exclude -> tryExcludeFolder()
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> copyMoveTo(false)
            R.id.cab_select_all -> selectAll()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_select_photo -> changeAlbumCover(false)
            R.id.cab_use_default -> changeAlbumCover(true)
        }
    }

    override fun getSelectableItemCount() = dirs.size

    override fun onViewRecycled(holder: ViewHolder?) {
        super.onViewRecycled(holder)
        if (!activity.isActivityDestroyed()) {
            Glide.with(activity).clear(holder?.itemView?.dir_thumbnail!!)
        }
    }

    private fun checkHideBtnVisibility(menu: Menu) {
        var hiddenCnt = 0
        var unhiddenCnt = 0
        selectedPositions.mapNotNull { dirs.getOrNull(it)?.path }.forEach {
            if (File(it).containsNoMedia()) {
                hiddenCnt++
            } else {
                unhiddenCnt++
            }
        }

        menu.findItem(R.id.cab_hide).isVisible = unhiddenCnt > 0
        menu.findItem(R.id.cab_unhide).isVisible = hiddenCnt > 0
    }

    private fun checkPinBtnVisibility(menu: Menu) {
        val pinnedFolders = config.pinnedFolders
        var pinnedCnt = 0
        var unpinnedCnt = 0
        selectedPositions.mapNotNull { dirs.getOrNull(it)?.path }.forEach {
            if (pinnedFolders.contains(it)) {
                pinnedCnt++
            } else {
                unpinnedCnt++
            }
        }

        menu.findItem(R.id.cab_pin).isVisible = unpinnedCnt > 0
        menu.findItem(R.id.cab_unpin).isVisible = pinnedCnt > 0
    }

    private fun showProperties() {
        if (selectedPositions.size <= 1) {
            PropertiesDialog(activity, dirs[selectedPositions.first()].path, config.shouldShowHidden)
        } else {
            val paths = ArrayList<String>()
            selectedPositions.forEach { paths.add(dirs[it].path) }
            PropertiesDialog(activity, paths, config.shouldShowHidden)
        }
    }

    private fun renameDir() {
        val path = dirs[selectedPositions.first()].path
        val dir = File(path)
        if (activity.isAStorageRootFolder(dir.absolutePath)) {
            activity.toast(R.string.rename_folder_root)
            return
        }

        RenameItemDialog(activity, dir.absolutePath) {
            activity.runOnUiThread {
                listener?.refreshItems()
                finishActMode()
            }
        }
    }

    private fun toggleFoldersVisibility(hide: Boolean) {
        getSelectedPaths().forEach {
            if (hide) {
                if (config.wasHideFolderTooltipShown) {
                    hideFolder(it)
                } else {
                    config.wasHideFolderTooltipShown = true
                    ConfirmationDialog(activity, activity.getString(R.string.hide_folder_description)) {
                        hideFolder(it)
                    }
                }
            } else {
                activity.removeNoMedia(it) {
                    activity.scanPath(it)
                    noMediaHandled()
                }
            }
        }
    }

    private fun hideFolder(path: String) {
        activity.addNoMedia(path) {
            noMediaHandled()
        }
    }

//    private fun tryExcludeFolder() {
//        ExcludeFolderDialog(activity, getSelectedPaths().toList()) {
//            listener?.refreshItems()
//            finishActMode()
//        }
//    }

    private fun noMediaHandled() {
        activity.runOnUiThread {
            listener?.refreshItems()
            finishActMode()
        }
    }

    private fun pinFolders(pin: Boolean) {
        if (pin) {
            config.addPinnedFolders(getSelectedPaths())
        } else {
            config.removePinnedFolders(getSelectedPaths())
        }

        pinnedFolders = config.pinnedFolders
        listener?.recheckPinnedFolders()
        notifyDataSetChanged()
        finishActMode()
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val paths = ArrayList<String>()
        selectedPositions.forEach {
            val dir = File(dirs[it].path)
            paths.addAll(dir.list().filter { !activity.getIsPathDirectory(it) && it.isImageVideoGif() })
        }

        val fileDirItems = paths.map { FileDirItem(it, it.getFilenameFromPath()) } as ArrayList<FileDirItem>
        activity.tryCopyMoveFilesTo(fileDirItems, isCopyOperation) {
            config.tempFolderPath = ""
            listener?.refreshItems()
            finishActMode()
        }
    }

    private fun askConfirmDelete() {
        if (config.skipDeleteConfirmation) {
            deleteFiles()
        } else {
            ConfirmationDialog(activity) {
                deleteFiles()
            }
        }
    }

    private fun deleteFiles() {
        if (selectedPositions.isEmpty()) {
            return
        }

        val folders = ArrayList<File>(selectedPositions.size)
        val removeFolders = ArrayList<Directory>(selectedPositions.size)

        var SAFPath = ""
        selectedPositions.forEach {
            if (dirs.size > it) {
                val path = dirs[it].path
                if (activity.needsStupidWritePermissions(path) && config.treeUri.isEmpty()) {
                    SAFPath = path
                }
            }
        }

        activity.handleSAFDialog(SAFPath) {
            selectedPositions.sortedDescending().forEach {
                val directory = dirs[it]
                folders.add(File(directory.path))
                removeFolders.add(directory)
            }

            dirs.removeAll(removeFolders)
            listener?.deleteFolders(folders)
            removeSelectedItems()
        }
    }

    private fun changeAlbumCover(useDefault: Boolean) {
        if (selectedPositions.size != 1)
            return

        val path = dirs[selectedPositions.first()].path

        if (useDefault) {
            val albumCovers = getAlbumCoversWithout(path)
            storeCovers(albumCovers)
        } else {
            pickMediumFrom(path, path)
        }
    }

    private fun pickMediumFrom(targetFolder: String, path: String) {
        PickMediumDialog(activity, path) {
            if (File(it).isDirectory) {
                pickMediumFrom(targetFolder, it)
            } else {
                val albumCovers = getAlbumCoversWithout(path)
                val cover = AlbumCover(targetFolder, it)
                albumCovers.add(cover)
                storeCovers(albumCovers)
            }
        }
    }

    private fun getAlbumCoversWithout(path: String) = config.parseAlbumCovers().filterNot { it.path == path } as ArrayList

    private fun storeCovers(albumCovers: ArrayList<AlbumCover>) {
        activity.config.albumCovers = Gson().toJson(albumCovers)
        finishActMode()
        listener?.refreshItems()
    }

    private fun getSelectedPaths(): HashSet<String> {
        val paths = HashSet<String>(selectedPositions.size)
        selectedPositions.forEach { paths.add(dirs[it].path) }
        return paths
    }

    fun updateDirs(newDirs: ArrayList<Directory>) {
        if (newDirs.hashCode() != currentDirectoriesHash) {
            currentDirectoriesHash = newDirs.hashCode()
            dirs = newDirs
            notifyDataSetChanged()
            finishActMode()
        }
    }

    fun updateAnimateGifs(animateGifs: Boolean) {
        this.animateGifs = animateGifs
        notifyDataSetChanged()
    }

    fun updateCropThumbnails(cropThumbnails: Boolean) {
        this.cropThumbnails = cropThumbnails
        notifyDataSetChanged()
    }

    fun updateShowMediaCount(showMediaCount: Boolean) {
        this.showMediaCount = showMediaCount
        notifyDataSetChanged()
    }

    fun updateScrollHorizontally(scrollHorizontally: Boolean) {
        this.scrollHorizontally = scrollHorizontally
        notifyDataSetChanged()
    }

    private fun setupView(view: View, directory: Directory) {
        view.apply {
            dir_name.text = directory.name
            dir_path?.text = "${directory.path.substringBeforeLast("/")}/"
            photo_cnt.text = directory.mediaCnt.toString()
            val thumbnailType = when {
                directory.tmb.isImageFast() -> TYPE_IMAGE
                directory.tmb.isVideoFast() -> TYPE_VIDEO
                else -> TYPE_GIF
            }

            activity.loadImage(thumbnailType, directory.tmb, dir_thumbnail, scrollHorizontally, animateGifs, cropThumbnails)
            dir_pin.beVisibleIf(pinnedFolders.contains(directory.path))
            dir_sd_card.beVisibleIf(directory.isOnSDCard)
            photo_cnt.beVisibleIf(showMediaCount)

            if (isListViewType) {
                dir_name.setTextColor(textColor)
                dir_path.setTextColor(textColor)
                photo_cnt.setTextColor(textColor)
                dir_pin.applyColorFilter(textColor)
                dir_sd_card.applyColorFilter(textColor)
            }
        }
    }

    interface DirOperationsListener {
        fun refreshItems()

        fun deleteFolders(folders: ArrayList<File>)

        fun recheckPinnedFolders()
    }
}
