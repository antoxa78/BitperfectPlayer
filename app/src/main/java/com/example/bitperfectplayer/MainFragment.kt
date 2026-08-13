package com.example.bitperfectplayer

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import jcifs.smb.SmbFile
import jcifs.smb.NtlmPasswordAuthenticator
import org.json.JSONArray
import org.json.JSONObject

class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var controlsAdapter: ArrayObjectAdapter
    private lateinit var playlistAdapter: ArrayObjectAdapter
    private val PREFS_NAME = "SmbShares"
    private val KEY_SHARES = "shares"
    private val PREFS_SETTINGS = "AppSettings"
    private val KEY_SCREENSAVER = "screensaver_delay"
    private val KEY_RESUME = "resume_playback"
    private val KEY_RECENT = "recent_files"
    private val KEY_MUSIC_FOLDERS = "music_folders"
    private val KEY_AUTO_SCAN = "auto_scan"
    private val KEY_COLOR_SCHEME = "color_scheme"
    private val KEY_WAVEFORM_TYPE = "waveform_type"
    private val KEY_NETWORK_BUFFER = "network_buffer"
    private val KEY_AUTO_RECONNECT = "auto_reconnect"
    private var hasAttemptedResume = false
    private var lastSelectedRowId = -1L
    private var lastSelectedColumn = 0
    private lateinit var settingsAdapter: ArrayObjectAdapter
    // Index of the "USB DAC" card inside settingsAdapter — must match the add() order below
    private val SETTINGS_USB_DAC_INDEX = 7

    private val usbStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            // USB device attached or detached — refresh the DAC card subtitle
            updateUsbDacCard()
        }
    }

    private val externalStorageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (!isAdded) return
            view?.post { refreshRows() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // jcifs-ng 2.x is configured via SmbContext (PropertyConfiguration + BaseContext).
        // System.setProperty() has no effect in jcifs-ng and has been removed.
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(0xFF000000.toInt())
        setupUIElements()
        loadRows()
        setupEventListeners()
        observePlaybackState()
        
        onItemViewSelectedListener = OnItemViewSelectedListener { itemViewHolder, item, rowViewHolder, row ->
            if (row is ListRow) {
                lastSelectedRowId = row.headerItem.id
            }
            if (rowViewHolder is ListRowPresenter.ViewHolder) {
                lastSelectedColumn = rowViewHolder.gridView.selectedPosition
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!isShowingHeaders) {
                        startHeadersTransition(true)
                    } else {
                        val activity = activity as? MainActivity
                        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Exit")
                            .setMessage("Are you sure you want to exit?")
                            .setPositiveButton("Yes") { _, _ ->
                                activity?.stopService(
                                    android.content.Intent(requireContext(), PlaybackService::class.java)
                                )
                                activity?.finishAffinity()
                                System.exit(0)
                            }
                            .setNegativeButton("No", null)
                            .show()
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        try { requireContext().registerReceiver(externalStorageReceiver, filter) } catch (e: Exception) { e.printStackTrace() }

        // Listen for USB attach/detach so the DAC card refreshes in real time
        val usbFilter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try { requireContext().registerReceiver(usbStateReceiver, usbFilter) } catch (e: Exception) { e.printStackTrace() }

        // Also refresh immediately when the fragment becomes visible
        // (covers the case where DAC state changed while we were in another screen)
        updateUsbDacCard()
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(externalStorageReceiver) } catch (e: Exception) {}
        try { requireContext().unregisterReceiver(usbStateReceiver) } catch (e: Exception) {}
    }

    private fun setupUIElements() {
        title = "Bitperfect Player"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        // Sidebar brand color: use the theme accent color as sidebar background,
        // but for light/white schemes the sidebar must stay dark so white header
        // text remains readable.
        brandColor = getSidebarColor(requireContext())
    }

    fun refreshRows(targetRowId: Long = -1L, selectedColumn: Int = -1) {
        if (::rowsAdapter.isInitialized) {
            rowsAdapter.clear()
            loadRows()
            
            if (targetRowId != -1L) {
                // Find current index of the row with targetRowId
                var rowIndex = -1
                for (i in 0 until rowsAdapter.size()) {
                    val row = rowsAdapter.get(i) as? ListRow
                    if (row?.headerItem?.id == targetRowId) {
                        rowIndex = i
                        break
                    }
                }

                if (rowIndex != -1) {
                    setSelectedPosition(rowIndex, true, object : ListRowPresenter.SelectItemViewHolderTask(selectedColumn) {
                        override fun run(holder: Presenter.ViewHolder?) {
                            super.run(holder)
                        }
                    })
                }
            }
        }
    }

    fun refreshWithCurrentFocus() {
        refreshRows(lastSelectedRowId, lastSelectedColumn)
    }

    private fun loadRows() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter { item ->
            if (item.mediaId.startsWith("smb://")) {
                handleSmbSelection(item.mediaId)
            } else if (item.mediaId.startsWith("content://") || item.mediaId.startsWith("file://")) {
                showFolderPlaylistOptions(item.mediaId, item.mediaMetadata.title.toString())
            }
        }

        // Section: Now Playing Controls
        controlsAdapter = ArrayObjectAdapter(cardPresenter)
        updateControlsRow()
        val controlsHeader = HeaderItem(0, "Now Playing")
        rowsAdapter.add(ListRow(controlsHeader, controlsAdapter))

        // Section: Actions
        val actionsAdapter = ArrayObjectAdapter(cardPresenter)
        actionsAdapter.add(createActionItem("Internal Storage", "Browse all internal files"))
        actionsAdapter.add(createActionItem("Music Folders", "Manage local and network folders"))
        actionsAdapter.add(createActionItem("External Drive", "Browse USB and SSD drives"))
        actionsAdapter.add(createActionItem("Add SMB Source", "Enter network share details"))
        val actionsHeader = HeaderItem(1, "Actions")
        rowsAdapter.add(ListRow(actionsHeader, actionsAdapter))

        // Section: Playlists
        playlistAdapter = ArrayObjectAdapter(cardPresenter)
        updatePlaylistRow()
        val playlistHeader = HeaderItem(6, "Playlists")
        rowsAdapter.add(ListRow(playlistHeader, playlistAdapter))

        // Section: Network Share
        val networkAdapter = ArrayObjectAdapter(cardPresenter)
        loadSavedSmbShares(networkAdapter)
        val networkHeader = HeaderItem(3, "Network Shares (SMB)")
        rowsAdapter.add(ListRow(networkHeader, networkAdapter))

        // Section: Local Music Library (Configured Folders)
        val localAdapter = ArrayObjectAdapter(cardPresenter)
        loadConfiguredLocalFolders(localAdapter)
        val localHeader = HeaderItem(2, "Local Music Library")
        rowsAdapter.add(ListRow(localHeader, localAdapter))

        // Section: Recent Files
        if (isRecentEnabled()) {
            val recentAdapter = ArrayObjectAdapter(cardPresenter)
            loadRecentFiles(recentAdapter)
            if (recentAdapter.size() > 0) {
                val recentHeader = HeaderItem(5, "Recently Played")
                rowsAdapter.add(ListRow(recentHeader, recentAdapter))
            }
        }

        // Section: Settings
        settingsAdapter = ArrayObjectAdapter(cardPresenter)
        settingsAdapter.add(createActionItem("Screensaver", "Delay: ${getScreensaverLabel()}"))
        settingsAdapter.add(createActionItem("Resume Playback", if (isResumeEnabled()) "Enabled" else "Disabled"))
        settingsAdapter.add(createActionItem("Recent Files", "Settings and cleanup"))
        settingsAdapter.add(createActionItem("Network Settings", "Buffer and streaming options"))
        settingsAdapter.add(createActionItem("Scan Library", "Options: ${if (isAutoScanEnabled()) "Auto" else "Manual"}"))
        settingsAdapter.add(createActionItem("Waveform Type", "Current: ${getWaveformTypeName()}"))
        settingsAdapter.add(createActionItem("Player Color Scheme", "Current: ${getColorSchemeName()}"))
        settingsAdapter.add(createActionItem("USB DAC", getUsbDacStatus()))
        settingsAdapter.add(createActionItem("About", "Version and build info"))
        val settingsHeader = HeaderItem(4, "Settings")
        rowsAdapter.add(ListRow(settingsHeader, settingsAdapter))

        // Section: Exit
        val exitAdapter = ArrayObjectAdapter(cardPresenter)
        exitAdapter.add(createActionItem("Exit", "Close application and stop playback"))
        val exitHeader = HeaderItem(7, "Exit")
        rowsAdapter.add(ListRow(exitHeader, exitAdapter))

        adapter = rowsAdapter
    }

    private fun loadSavedSmbShares(adapter: ArrayObjectAdapter) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sharesJson = prefs.getString(KEY_SHARES, "[]")
        try {
            val jsonArray = JSONArray(sharesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ip = obj.getString("ip")
                val share = obj.getString("share")
                val user = obj.optString("user", "")
                val pass = obj.optString("pass", "")
                
                // Encode share name and credentials for a valid SMB URL
                val encShare = Uri.encode(share)
                val uri = if (user.isNotEmpty()) {
                    val encUser = Uri.encode(user)
                    val encPass = Uri.encode(pass)
                    "smb://$encUser:$encPass@$ip/$encShare/"
                } else {
                    "smb://$ip/$encShare/"
                }
                adapter.add(createMediaItem("$share on $ip", "Network Share", uri))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSmbShare(ip: String, share: String, user: String, pass: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sharesJson = prefs.getString(KEY_SHARES, "[]")
        try {
            val jsonArray = JSONArray(sharesJson)
            val newObj = JSONObject().apply {
                put("ip", ip)
                put("share", share)
                put("user", user)
                put("pass", pass)
            }
            jsonArray.put(newObj)
            prefs.edit { putString(KEY_SHARES, jsonArray.toString()) }
            refreshWithCurrentFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showAddSmbDialog() {
        val activity = activity ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_smb, null)
        val editIp = view.findViewById<EditText>(R.id.edit_ip)
        val editShare = view.findViewById<EditText>(R.id.edit_share_name)
        val editUser = view.findViewById<EditText>(R.id.edit_username)
        val editPass = view.findViewById<EditText>(R.id.edit_password)

        setupTvEditText(editIp)
        setupTvEditText(editShare)
        setupTvEditText(editUser)
        setupTvEditText(editPass)

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add SMB Share")
            .setIcon(R.drawable.ic_network)
            .setView(view)
            .create()

        // Create a horizontal layout for buttons
        val buttonRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }

        fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(activity).apply {
            this.text = text
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            isFocusable = true
        }

        buttonRow.addView(createBtn("Scan Shares") {
            val ip = editIp.text.toString().trim()
            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()
            if (ip.isNotEmpty()) {
                scanSharesOnIp(ip, user, pass)
            } else {
                scanNetworkForSmb(user, pass)
            }
        })
        buttonRow.addView(createBtn("Add") {
            val ip = editIp.text.toString().trim()
            val share = editShare.text.toString().trim().removePrefix("/").removeSuffix("/")
            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()
            if (ip.isNotEmpty() && share.isNotEmpty()) {
                saveSmbShare(ip, share, user, pass)
            } else {
                Toast.makeText(activity, "IP and Share Name are required", Toast.LENGTH_SHORT).show()
            }
        })

        // Add a helper hint
        val hintView = android.widget.TextView(activity).apply {
            text = "Click 'Scan Shares' after entering IP to see all folders."
            textSize = 12f
            setPadding(16, 0, 16, 0)
            gravity = android.view.Gravity.CENTER
        }
        (view as? android.view.ViewGroup)?.addView(hintView)
        (view as? android.view.ViewGroup)?.addView(buttonRow)
        dialog.show()
    }

    private fun scanSharesOnIp(ip: String, user: String, pass: String) {
        val activity = activity ?: return
        val loadingToast = Toast.makeText(activity, "Scanning shares on $ip...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val cleanIp = ip.removeSuffix("/")
                val uri = "smb://$cleanIp/"
                
                // Use CIFSContext with credentials instead of embedding them in the URL
                val context = if (user.isNotEmpty()) {
                    SmbContext.getWithCredentials(user = user, pass = pass)
                } else {
                    SmbContext.get()
                }

                val server = SmbFile(uri, context)
                val shares = try {
                    server.listFiles() ?: emptyArray()
                } catch (e: Exception) {
                    if (user.isNotEmpty()) {
                         // Fallback to anonymous if credentials failed
                         SmbFile(uri, SmbContext.get()).listFiles() ?: emptyArray()
                    } else throw e
                }
                
                val shareNames = shares
                    .map { it.name.removeSuffix("/") }
                    .filter { !it.endsWith("$") }
                    .toTypedArray()

                val shareItems = shareNames.map { name ->
                    BrowseItem(name, "", R.drawable.ic_network, true)
                }

                activity.runOnUiThread {
                    loadingToast.cancel()
                    if (shareNames.isEmpty()) {
                        Toast.makeText(activity, "No public shares found on $ip.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    showBrowserDialog("Select Share on $ip", BrowseAdapter(activity, shareItems), { which ->
                        saveSmbShare(ip, shareNames[which], user, pass)
                    }, onBack = { showAddSmbDialog() })
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    loadingToast.cancel()
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Scan Failed")
                        .setMessage("Could not connect to $ip.\nDetails: ${e.localizedMessage ?: "Unknown error"}\n\nNote: Many modern PCs require a Username and Password to list shares.")
                        .setPositiveButton("Enter Credentials") { _, _ ->
                            showCredentialsDialog(ip) 
                        }
                        .setNegativeButton("Back", { _, _ -> showAddSmbDialog() })
                        .show()
                }
            }
        }.start()
    }

    private fun loadConfiguredLocalFolders(adapter: ArrayObjectAdapter) {
        val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = settings.getString(KEY_MUSIC_FOLDERS, "[]")
        try {
            val jsonArray = JSONArray(foldersJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawName = obj.getString("name")
                val cleanName = if (rawName.contains(":")) rawName.substringAfterLast(":") else rawName
                adapter.add(createMediaItem(cleanName, "Local Folder", obj.getString("uri")))
            }
        } catch (e: Exception) {}
    }

    private fun updateControlsRow() {
        controlsAdapter.clear()
        val activity = activity as? MainActivity
        val controller = activity?.getController()
        
        val currentItem = controller?.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Nothing Playing"
        val subtitle = currentItem?.mediaMetadata?.artist?.toString() ?: ""
        val isPlaying = controller?.isPlaying == true

        val nowPlayingId = if (isPlaying) "action:NOW_PLAYING:$title" else "action:NOW_PAUSED:$title"
        controlsAdapter.add(
            MediaItem.Builder()
                .setMediaId(nowPlayingId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("NOW: $title")
                        .setSubtitle(subtitle)
                        .build()
                )
                .build()
        )

        // Add Resume Last Played if enabled and not currently playing anything else
        if (isResumeEnabled() && (controller == null || controller.mediaItemCount == 0)) {
            val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val lastUri = settings.getString("last_played_uri", null)
            if (lastUri != null) {
                val lastTitle = settings.getString("last_played_title", "Last Played") ?: "Last Played"
                controlsAdapter.add(createActionItem("Resume Last Played", lastTitle))
            }
        }
    }

    private fun updatePlaylistRow() {
        if (!::playlistAdapter.isInitialized) return
        playlistAdapter.clear()
        
        val context = context ?: return
        
        // Scan Local Folders for Playlists
        val settings = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = settings.getString(KEY_MUSIC_FOLDERS, "[]")
        try {
            val jsonArray = JSONArray(foldersJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val uriStr = obj.getString("uri")
                if (uriStr.startsWith("content://")) {
                    findPlaylistsLocal(uriStr)
                } else if (uriStr.startsWith("file://")) {
                    val path = uriStr.toUri().path
                    if (path != null) findPlaylistsFile(path)
                }
            }
        } catch (e: Exception) {}
    }

    private fun findPlaylistsFile(path: String) {
        Thread {
            try {
                val root = java.io.File(path)
                fun scanRecursive(file: java.io.File) {
                    if (file.isDirectory) {
                        file.listFiles()?.forEach { scanRecursive(it) }
                    } else if (file.name.lowercase().endsWith(".m3u") || file.name.lowercase().endsWith(".m3u8") || file.name.lowercase().endsWith(".pls") || file.name.lowercase().endsWith(".cue")) {
                        activity?.runOnUiThread {
                            playlistAdapter.add(createMediaItem(file.name, "Local Playlist", Uri.fromFile(file).toString()))
                        }
                    }
                }
                scanRecursive(root)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun findPlaylistsLocal(uriString: String) {
        val context = context ?: return
        val rootUri = uriString.toUri()
        Thread {
            try {
                fun scanRecursive(uri: Uri) {
                    val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                    val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority!!, treeId)
                    val docId = try { android.provider.DocumentsContract.getDocumentId(uri) } catch (e: Exception) { treeId }
                    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                    val projection = arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameCol)
                            val mime = cursor.getString(mimeCol)
                            val childId = cursor.getString(idCol)
                            val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            
                            if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                                scanRecursive(childUri)
                            } else if (name.lowercase().endsWith(".m3u") || name.lowercase().endsWith(".m3u8") || name.lowercase().endsWith(".pls") || name.lowercase().endsWith(".cue")) {
                                activity?.runOnUiThread {
                                    playlistAdapter.add(createMediaItem(name, "Local Playlist", childUri.toString()))
                                }
                            }
                        }
                    }
                }
                scanRecursive(rootUri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun findPlaylistsSmb(uri: String) {
        Thread {
            try {
                fun scanRecursive(smbUri: String) {
                    val dir = SmbFile(smbUri, SmbContext.getContextForUri(smbUri))
                    val files = try { dir.listFiles() } catch (e: Exception) { null } ?: emptyArray()
                    for (f in files) {
                        if (f.isDirectory) {
                            scanRecursive(f.path)
                        } else if (f.name.lowercase().endsWith(".m3u") || f.name.lowercase().endsWith(".m3u8") || f.name.lowercase().endsWith(".pls") || f.name.lowercase().endsWith(".cue")) {
                            val name = f.name
                            val path = f.path
                            activity?.runOnUiThread {
                                playlistAdapter.add(createMediaItem(name, "SMB Playlist", path))
                            }
                        }
                    }
                }
                scanRecursive(uri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun observePlaybackState() {
        val activity = activity as? MainActivity
        val checkController = object : Runnable {
            override fun run() {
                val controller = activity?.getController()
                if (controller != null) {
                    controller.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                            updateControlsRow()
                            updatePlaylistRow()
                        }
                    })
                    updateControlsRow()
                    updatePlaylistRow()

                    // Auto-resume if enabled and nothing is playing
                    if (isResumeEnabled() && controller.mediaItemCount == 0 && !hasAttemptedResume) {
                        hasAttemptedResume = true
                        resumeLastPlayed()
                    }
                } else {
                    view?.postDelayed(this, 500)
                }
            }
        }
        view?.post(checkController)
    }

    private fun createActionItem(title: String, description: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("action:$title")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(description)
                    .build()
            )
            .build()
    }

    private fun createMediaItem(
        title: String,
        artist: String,
        uri: String,
        mediaId: String? = null,
        startMs: Long = 0,
        endMs: Long = androidx.media3.common.C.TIME_UNSET
    ): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
        
        if (artist.isNotEmpty()) {
            metadataBuilder.setArtist(artist)
        }

        val builder = MediaItem.Builder()
            .setMediaId(mediaId ?: uri)
            .setUri(uri.toUri())
            .setMimeType(getMimeType(uri))
            .setMediaMetadata(metadataBuilder.build())

        if (startMs > 0 || endMs != androidx.media3.common.C.TIME_UNSET) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
            if (endMs != androidx.media3.common.C.TIME_UNSET) {
                clip.setEndPositionMs(endMs)
            }
            builder.setClippingConfiguration(clip.build())
        }

        return builder.build()
    }

    private fun getMimeType(uri: String): String? {
        val lower = uri.lowercase()
        return when {
            lower.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
            lower.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            lower.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
            lower.endsWith(".ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
            lower.endsWith(".ape") -> "audio/x-ape"
            else -> null
        }
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            if (item is MediaItem) {
                if (item.mediaId.startsWith("action:")) {
                    handleAction(item.mediaId)
                } else if (row is ListRow && row.headerItem.id == 6L) { // Playlists
                    if (item.mediaId.startsWith("smb://")) {
                        val file = SmbFile(item.mediaId, SmbContext.get())
                        addSmbToPlaylist(file, replace = true)
                    } else if (item.mediaId.startsWith("content://")) {
                        addLocalToPlaylist(item.mediaId, item.mediaMetadata.title.toString(), replace = true)
                    } else if (item.mediaId.startsWith("file://")) {
                        val file = java.io.File(item.mediaId.toUri().path ?: "")
                        addFilesToPlaylist(file, replace = true)
                    }
                } else if (item.mediaId.startsWith("smb://")) {
              if (item.mediaId.endsWith("/")) {
                        browseSmbDirectory(item.mediaId)
                    } else {
                        // It's an SMB file, play it or show options
                        handleSmbSelection(item.mediaId)
                    }
                } else if (item.mediaId.startsWith("content://") || item.mediaId.startsWith("file://")) {
                    if (item.mediaId.startsWith("file://")) {
                        browseFileStorage(item.mediaId.toUri().path ?: "")
                    } else {
                        browseLocalDirectory(item.mediaId)
                    }
                } else if (row is ListRow) {
                    val activity = activity as? MainActivity
                    activity?.getController()?.let { controller ->
                        val adapter = row.adapter as ArrayObjectAdapter
                        val mediaItems = mutableListOf<MediaItem>()
                        var startIndex = 0
                        for (i in 0 until adapter.size()) {
                            val mediaItem = adapter.get(i) as MediaItem
                            if (!mediaItem.mediaId.startsWith("action:")) {
                                mediaItems.add(mediaItem)
                                if (mediaItem == item) {
                                    startIndex = mediaItems.size - 1
                                }
                            }
                        }
                        if (mediaItems.isNotEmpty()) {
                            controller.setMediaItems(mediaItems, startIndex, 0)
                            controller.prepare()
                            controller.play()
                            
                            val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                            startActivity(intent)
                        }
                    }
                }
            }
        }
    }

    private fun browseSmbDirectory(uri: String) {
        val context = activity ?: return
        if (context.isFinishing || context.isDestroyed) return
        
        val loadingToast = Toast.makeText(context, "Accessing network share...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val dir = SmbFile(uri, SmbContext.getContextForUri(uri))
                val files = try {
                    dir.listFiles() ?: emptyArray()
                } catch (e: Exception) {
                    // Surface the real error — "folder empty" was masking auth/connection failures
                    activity?.runOnUiThread {
                        loadingToast.cancel()
                        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("SMB Error")
                            .setMessage("Could not list directory:\n${e.localizedMessage ?: e.javaClass.simpleName}\n\nCheck credentials and that the share is accessible.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@Thread
                }

                // Sort: folders first, then files, case-insensitive
                val filteredFiles = files.filter { 
                    if (it.isDirectory()) {
                        !it.name.startsWith(".") && !it.name.contains("thumbnail", ignoreCase = true)
                    } else {
                        isPlayable(it.name)
                    }
                }
                val sortedFiles = filteredFiles.sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
                val browseItems = sortedFiles.map { 
                    val isDir = it.isDirectory()
                    val icon = if (isDir) R.drawable.ic_folder 
                              else if (it.name.lowercase().endsWith(".m3u") || it.name.lowercase().endsWith(".m3u8") || it.name.lowercase().endsWith(".pls") || it.name.lowercase().endsWith(".cue")) R.drawable.ic_playlist
                              else R.drawable.ic_audio
                    BrowseItem(it.name, it.path, icon, isDir)
                }

                activity?.runOnUiThread {
                    if (activity?.isFinishing == true) return@runOnUiThread
                    loadingToast.cancel()
                    
                    if (browseItems.isEmpty()) {
                        Toast.makeText(context, "This folder is empty or inaccessible.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    showBrowserDialog(dir.name.removeSuffix("/"), BrowseAdapter(context, browseItems), { which ->
                        val item = browseItems[which]
                        if (item.isDirectory) browseSmbDirectory(item.path) else handleSmbSelection(item.path)
                    }, { // Add All
                        addSmbToPlaylist(dir, replace = false)
                    }, { // Replace
                        addSmbToPlaylist(dir, replace = true)
                    }, { // Long click
                        which -> handleSmbSelection(browseItems[which].path)
                    }, { // onBack
                        val parent = dir.parent
                        if (parent != null) {
                            browseSmbDirectory(parent)
                        }
                    })
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    val activity = activity ?: return@runOnUiThread
                    loadingToast.cancel()
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("SMB Error")
                        .setMessage("Failed to browse: ${e.localizedMessage}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun browseLocalDirectory(uriString: String) {
        val context = activity ?: return
        val uri = uriString.toUri()
        val loadingToast = Toast.makeText(context, "Scanning folder...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority!!, treeId)
                val docId = try {
                    android.provider.DocumentsContract.getDocumentId(uri)
                } catch (e: Exception) {
                    treeId
                }
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                
                val projection = arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                
                val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                val items = mutableListOf<BrowseItem>()

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                    
                    while (it.moveToNext()) {
                        val id = it.getString(idCol)
                        val name = it.getString(nameCol)
                        val mime = it.getString(mimeCol)
                        val isDir = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                        
                        if (isDir) {
                            // Filter thumbnails and hidden folders
                            if (name.startsWith(".") || name.contains("thumbnail", ignoreCase = true)) continue
                            
                            // Only add if it contains music/playlists/cues or subfolders
                            if (hasPlayableContent(context, treeUri, id)) {
                                items.add(BrowseItem(name, android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), R.drawable.ic_folder, true))
                            }
                        } else if (isPlayable(name)) {
                            val icon = if (name.lowercase().endsWith(".m3u") || name.lowercase().endsWith(".m3u8") || name.lowercase().endsWith(".pls") || name.lowercase().endsWith(".cue")) R.drawable.ic_playlist else R.drawable.ic_audio
                            items.add(BrowseItem(name, android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), icon, false))
                        }
                    }
                }

                // Sort: folders first
                val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                activity?.runOnUiThread {
                    loadingToast.cancel()
                    if (sortedItems.isEmpty()) {
                        Toast.makeText(context, "No playable files found.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    showBrowserDialog("Browse Folder", BrowseAdapter(context, sortedItems), { which ->
                        val item = sortedItems[which]
                        if (item.isDirectory) browseLocalDirectory(item.path) else handleLocalSelection(item.path, item.name)
                    }, { // Add All
                        addLocalToPlaylist(uriString, "Current Folder", replace = false)
                    }, { // Replace
                        addLocalToPlaylist(uriString, "Current Folder", replace = true)
                    }, { // Long click
                        which -> handleLocalSelection(sortedItems[which].path, sortedItems[which].name)
                    }, { // onBack
                        // For local SAF, we don't easily have the parent URI here, but we can dismiss
                    })
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    loadingToast.cancel()
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showBrowserDialog(title: String, adapter: android.widget.ListAdapter, onItemClick: (Int) -> Unit, onAddAll: (() -> Unit)? = null, onReplace: (() -> Unit)? = null, onLongClick: ((Int) -> Unit)? = null, onBack: (() -> Unit)? = null) {
        val activity = activity ?: return
        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)

        val dialogView = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val listView = android.widget.ListView(activity).apply {
            this.adapter = adapter
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
        }
        dialogView.addView(listView)

        val buttonRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dialog = builder.setView(dialogView).create()

        fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(activity).apply {
            this.text = text
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            isFocusable = true
        }

        if (onBack != null) {
            buttonRow.addView(createBtn("Back") { 
                dialog.dismiss() 
                onBack.invoke()
            })
        }
        if (onAddAll != null) buttonRow.addView(createBtn("Add All") { onAddAll() })
        if (onReplace != null) buttonRow.addView(createBtn("Replace All") { onReplace() })

        if (buttonRow.childCount > 0) {
            dialogView.addView(buttonRow)
        }

        listView.setOnItemClickListener { _, _, which, _ ->
            onItemClick(which)
            dialog.dismiss()
        }

        if (onLongClick != null) {
            listView.setOnItemLongClickListener { _, _, which, _ ->
                onLongClick(which)
                dialog.dismiss()
                true
            }
        }

        dialog.show()
    }

    private fun handleLocalSelection(uriString: String, name: String) {
        val context = activity ?: return
        val items = listOf(
            DialogOptionItem("Add to current playlist", R.drawable.ic_queue_music),
            DialogOptionItem("Replace current playlist", R.drawable.ic_repeat)
        )
        val adapter = DialogOptionAdapter(context, items)
        
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setAdapter(adapter) { _, which ->
                if (uriString.startsWith("file://")) {
                    val file = java.io.File(uriString.toUri().path ?: "")
                    addFilesToPlaylist(file, which == 1)
                } else {
                    addLocalToPlaylist(uriString, name, which == 1)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addLocalToPlaylist(uriString: String, name: String, replace: Boolean) {
        val context = activity ?: return
        val mainActivity = activity as? MainActivity
        val controller = mainActivity?.getController() ?: return
        val rootUri = uriString.toUri()

        Thread {
            val itemsToAdd = mutableListOf<MediaItem>()
            
            fun scanRecursive(uri: Uri, isDir: Boolean, displayName: String) {
                if (isDir) {
                    try {
                        val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                        val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority!!, treeId)
                        val docId = try {
                            android.provider.DocumentsContract.getDocumentId(uri)
                        } catch (e: Exception) {
                            treeId
                        }
                        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                        val projection = arrayOf(
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                        )
                        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                            while (cursor.moveToNext()) {
                                val childId = cursor.getString(idCol)
                                val childName = cursor.getString(nameCol)
                                val childMime = cursor.getString(mimeCol)
                                val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                scanRecursive(childUri, childMime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR, childName)
                            }
                        }
                    } catch (e: Exception) {
                        // Fallback if buildChildDocumentsUriUsingTree fails (might not be a tree URI)
                        if (isPlayable(displayName)) {
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: displayName, meta.artist ?: "", uri.toString()))
                        }
                    }
                } else if (isPlayable(displayName)) {
                    val lower = displayName.lowercase()
                    if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
                        val basePath = uri.toString().substringBeforeLast("%2F")
                        val parsed = mainActivity.parseM3u(uri, basePath)
                        if (parsed.isNotEmpty()) {
                            itemsToAdd.addAll(parsed)
                        } else {
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: displayName, meta.artist ?: "", uri.toString()))
                        }
                    } else if (lower.endsWith(".cue")) {
                        val basePath = uri.toString().substringBeforeLast("%2F")
                        val parsed = mainActivity.parseCue(uri) // parseCue doesn't take basePath in MainFragment currently, let's check
                        if (parsed.isNotEmpty()) {
                            itemsToAdd.addAll(parsed)
                        } else {
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: displayName, meta.artist ?: "", uri.toString()))
                        }
                    } else if (lower.endsWith(".pls")) {
                        val basePath = uri.toString().substringBeforeLast("%2F")
                        val parsed = mainActivity.parsePls(uri, basePath)
                        if (parsed.isNotEmpty()) {
                            itemsToAdd.addAll(parsed)
                        } else {
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: displayName, meta.artist ?: "", uri.toString()))
                        }
                    } else {
                        val meta = MetadataUtils.getMetadata(context, uri)
                        itemsToAdd.add(createMediaItem(meta.title ?: displayName, meta.artist ?: "", uri.toString()))
                    }
                }
            }

            // Check if it's a directory first
            val isInitialDir = try {
                android.provider.DocumentsContract.isTreeUri(rootUri) ||
                context.contentResolver.getType(rootUri) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
            } catch (e: Exception) { false }
            
            val lowerName = name.lowercase()
            if (lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8")) {
                val basePath = rootUri.toString().substringBeforeLast("%2F")
                val parsed = mainActivity.parseM3u(rootUri, basePath)
                if (parsed.isNotEmpty()) {
                    itemsToAdd.addAll(parsed)
                } else {
                    val meta = MetadataUtils.getMetadata(context, rootUri)
                    itemsToAdd.add(createMediaItem(meta.title ?: name, meta.artist ?: "", rootUri.toString()))
                }
            } else if (lowerName.endsWith(".cue")) {
                val parsed = mainActivity.parseCue(rootUri)
                if (parsed.isNotEmpty()) {
                    itemsToAdd.addAll(parsed)
                } else {
                    val meta = MetadataUtils.getMetadata(context, rootUri)
                    itemsToAdd.add(createMediaItem(meta.title ?: name, meta.artist ?: "", rootUri.toString()))
                }
            } else if (lowerName.endsWith(".pls")) {
                val basePath = rootUri.toString().substringBeforeLast("%2F")
                val parsed = mainActivity.parsePls(rootUri, basePath)
                if (parsed.isNotEmpty()) {
                    itemsToAdd.addAll(parsed)
                } else {
                    itemsToAdd.add(createMediaItem(name, "", rootUri.toString()))
                }
            } else if (isInitialDir) {
                scanRecursive(rootUri, true, name)
            } else {
                itemsToAdd.add(createMediaItem(name, "", uriString))
            }

            activity?.runOnUiThread {
                if (itemsToAdd.isNotEmpty()) {
                    val sortedItems = if (name.lowercase().endsWith(".m3u") || name.lowercase().endsWith(".m3u8") || name.lowercase().endsWith(".pls")) {
                        itemsToAdd
                    } else {
                        // Sort items by track number extracted from title, then by title
                        itemsToAdd.sortedWith(compareBy({ extractTrackNumber(it.mediaMetadata.title.toString()) }, { it.mediaMetadata.title.toString().lowercase() }))
                    }
                    
                    if (replace) {
                        controller.setMediaItems(sortedItems)
                        controller.prepare()
                        controller.play()
                        val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                        startActivity(intent)
                    } else {
                        controller.addMediaItems(sortedItems)
                        Toast.makeText(context, "Added ${sortedItems.size} items to playlist", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No playable music files found.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleSmbSelection(uri: String) {
        val context = activity ?: return
        if (context.isFinishing) return

        val loadingToast = Toast.makeText(context, "Checking file info...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val file = SmbFile(uri, SmbContext.getContextForUri(uri))
                val fileName = file.name.removeSuffix("/")

                activity?.runOnUiThread {
                    loadingToast.cancel()
                    if (activity?.isFinishing == true) return@runOnUiThread

                    val items = listOf(
                        DialogOptionItem("Add to current playlist", R.drawable.ic_queue_music),
                        DialogOptionItem("Replace current playlist", R.drawable.ic_repeat)
                    )
                    val adapter = DialogOptionAdapter(context, items)

                    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(fileName)
                        .setAdapter(adapter) { _, which ->
                            addSmbToPlaylist(file, replace = (which == 1))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    loadingToast.cancel()
                    Toast.makeText(context, "Error accessing file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun addSmbToPlaylist(file: SmbFile, replace: Boolean) {
        val context = activity ?: return
        val loadingToast = Toast.makeText(context, "Processing...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val mainActivity = activity as? MainActivity
                val itemsToAdd = mutableListOf<MediaItem>()
                val isPlaylistFile = !file.isDirectory() && isPlayable(file.name) &&
                    (file.name.lowercase().endsWith(".m3u") || file.name.lowercase().endsWith(".m3u8") || file.name.lowercase().endsWith(".pls") || file.name.lowercase().endsWith(".cue"))
                
                fun scanRecursive(f: SmbFile) {
                    if (f.isDirectory()) {
                        f.listFiles()?.forEach { scanRecursive(it) }
                    } else if (isPlayable(f.name)) {
                        val lower = f.name.lowercase()
                        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
                            f.getInputStream().use { 
                                val parsed = mainActivity?.parseM3uFromStream(it, f.parent) ?: emptyList()
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    itemsToAdd.add(createMediaItem(f.name, "", f.path))
                                }
                            }
                        } else if (lower.endsWith(".cue")) {
                            f.getInputStream().use {
                                val parsed = mainActivity?.parseCueFromStream(it, f.parent) ?: emptyList()
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    itemsToAdd.add(createMediaItem(f.name, "", f.path))
                                }
                            }
                        } else if (lower.endsWith(".pls")) {
                            f.getInputStream().use { 
                                val parsed = mainActivity?.parsePlsFromStream(it, f.parent) ?: emptyList()
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    itemsToAdd.add(createMediaItem(f.name, "", f.path))
                                }
                            }
                        } else {
                            itemsToAdd.add(createMediaItem(f.name, "", f.path))
                        }
                    }
                }

                // Ensure we use a context with credentials if needed for recursion
                val credentialContext = SmbContext.getContextForUri(file.path)
                val rootFile = SmbFile(file.path, credentialContext)
                scanRecursive(rootFile)

                activity?.runOnUiThread {
                    if (activity?.isFinishing == true) return@runOnUiThread
                    loadingToast.cancel()
                    if (itemsToAdd.isNotEmpty()) {
                        val sortedItems = if (isPlaylistFile) {
                            itemsToAdd
                        } else {
                            // Sort items by track number extracted from title, then by title
                            itemsToAdd.sortedWith(compareBy({ extractTrackNumber(it.mediaMetadata.title.toString()) }, { it.mediaMetadata.title.toString().lowercase() }))
                        }
                        
                        val controller = mainActivity?.getController()
                        if (controller != null) {
                            if (replace) {
                                controller.setMediaItems(sortedItems)
                                controller.prepare()
                                controller.play()
                                val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                                startActivity(intent)
                            } else {
                                controller.addMediaItems(sortedItems)
                                Toast.makeText(context, "Added ${sortedItems.size} items to playlist", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "No playable music files found.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    if (activity?.isFinishing == true) return@runOnUiThread
                    loadingToast.cancel()
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun checkAndBrowseInternalStorage(isSelectionMode: Boolean = false) {
        val context = requireContext()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode)
            } else {
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("All Files Access Required")
                    .setMessage("To browse storage directly on Android TV 11+, please grant 'All Files Access' in the system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = "package:${context.packageName}".toUri()
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode)
        }
    }

    private data class ExternalDrive(val name: String, val path: String)

    private fun detectExternalDrives(): List<ExternalDrive> {
        val context = context ?: return emptyList()
        val drives = linkedMapOf<String, ExternalDrive>()
        val primaryRoot = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

        fun tryAdd(rawPath: String?, label: String?) {
            if (rawPath.isNullOrBlank()) return
            val path = rawPath.trimEnd('/')
            if (path.isEmpty() || path == primaryRoot || drives.containsKey(path)) return
            val file = java.io.File(path)
            if (!file.exists() || !file.isDirectory) return
            // canRead() is unreliable on /mnt/media_rw and some Android TV mounts; listing is the real test
            val readable = try { file.list() != null } catch (e: Exception) { false }
            if (!readable) return
            val name = label?.takeIf { it.isNotBlank() } ?: file.name.ifEmpty { "External Drive" }
            drives[path] = ExternalDrive(name, path)
        }

        // 1) StorageManager.getStorageVolumes() — gives the best human-readable label
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
            sm?.storageVolumes?.forEach { volume ->
                if (volume.isPrimary) return@forEach
                val path: String? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    volume.directory?.absolutePath
                } else {
                    try { volume.javaClass.getMethod("getPath").invoke(volume) as? String } catch (e: Exception) { null }
                }
                tryAdd(path, volume.getDescription(context))
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 2) getExternalFilesDirs() — sandbox paths reveal volume roots
        try {
            context.getExternalFilesDirs(null)?.forEach { sandbox ->
                if (sandbox == null) return@forEach
                val idx = sandbox.absolutePath.indexOf("/Android/")
                if (idx > 0) tryAdd(sandbox.absolutePath.substring(0, idx), null)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 3) Scan /storage directly — Android TV USB drives often land here with no StorageVolume entry
        try {
            java.io.File("/storage").listFiles()?.forEach { f ->
                val n = f.name
                if (n == "emulated" || n == "self" || n == "enc_emulated" || n.startsWith("private")) return@forEach
                tryAdd(f.absolutePath, null)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 4) /proc/mounts as a final sweep — catches unusual mount points like /mnt/usb/<...>
        try {
            val removableFs = setOf("vfat", "exfat", "ntfs", "ntfs3", "ext4", "ext3", "ext2", "f2fs", "sdfat", "fuse", "fuseblk")
            val skipPrefixes = listOf("/mnt/runtime", "/mnt/installer", "/mnt/androidwritable", "/mnt/user",
                "/mnt/pass_through", "/mnt/appfuse", "/mnt/asec", "/mnt/obb", "/mnt/expand", "/mnt/secure")
            java.io.BufferedReader(java.io.FileReader("/proc/mounts")).use { reader ->
                reader.lineSequence().forEach { line ->
                    val parts = line.split(" ")
                    if (parts.size < 3) return@forEach
                    val mount = parts[1]
                    val fsType = parts[2]
                    if (fsType !in removableFs) return@forEach
                    if (!mount.startsWith("/storage/") && !mount.startsWith("/mnt/")) return@forEach
                    if (skipPrefixes.any { mount.startsWith(it) }) return@forEach
                    tryAdd(mount, null)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return drives.values.toList()
    }

    private fun showExternalDrivesDialog() {
        val context = activity ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()) {
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("All Files Access Required")
                .setMessage("To browse USB and external drives, please grant 'All Files Access' in the system settings.")
                .setPositiveButton("Settings") { _, _ ->
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = "package:${context.packageName}".toUri()
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val drives = detectExternalDrives()
        if (drives.isEmpty()) {
            val storageContents = try {
                java.io.File("/storage").listFiles()?.joinToString(", ") { it.name } ?: "<unreadable>"
            } catch (e: Exception) { "<error: ${e.message}>" }
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("No External Drives")
                .setMessage("No USB or SSD drives were detected. Connect a drive and try again.\n\nVisible in /storage: $storageContents")
                .setPositiveButton("Retry") { _, _ -> showExternalDrivesDialog() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (drives.size == 1) {
            browseFileStorage(drives[0].path)
            return
        }

        val items = drives.map { BrowseItem(it.name, it.path, R.drawable.ic_usb, true) }
        val adapter = BrowseAdapter(context, items)
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select External Drive")
            .setAdapter(adapter) { _, which -> browseFileStorage(drives[which].path) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun browseFileStorage(path: String, isSelectionMode: Boolean = false) {
        val context = activity ?: return
        val currentDir = java.io.File(path)
        val loadingToast = Toast.makeText(context, "Reading folder...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val files = currentDir.listFiles() ?: emptyArray()
                val items = mutableListOf<BrowseItem>()

                for (file in files) {
                    if (file.name.startsWith(".")) continue
                    
                    if (file.isDirectory) {
                        items.add(BrowseItem(file.name, file.absolutePath, R.drawable.ic_folder, true))
                    } else if (!isSelectionMode && isPlayable(file.name)) {
                        val icon = if (file.name.lowercase().endsWith(".m3u") || file.name.lowercase().endsWith(".m3u8") || file.name.lowercase().endsWith(".pls") || file.name.lowercase().endsWith(".cue")) R.drawable.ic_playlist else R.drawable.ic_audio
                        items.add(BrowseItem(file.name, file.absolutePath, icon, false))
                    }
                }

                val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                activity?.runOnUiThread {
                    loadingToast.cancel()
                    
                    val folderName = currentDir.name.ifEmpty { "Storage" }
                    val title = if (isSelectionMode) "Add Folder: $folderName" else folderName
                    
                    val onBack: () -> Unit = {
                        val parent = currentDir.parentFile
                        if (parent != null && parent.absolutePath != "/storage" && parent.absolutePath != "/") {
                            browseFileStorage(parent.absolutePath, isSelectionMode)
                        }
                    }

                    if (isSelectionMode) {
                        showFolderSelectionDialog(title, BrowseAdapter(context, sortedItems), { which ->
                            val item = sortedItems[which]
                            if (item.isDirectory) browseFileStorage(item.path, true)
                        }, { // Add Folder
                            saveMusicFolder(Uri.fromFile(currentDir).toString(), true)
                        }, onBack)
                    } else {
                        showBrowserDialog(title, BrowseAdapter(context, sortedItems), { which ->
                            val item = sortedItems[which]
                            if (item.isDirectory) browseFileStorage(item.path) else handleFileSelection(item.path, item.name)
                        }, { // Add All
                            addFilesToPlaylist(currentDir, replace = false)
                        }, { // Replace
                            addFilesToPlaylist(currentDir, replace = true)
                        }, { // Long click
                            which -> handleFileSelection(sortedItems[which].path, sortedItems[which].name)
                        }, onBack)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    loadingToast.cancel()
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showFolderSelectionDialog(title: String, adapter: android.widget.ListAdapter, onItemClick: (Int) -> Unit, onAddFolder: () -> Unit, onBack: (() -> Unit)? = null) {
        val activity = activity ?: return
        val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)

        val dialogView = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val listView = android.widget.ListView(activity).apply {
            this.adapter = adapter
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
        }
        dialogView.addView(listView)

        val buttonRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dialog = builder.setView(dialogView).create()

        fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(activity).apply {
            this.text = text
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            isFocusable = true
        }

        buttonRow.addView(createBtn("Back") { 
            dialog.dismiss()
            onBack?.invoke()
        })
        buttonRow.addView(createBtn("Add Folder") { onAddFolder() })

        dialogView.addView(buttonRow)

        listView.setOnItemClickListener { _, _, which, _ ->
            onItemClick(which)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun handleFileSelection(path: String, name: String) {
        val context = activity ?: return
        val file = java.io.File(path)
        val isDir = file.isDirectory
        
        val items = if (isDir) {
            listOf(
                DialogOptionItem("Add to current playlist", R.drawable.ic_queue_music),
                DialogOptionItem("Replace current playlist", R.drawable.ic_repeat),
                DialogOptionItem("Add to Music Library", R.drawable.ic_folder_music)
            )
        } else {
            listOf(
                DialogOptionItem("Add to current playlist", R.drawable.ic_queue_music),
                DialogOptionItem("Replace current playlist", R.drawable.ic_repeat)
            )
        }
        val adapter = DialogOptionAdapter(context, items)
        
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> addFilesToPlaylist(file, false)
                    1 -> addFilesToPlaylist(file, true)
                    2 -> if (isDir) saveMusicFolder(Uri.fromFile(file).toString(), true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderPlaylistOptions(uriString: String, name: String) {
        val context = activity ?: return
        val items = listOf(
            DialogOptionItem("Add To Current Playlist", R.drawable.ic_queue_music),
            DialogOptionItem("Replace Current Playlist", R.drawable.ic_repeat)
        )
        val adapter = DialogOptionAdapter(context, items)

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setAdapter(adapter) { _, which ->
                if (uriString.startsWith("file://")) {
                    val file = java.io.File(uriString.toUri().path ?: "")
                    addFilesToPlaylist(file, which == 1)
                } else {
                    addLocalToPlaylist(uriString, name, which == 1)
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun addFilesToPlaylist(root: java.io.File, replace: Boolean) {
        val context = activity ?: return
        val mainActivity = activity as? MainActivity
        val controller = mainActivity?.getController() ?: return
        val loadingToast = Toast.makeText(context, "Processing files...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            val itemsToAdd = mutableListOf<MediaItem>()
            val isPlaylistFile = !root.isDirectory && isPlayable(root.name) &&
                (root.name.lowercase().endsWith(".m3u") || root.name.lowercase().endsWith(".m3u8") || root.name.lowercase().endsWith(".pls") || root.name.lowercase().endsWith(".cue"))
            
            fun scanRecursive(file: java.io.File) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { scanRecursive(it) }
                } else if (isPlayable(file.name)) {
                    val lower = file.name.lowercase()
                    if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
                        try {
                            java.io.FileInputStream(file).use { stream ->
                                val parsed = mainActivity?.parseM3uFromStream(stream, file.parent) ?: emptyList()
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    val uri = Uri.fromFile(file)
                                    val meta = MetadataUtils.getMetadata(context, uri)
                                    itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                                }
                            }
                        } catch (e: Exception) {
                            val uri = Uri.fromFile(file)
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                        }
                    } else if (lower.endsWith(".cue")) {
                        try {
                            java.io.FileInputStream(file).use { stream ->
                                val parsed = mainActivity?.parseCueFromStream(stream, file.parent) ?: emptyList()
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    val uri = Uri.fromFile(file)
                                    val meta = MetadataUtils.getMetadata(context, uri)
                                    itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                                }
                            }
                        } catch (e: Exception) {
                            val uri = Uri.fromFile(file)
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                        }
                    } else if (lower.endsWith(".pls")) {
                        try {
                            java.io.FileInputStream(file).use { stream ->
                                val parsed = mainActivity?.parsePlsFromStream(stream, file.parent) ?: emptyList()
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    val uri = Uri.fromFile(file)
                                    val meta = MetadataUtils.getMetadata(context, uri)
                                    itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                                }
                            }
                        } catch (e: Exception) {
                            val uri = Uri.fromFile(file)
                            val meta = MetadataUtils.getMetadata(context, uri)
                            itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                        }
                    } else {
                        val uri = Uri.fromFile(file)
                        val meta = MetadataUtils.getMetadata(context, uri)
                        itemsToAdd.add(createMediaItem(meta.title ?: file.name, meta.artist ?: "", uri.toString()))
                    }
                }
            }

            scanRecursive(root)

            activity?.runOnUiThread {
                loadingToast.cancel()
                if (itemsToAdd.isNotEmpty()) {
                    val sortedItems = if (isPlaylistFile) {
                        itemsToAdd
                    } else {
                        itemsToAdd.sortedWith(compareBy({ extractTrackNumber(it.mediaMetadata.title.toString()) }, { it.mediaMetadata.title.toString().lowercase() }))
                    }
                    
                    if (replace) {
                        controller.setMediaItems(sortedItems)
                        controller.prepare()
                        controller.play()
                        val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                        startActivity(intent)
                    } else {
                        controller.addMediaItems(sortedItems)
                        Toast.makeText(context, "Added ${sortedItems.size} items", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No music found.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun isPlayable(filename: String): Boolean {
        val extensions = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".m3u", ".m3u8", ".pls", ".cue", ".ape")
        return extensions.any { filename.lowercase().endsWith(it) }
    }

    private fun extractTrackNumber(title: String): Int {
        val cleanTitle = title.trim()
        val regex = Regex("^(\\d+)")
        val match = regex.find(cleanTitle)
        return match?.value?.toInt() ?: Int.MAX_VALUE
    }

    private fun hasPlayableContent(context: Context, treeUri: Uri, docId: String): Boolean {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && !name.contains("thumbnail", ignoreCase = true)) {
                            return true // Non-hidden subfolder might have content
                        }
                    } else if (isPlayable(name)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun getUsbDacStatus(): String {
        val dac = PlaybackService.findUsbAudioDevice(requireContext())
        return if (dac != null)
            "Connected: ${dac.productName?.takeIf { it.isNotBlank() } ?: dac.deviceName}"
        else
            "No DAC detected"
    }

    /** Replaces only the USB DAC settings card subtitle without touching the rest of the row. */
    private fun updateUsbDacCard() {
        if (!::settingsAdapter.isInitialized) return
        val newItem = createActionItem("USB DAC", getUsbDacStatus())
        settingsAdapter.replace(SETTINGS_USB_DAC_INDEX, newItem)
    }

    private fun showUsbDacDialog() {
        val context = requireContext()
        val svc = PlaybackService.instance
        // UsbManager check = live device presence (correct after power-off)
        val usbDac = PlaybackService.findUsbAudioDevice(context)
        // AudioManager check = audio capabilities (sample rates, bit depths, channel counts)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val audioDac = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE    ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }

        val msg = if (usbDac != null) {
            buildString {
                append("USB DAC connected\n\n")
                append("Product: ${usbDac.productName ?: usbDac.deviceName}\n")
                append("Vendor ID: 0x${usbDac.vendorId.toString(16).uppercase()}\n")
                if (audioDac != null) {
                    val rates = audioDac.sampleRates
                    if (rates.isNotEmpty()) append("Sample rates: ${rates.joinToString(", ")} Hz\n")
                    val encodings = audioDac.encodings
                    if (encodings.isNotEmpty()) {
                        val encNames = encodings.joinToString(", ") { enc ->
                            when (enc) {
                                android.media.AudioFormat.ENCODING_PCM_16BIT        -> "16-bit"
                                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
                                android.media.AudioFormat.ENCODING_PCM_32BIT        -> "32-bit"
                                android.media.AudioFormat.ENCODING_PCM_FLOAT        -> "Float"
                                android.media.AudioFormat.ENCODING_DTS              -> "DTS"
                                android.media.AudioFormat.ENCODING_AC3              -> "AC3"
                                else -> "enc:$enc"
                            }
                        }
                        append("Encodings: $encNames\n")
                    }
                    val ch = audioDac.channelCounts
                    if (ch.isNotEmpty()) {
                        // channelCounts includes upmix capabilities (4,6,8…).
                        // Native output = highest value that is ≤ 2 (mono=1, stereo=2).
                        // Fall back to ch.min() only if device reports no stereo-or-below count.
                        val native = ch.filter { it <= 2 }.maxOrNull() ?: ch.min()
                        val label = when (native) {
                            1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$native ch"
                        }
                        append("Channels: $label")
                        if (ch.max() > native) append(" (upmix up to ${ch.max()})")
                        append("\n")
                    }
                }
                append("\nIf the DAC outputs wrong audio after being power-cycled,\nuse 'Reset Audio Sink' to force re-negotiation.")
            }
        } else {
            "No USB DAC detected.\n\nConnect a USB DAC — it will be detected\nautomatically on startup or when plugged in.\n\nIf your DAC was just connected, try\n'Reset Audio Sink' to trigger negotiation."
        }

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("USB DAC")
            .setIcon(R.drawable.ic_dac)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Reset Audio Sink") { _, _ ->
                if (svc != null) {
                    Toast.makeText(context, "Resetting audio sink…", Toast.LENGTH_SHORT).show()
                    svc.checkAndResetUsbAudio(reason = "manual from settings")
                    refreshWithCurrentFocus()
                } else {
                    Toast.makeText(context, "Playback service not running", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun getScreensaverLabel(): String {
        val mins = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_SCREENSAVER, 0)
        return when {
            mins == 0 -> "Never"
            mins > 0 -> "Bouncing ($mins min)"
            mins < 0 -> "Screen Off (${Math.abs(mins)} min)"
            else -> "Never"
        }
    }

    private fun isResumeEnabled() = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(KEY_RESUME, false)

    private fun isRecentEnabled() = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(KEY_RECENT, true)

    private fun isAutoScanEnabled() = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_SCAN, false)

    private fun getWaveformTypeName(): String {
        val types = arrayOf("Mirrored Bars", "Single Bars", "Bars with level hold", "Dots bars", "Dots bars with level holds")
        val index = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_WAVEFORM_TYPE, 0)
        return if (index in types.indices) types[index] else types[0]
    }

    private fun showWaveformTypeDialog() {
        val typeNames = arrayOf("Mirrored Bars", "Single Bars", "Bars with level hold", "Dots bars", "Dots bars with level holds")
        val items = typeNames.map { DialogOptionItem(it, R.drawable.ic_waveform) }
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Waveform Type")
            .setAdapter(adapter) { _, which ->
                requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_WAVEFORM_TYPE, which).apply()
                refreshWithCurrentFocus()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun getColorSchemeName(): String {
        val schemes = arrayOf("Neon Green", "Electric Blue", "Amber Gold", "Deep Purple", "Hot Pink", "Pure Black with Grey", "Pure Black with Red", "Pure Black with Green", "Pure Black with White")
        val index = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_COLOR_SCHEME, 0)
        return if (index in schemes.indices) schemes[index] else schemes[0]
    }

    private fun getColorSchemeColor(): Int {
        val colors = intArrayOf(
            0xFF00E676.toInt(), // Neon Green
            0xFF2979FF.toInt(), // Electric Blue
            0xFFFFC400.toInt(), // Amber Gold
            0xFF7C4DFF.toInt(), // Deep Purple
            0xFFFF4081.toInt(), // Hot Pink
            0xFF888888.toInt(), // Grey
            0xFFFF5252.toInt(), // Red
            0xFF1B5E20.toInt(), // Dark Green (Material 900)
            0xFFFFFFFF.toInt()  // White
        )
        val index = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_COLOR_SCHEME, 0)
        return if (index in colors.indices) colors[index] else colors[0]
    }

    private fun showColorSchemeDialog() {
        val schemeNames = arrayOf("Neon Green", "Electric Blue", "Amber Gold", "Deep Purple", "Hot Pink", "Pure Black with Grey", "Pure Black with Red", "Pure Black with Green", "Pure Black with White")
        val items = schemeNames.map { DialogOptionItem(it, R.drawable.ic_palette) }
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Player Color Scheme")
            .setAdapter(adapter) { _, which ->
                requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_COLOR_SCHEME, which).apply()
                refreshWithCurrentFocus()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun loadRecentFiles(adapter: ArrayObjectAdapter) {
        val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val recentJson = settings.getString("recent_list", "[]")
        try {
            val recentArray = JSONArray(recentJson)
            for (i in 0 until recentArray.length()) {
                val obj = recentArray.getJSONObject(i)
                adapter.add(createMediaItem(obj.getString("title"), obj.getString("artist"), obj.getString("uri")))
            }
        } catch (e: Exception) {}
    }

    private fun showScreensaverSettings() {
        val items = listOf(
            DialogOptionItem("Screensaver On", R.drawable.ic_screensaver),
            DialogOptionItem("Turn The Screen Off", R.drawable.ic_screensaver),
            DialogOptionItem("Never", R.drawable.ic_screensaver)
        )
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Screensaver Settings")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> showScreensaverDelayDialog(true)
                    1 -> showScreensaverDelayDialog(false)
                    2 -> {
                        requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_SCREENSAVER, 0).apply()
                        refreshWithCurrentFocus()
                    }
                }
            }
            .show()
    }

    private fun showScreensaverDelayDialog(isBouncing: Boolean) {
        val delays = intArrayOf(1, 3, 15, 30)
        val options = delays.map { "$it Minutes" }.toTypedArray()

        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (isBouncing) "Screensaver Delay" else "Turn Off Delay")
            .setItems(options) { _, which ->
                val value = if (isBouncing) delays[which] else -delays[which]
                requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SCREENSAVER, value).apply()
                refreshWithCurrentFocus()
            }
            .setNegativeButton("Back") { _, _ -> showScreensaverSettings() }
            .show()
    }

    private fun toggleResumePlayback() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_RESUME, false)
        prefs.edit { putBoolean(KEY_RESUME, !current) }
        refreshWithCurrentFocus()
    }

    private fun toggleRecentFiles() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_RECENT, true)
        prefs.edit { putBoolean(KEY_RECENT, !current) }
        refreshWithCurrentFocus()
    }

    private data class BrowseItem(val name: String, val path: String, val icon: Int, val isDirectory: Boolean)

    private class BrowseAdapter(context: Context, items: List<BrowseItem>) : 
        android.widget.ArrayAdapter<BrowseItem>(context, R.layout.list_item_browse, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
            val item = getItem(position)!!
            view.findViewById<android.widget.TextView>(R.id.item_text).text = item.name
            val icon = view.findViewById<android.widget.ImageView>(R.id.item_icon)
            icon.setImageResource(item.icon)
            icon.drawable?.mutate()?.setTint(getThemeColor(context))
            return view
        }
    }

    data class DialogOptionItem(val label: String, val iconRes: Int)

    class DialogOptionAdapter(context: Context, items: List<DialogOptionItem>) :
        android.widget.ArrayAdapter<DialogOptionItem>(context, R.layout.list_item_browse, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
            val item = getItem(position)!!
            view.findViewById<android.widget.TextView>(R.id.item_text).text = item.label
            val icon = view.findViewById<android.widget.ImageView>(R.id.item_icon)
            icon.setImageResource(item.iconRes)
            icon.drawable?.mutate()?.setTint(getThemeColor(context))
            return view
        }
    }

    private fun manageMusicFolders() {
        val items = listOf(
            DialogOptionItem("Add Local Music Folder", R.drawable.ic_storage),
            DialogOptionItem("Add SMB Network Share", R.drawable.ic_network_music),
            DialogOptionItem("View/Remove Folders", R.drawable.ic_folder)
        )
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Music Folders")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> checkAndBrowseInternalStorage(isSelectionMode = true)
                    1 -> showAddSmbDialog()
                    2 -> viewConfiguredFolders()
                }
            }
            .show()
    }

    private fun pickLocalFolder() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            startActivityForResult(intent, 1001)
        } catch (e: Exception) {
            // Fallback for devices without DocumentUI/SAF picker
            AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Compatible File Manager Required")
                .setMessage("Your device does not have a standard folder picker. Please install a file manager like 'X-plore' or 'MiXplorer' that supports SAF (Storage Access Framework).")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                saveMusicFolder(uri.toString(), true)
            }
        }
    }

    private fun saveMusicFolder(uri: String, isLocal: Boolean) {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = prefs.getString(KEY_MUSIC_FOLDERS, "[]")
        try {
            val jsonArray = JSONArray(foldersJson)
            // Check if already exists
            for (i in 0 until jsonArray.length()) {
                if (jsonArray.getJSONObject(i).getString("uri") == uri) return
            }
            val newObj = JSONObject().apply {
                put("uri", uri)
                put("isLocal", isLocal)
                val rawName = uri.toUri().lastPathSegment ?: uri
                val cleanName = if (rawName.contains(":")) rawName.substringAfterLast(":") else rawName
                put("name", cleanName)
            }
            jsonArray.put(newObj)
            prefs.edit { putString(KEY_MUSIC_FOLDERS, jsonArray.toString()) }
            refreshWithCurrentFocus()
            Toast.makeText(requireContext(), "Folder added", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.getInetAddresses()
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {}
        return null
    }

    private fun setupTvEditText(editText: EditText) {
        editText.setOnEditorActionListener { v, _, _ ->
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        
        // Disable automatic keyboard popup on focus to allow D-pad navigation
        editText.onFocusChangeListener = null
        
        // Show keyboard only when OK/Center button is pressed
        editText.setOnClickListener { v ->
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun scanNetworkForSmb(user: String, pass: String) {
        val activity = activity ?: return
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Toast.makeText(activity, "Could not determine local IP", Toast.LENGTH_SHORT).show()
            return
        }
        val prefix = localIp.substring(0, localIp.lastIndexOf(".") + 1)
        
        val foundIps = mutableListOf<String>()
        val loadingToast = Toast.makeText(activity, "Scanning local network for SMB servers...", Toast.LENGTH_LONG)
        loadingToast.show()
        
        Thread {
            val timeout = 300 // ms
            val pool = java.util.concurrent.Executors.newFixedThreadPool(30)
            val futures = mutableListOf<java.util.concurrent.Future<*>>()
            
            for (i in 1..254) {
                val testIp = prefix + i
                futures.add(pool.submit {
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(testIp, 445), timeout)
                        socket.close()
                        synchronized(foundIps) { foundIps.add(testIp) }
                    } catch (e: Exception) {}
                })
            }
            
            for (f in futures) {
                try { f.get() } catch (e: Exception) {}
            }
            pool.shutdown()
            
            activity.runOnUiThread {
                loadingToast.cancel()
                if (foundIps.isEmpty()) {
                    Toast.makeText(activity, "No SMB servers found on network.", Toast.LENGTH_LONG).show()
                } else {
                    val serverItems = foundIps.map { BrowseItem(it, it, R.drawable.ic_network, true) }
                    showBrowserDialog("Select SMB Server", BrowseAdapter(activity, serverItems), { which ->
                        val selectedIp = foundIps[which]
                        showCredentialsDialog(selectedIp)
                    }, onBack = { showAddSmbDialog() })
                }
            }
        }.start()
    }

    private fun showCredentialsDialog(ip: String) {
        val activity = activity ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_smb, null)
        val editIp = view.findViewById<EditText>(R.id.edit_ip)
        val editShare = view.findViewById<EditText>(R.id.edit_share_name)
        val editUser = view.findViewById<EditText>(R.id.edit_username)
        val editPass = view.findViewById<EditText>(R.id.edit_password)
        
        editIp.setText(ip)
        editIp.isEnabled = false // Lock IP

        setupTvEditText(editShare)
        setupTvEditText(editUser)
        setupTvEditText(editPass)

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Credentials for $ip")
            .setIcon(R.drawable.ic_network)
            .setView(view)
            .create()

        // Create a horizontal layout for buttons
        val buttonRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }

        fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(activity).apply {
            this.text = text
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            isFocusable = true
        }

        buttonRow.addView(createBtn("Scan Shares") {
            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()
            scanSharesOnIp(ip, user, pass)
        })
        buttonRow.addView(createBtn("Add") {
            val share = editShare.text.toString().trim().removePrefix("/").removeSuffix("/")
            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()
            if (share.isNotEmpty()) {
                saveSmbShare(ip, share, user, pass)
            } else {
                Toast.makeText(activity, "Share Name is required", Toast.LENGTH_SHORT).show()
            }
        })

        (view as? android.view.ViewGroup)?.addView(buttonRow)
        dialog.show()
    }

    private fun viewConfiguredFolders() {
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = prefs.getString(KEY_MUSIC_FOLDERS, "[]")
        val smbJson = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SHARES, "[]")
        
        val browseItems = mutableListOf<BrowseItem>()
        val folderObjects = mutableListOf<JSONObject>()
        
        try {
            val localFolders = JSONArray(foldersJson)
            for (i in 0 until localFolders.length()) {
                val obj = localFolders.getJSONObject(i)
                folderObjects.add(obj)
                browseItems.add(BrowseItem("[Local] " + obj.getString("name"), obj.getString("uri"), R.drawable.ic_folder, true))
            }
            
            val smbFolders = JSONArray(smbJson)
            for (i in 0 until smbFolders.length()) {
                val obj = smbFolders.getJSONObject(i)
                val ip = obj.getString("ip")
                val share = obj.getString("share")
                val encShare = Uri.encode(share)
                val folderObj = JSONObject().apply {
                    put("uri", "smb://$ip/$encShare/")
                    put("isLocal", false)
                    put("isSmb", true)
                    put("index", i)
                }
                folderObjects.add(folderObj)
                browseItems.add(BrowseItem("[SMB] $share on $ip", folderObj.getString("uri"), R.drawable.ic_network, true))
            }
        } catch (e: Exception) {}

        if (browseItems.isEmpty()) {
            Toast.makeText(context, "No folders configured", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = BrowseAdapter(context, browseItems)
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Manage Folders (Select to Remove)")
            .setAdapter(adapter) { _, which ->
                val item = browseItems[which]
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove Folder")
                    .setMessage("Are you sure you want to remove '${item.name}'?")
                    .setPositiveButton("Remove") { _, _ ->
                        removeFolder(folderObjects[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun removeFolder(obj: JSONObject) {
        if (obj.optBoolean("isSmb")) {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_SHARES, "[]"))
            val index = obj.getInt("index")
            val newArray = JSONArray()
            for (i in 0 until jsonArray.length()) {
                if (i != index) newArray.put(jsonArray.get(i))
            }
            prefs.edit { putString(KEY_SHARES, newArray.toString()) }
        } else {
            val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_MUSIC_FOLDERS, "[]"))
            val newArray = JSONArray()
            val uriToRemove = obj.getString("uri")
            for (i in 0 until jsonArray.length()) {
                if (jsonArray.getJSONObject(i).getString("uri") != uriToRemove) {
                    newArray.put(jsonArray.get(i))
                }
            }
            prefs.edit { putString(KEY_MUSIC_FOLDERS, newArray.toString()) }
        }
        refreshWithCurrentFocus()
        Toast.makeText(requireContext(), "Folder removed", Toast.LENGTH_SHORT).show()
    }

    private fun resumeLastPlayed() {
        val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val queueJson = settings.getString("last_played_queue", null) ?: return
        val pos = settings.getLong("last_played_pos", 0)
        val index = settings.getInt("last_played_index", 0)
        
        try {
            val queueArray = JSONArray(queueJson)
            val items = mutableListOf<MediaItem>()
            for (i in 0 until queueArray.length()) {
                val entry = queueArray.optJSONObject(i)
                if (entry != null) {
                    val mId = entry.optString("mediaId", "")
                    val uri = entry.optString("uri", mId)
                    val title = entry.optString("title", "")
                    val artist = entry.optString("artist", "")
                    val start = entry.optLong("start", 0)
                    val end = entry.optLong("end", androidx.media3.common.C.TIME_UNSET)
                    
                    if (uri.isNotEmpty()) {
                        items.add(createMediaItem(title, artist, uri, mId, start, end))
                    }
                } else {
                    val uri = queueArray.getString(i)
                    val segment = if (uri.startsWith("smb://")) {
                        Uri.decode(uri.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: uri)
                    } else {
                        uri.toUri().lastPathSegment ?: uri
                    }
                    val title = segment.substringBeforeLast(".")
                    items.add(createMediaItem(title, "", uri))
                }
            }
            
            if (items.isEmpty()) return
            
            val activity = activity as? MainActivity
            activity?.getController()?.let { controller ->
                controller.setMediaItems(items, index, pos)
                controller.prepare()
                controller.play()
                
                val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearRecentFiles() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit { putString("recent_list", "[]") }
        refreshWithCurrentFocus()
        Toast.makeText(requireContext(), "Recent files cleared", Toast.LENGTH_SHORT).show()
    }

    private fun showRecentFilesMenu() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_RECENT, true)
        val items = listOf(
            DialogOptionItem(
                if (isEnabled) "Remember Recent Files: Enabled" else "Remember Recent Files: Disabled",
                R.drawable.ic_history
            ),
            DialogOptionItem("Clear Recent List", R.drawable.ic_history)
        )
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Recent Files Settings")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> {
                        toggleRecentFiles()
                        showRecentFilesMenu() // Show again to reflect toggle
                    }
                    1 -> clearRecentFiles()
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showScanLibraryMenu() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val autoScan = prefs.getBoolean(KEY_AUTO_SCAN, false)
        val items = listOf(
            DialogOptionItem("Perform Library Scan Now", R.drawable.ic_sync),
            DialogOptionItem(
                if (autoScan) "Automatic Library Scan: Enabled" else "Automatic Library Scan: Disabled",
                R.drawable.ic_history
            )
        )
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Scan Library Settings")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> showScanConfirmationDialog()
                    1 -> {
                        toggleAutoScan()
                        showScanLibraryMenu() // Show again to reflect toggle
                    }
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showScanConfirmationDialog() {
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Perform Scan")
            .setIcon(R.drawable.ic_sync)
            .setMessage("Scan all configured folders for new or deleted files?")
            .setPositiveButton("Yes") { _, _ -> performLibraryScan() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun toggleAutoScan() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_AUTO_SCAN, false)
        prefs.edit { putBoolean(KEY_AUTO_SCAN, !current) }
        refreshWithCurrentFocus()
    }

    private fun showNetworkSettingsMenu() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_NETWORK_BUFFER, true)
        val isReconnectEnabled = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        val items = listOf(
            DialogOptionItem(
                if (isEnabled) "Enhanced Network Buffer: Enabled" else "Enhanced Network Buffer: Disabled",
                R.drawable.ic_network
            ),
            DialogOptionItem(
                if (isReconnectEnabled) "Auto Reconnect (Radio): Enabled" else "Auto Reconnect (Radio): Disabled",
                R.drawable.ic_sync
            )
        )
        val adapter = DialogOptionAdapter(requireContext(), items)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Network Settings")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> {
                        val current = prefs.getBoolean(KEY_NETWORK_BUFFER, true)
                        prefs.edit { putBoolean(KEY_NETWORK_BUFFER, !current) }
                        showNetworkSettingsMenu()
                        refreshWithCurrentFocus()
                    }
                    1 -> {
                        val current = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
                        prefs.edit { putBoolean(KEY_AUTO_RECONNECT, !current) }
                        showNetworkSettingsMenu()
                        refreshWithCurrentFocus()
                    }
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun performLibraryScan() {
        Toast.makeText(requireContext(), "Scanning library...", Toast.LENGTH_SHORT).show()
        updatePlaylistRow()
        refreshWithCurrentFocus()
        // Here we could add more logic to deep scan folders if needed
        Toast.makeText(requireContext(), "Library scan complete", Toast.LENGTH_SHORT).show()
    }

    private fun showAboutDialog() {
        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { "Unknown" }

        val buildDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(BuildConfig.BUILD_TIME))

        val buildTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(BuildConfig.BUILD_TIME))

        val context = requireContext()
        val logoSize = (96 * context.resources.displayMetrics.density).toInt()
        val logo = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = android.widget.LinearLayout.LayoutParams(logoSize, logoSize)
        }
        val textView = android.widget.TextView(context).apply {
            text = "Release: $versionName\nBuild Date: $buildDate\nBuild Time: $buildTime\n\nGitHub: https://github.com/antoxa78/BitperfectPlayer\n\nA high-fidelity music player for Android TV."
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(16, 8, 16, 8)
            addView(logo)
            addView(textView)
        }

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("About Bitperfect Player")
            .setView(layout)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun handleAction(actionId: String) {
        val activity = activity as? MainActivity
        when {
            actionId == "action:Internal Storage" -> {
                checkAndBrowseInternalStorage()
            }
            actionId == "action:External Drive" -> {
                showExternalDrivesDialog()
            }
            actionId == "action:Add SMB Source" -> {
                showAddSmbDialog()
            }
            actionId == "action:Player Color Scheme" -> {
                showColorSchemeDialog()
            }
            actionId == "action:Screensaver" -> {
                showScreensaverSettings()
            }
            actionId == "action:Resume Playback" -> {
                toggleResumePlayback()
            }
            actionId == "action:Recent Files" -> {
                showRecentFilesMenu()
            }
            actionId == "action:Network Settings" -> {
                showNetworkSettingsMenu()
            }
            actionId == "action:Music Folders" -> {
                manageMusicFolders()
            }
            actionId == "action:Scan Library" -> {
                showScanLibraryMenu()
            }
            actionId == "action:Waveform Type" -> {
                showWaveformTypeDialog()
            }
            actionId == "action:Resume Last Played" -> {
                resumeLastPlayed()
            }
            actionId == "action:USB DAC" -> {
                showUsbDacDialog()
            }
            actionId == "action:About" -> {
                showAboutDialog()
            }
            actionId == "action:Exit" -> {
                AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Exit")
                    .setMessage("Are you sure you want to exit?")
                    .setPositiveButton("Yes") { _, _ ->
                        activity?.stopService(android.content.Intent(requireContext(), PlaybackService::class.java))
                        activity?.finishAffinity()
                        System.exit(0)
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
            actionId.startsWith("action:NOW_") -> {
                val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                startActivity(intent)
            }
        }
    }

    companion object {
        fun getSidebarColor(context: Context): Int {
            val index = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                .getInt("color_scheme", 0)
            // For Pure Black schemes (indices 5-8) the sidebar stays pure black (#1a1a1a)
            // so that white/light header text remains readable against a dark background.
            // For all other schemes we tint the sidebar with the accent color as before.
            return when (index) {
                5, 6, 7, 8 -> 0xFF1A1A1A.toInt() // Pure Black variants → dark sidebar
                else -> {
                    val colors = intArrayOf(
                        0xFF00E676.toInt(), // Neon Green
                        0xFF2979FF.toInt(), // Electric Blue
                        0xFFFFC400.toInt(), // Amber Gold
                        0xFF7C4DFF.toInt(), // Deep Purple
                        0xFFFF4081.toInt(), // Hot Pink
                    )
                    if (index in colors.indices) colors[index] else colors[0]
                }
            }
        }

        fun getThemeColor(context: Context): Int {
            val colors = intArrayOf(
                0xFF00E676.toInt(), // Neon Green
                0xFF2979FF.toInt(), // Electric Blue
                0xFFFFC400.toInt(), // Amber Gold
                0xFF7C4DFF.toInt(), // Deep Purple
                0xFFFF4081.toInt(), // Hot Pink
                0xFF888888.toInt(), // Grey
                0xFFFF5252.toInt(), // Red
                0xFF1B5E20.toInt(), // Dark Green
                0xFFFFFFFF.toInt()  // White
            )
            val index = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                .getInt("color_scheme", 0)
            return if (index in colors.indices) colors[index] else colors[0]
        }
    }
}
