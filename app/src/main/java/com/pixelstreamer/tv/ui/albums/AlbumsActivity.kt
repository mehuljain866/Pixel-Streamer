package com.pixelstreamer.tv.ui.albums

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelstreamer.tv.PixelStreamerApp
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.AlbumSource
import com.pixelstreamer.tv.data.model.AlbumType
import com.pixelstreamer.tv.data.model.VideoQuality
import com.pixelstreamer.tv.databinding.ActivityAlbumsBinding
import com.pixelstreamer.tv.util.QrGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlbumsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumsBinding
    private val app by lazy { application as PixelStreamerApp }
    private val repo by lazy { app.repository }
    private lateinit var adapter: AlbumListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AlbumListAdapter()
        binding.albumsRecycler.layoutManager = LinearLayoutManager(this)
        binding.albumsRecycler.adapter = adapter

        binding.btnAlbumsDone.setOnClickListener { finish() }
        binding.btnAddAlbumDirect.setOnClickListener { showAddAlbumDialog() }
        binding.btnSyncAll.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@AlbumsActivity, "Syncing enabled albums...", Toast.LENGTH_SHORT).show()
                repo.syncAllEnabledAlbums()
                Toast.makeText(this@AlbumsActivity, "Sync finished", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTmdbSettings.setOnClickListener { showSettingsDialog() }

        lifecycleScope.launch {
            repo.albums.collect { list ->
                adapter.submit(list)
                binding.albumsListHeader.text = if (list.isEmpty()) {
                    "No albums added yet — scan the QR code on the left or click '+ Paste Link on TV'"
                } else {
                    "Shared Albums (${list.size}) — Toggle visibility or Pick specific items"
                }
            }
        }

        binding.btnAddAlbumDirect.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) {
            val url = app.acquireCompanionServer()
            if (url != null) {
                val bmp = QrGenerator.generate(url, 360)
                runOnUiThread {
                    binding.albumsServerUrl.text = "$url\nOpen on your phone browser"
                    binding.albumsQrImage.setImageBitmap(bmp)
                }
            } else {
                runOnUiThread {
                    binding.albumsServerUrl.text = "No local Wi-Fi IP detected"
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        app.releaseCompanionServer()
    }

    private fun showAddAlbumDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val urlInput = EditText(this).apply {
            hint = "https://photos.app.goo.gl/... or photos.google.com/share/..."
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9E9E9E.toInt())
            setSingleLine(true)
        }
        val titleInput = EditText(this).apply {
            hint = "Optional Shelf Title (e.g. Movies, Vacation)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9E9E9E.toInt())
            setSingleLine(true)
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AlbumsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                AlbumType.entries.map { it.label }
            )
        }
        box.addView(urlInput)
        box.addView(titleInput)
        box.addView(typeSpinner)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Add Google Photos Shared Album")
            .setMessage("Paste a public shared album link. No Google sign-in required.")
            .setView(box)
            .setPositiveButton("Add & Sync") { _, _ ->
                val rawUrl = urlInput.text?.toString().orEmpty().trim()
                val customTitle = titleInput.text?.toString().orEmpty().trim()
                val type = AlbumType.entries[typeSpinner.selectedItemPosition]
                if (rawUrl.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    Toast.makeText(this@AlbumsActivity, "Fetching shared album...", Toast.LENGTH_SHORT).show()
                    val res = repo.addAndSyncAlbum(rawUrl, customTitle, type)
                    if (res.isSuccess) {
                        Toast.makeText(
                            this@AlbumsActivity,
                            "Synced ${res.getOrThrow().displayTitle}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@AlbumsActivity,
                            res.exceptionOrNull()?.message ?: "Failed to sync",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPickItemsDialog(album: AlbumSource) {
        val items = repo.getAlbumItems(album.id, includeHidden = true)
        if (items.isEmpty()) {
            Toast.makeText(this, "No items synced in this album yet", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = items.map { item ->
            val kind = if (item.isVideo) "Video" else "Photo"
            val dur = if (item.displayDuration.isNotBlank()) " (${item.displayDuration})" else ""
            "${item.displayTitle} [$kind$dur]"
        }.toTypedArray()
        val checked = items.map { !it.hidden }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select Items to Show (${album.displayTitle})")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save Selection") { _, _ ->
                lifecycleScope.launch {
                    items.forEachIndexed { idx, item ->
                        val shouldHide = !checked[idx]
                        if (item.hidden != shouldHide) {
                            repo.toggleItemHidden(album.id, item.id, shouldHide)
                        }
                    }
                }
            }
            .setNeutralButton("Show All") { _, _ ->
                lifecycleScope.launch {
                    items.forEach { if (it.hidden) repo.toggleItemHidden(album.id, it.id, false) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val tmdbInput = EditText(this).apply {
            setText(repo.tmdbApiKey)
            hint = "Optional TMDB API Key (IMDb + Wikipedia art works without a key)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9E9E9E.toInt())
            setSingleLine(true)
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AlbumsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                VideoQuality.entries.map { it.label }
            )
            setSelection(VideoQuality.entries.indexOf(repo.defaultQuality).coerceAtLeast(0))
        }
        box.addView(tmdbInput)
        box.addView(qualitySpinner)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Movie Artwork & Stream Quality")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                repo.tmdbApiKey = tmdbInput.text?.toString().orEmpty()
                repo.defaultQuality = VideoQuality.entries[qualitySpinner.selectedItemPosition]
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class AlbumListAdapter : RecyclerView.Adapter<AlbumListAdapter.AlbVH>() {

        private var items: List<AlbumSource> = emptyList()

        fun submit(list: List<AlbumSource>) {
            items = list
            notifyDataSetChanged()
        }

        inner class AlbVH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.albRowTitle)
            val meta: TextView = view.findViewById(R.id.albRowMeta)
            val btnToggle: Button = view.findViewById(R.id.btnAlbToggle)
            val btnPick: Button = view.findViewById(R.id.btnAlbSelectItems)
            val btnType: Button = view.findViewById(R.id.btnAlbType)
            val btnDelete: Button = view.findViewById(R.id.btnAlbDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_album_row, parent, false)
            return AlbVH(v)
        }

        override fun onBindViewHolder(holder: AlbVH, position: Int) {
            val album = items[position]
            val visibleCount = repo.getAlbumItems(album.id, includeHidden = false).size
            holder.title.text = album.displayTitle
            holder.meta.text = "$visibleCount/${album.itemCount} visible · ${album.type.label}"

            holder.btnToggle.text = if (album.enabled) "Shown" else "Hidden"
            holder.btnToggle.setOnClickListener {
                lifecycleScope.launch { repo.toggleAlbumEnabled(album.id, !album.enabled) }
            }

            holder.btnPick.setOnClickListener {
                showPickItemsDialog(album)
            }

            holder.btnType.text = album.type.label
            holder.btnType.setOnClickListener {
                val types = AlbumType.entries
                val next = types[(types.indexOf(album.type) + 1) % types.size]
                lifecycleScope.launch { repo.updateAlbumMeta(album.id, album.customTitle, next) }
            }

            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@AlbumsActivity)
                    .setTitle("Remove Album?")
                    .setMessage("Remove '${album.displayTitle}' from Pixel Streamer?")
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch { repo.removeAlbum(album.id) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
