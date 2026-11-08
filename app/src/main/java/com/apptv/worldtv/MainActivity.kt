package com.apptv.worldtv

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val headers: Map<String, String> = emptyMap()
)

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var adapter: ChannelAdapter

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadPlaylistFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .build()
        findViewById<PlayerView>(R.id.player_view).player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(this@MainActivity, "Playback failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })

        adapter = ChannelAdapter(emptyList()) { channel -> play(channel.url, channel.headers) }
        findViewById<RecyclerView>(R.id.channel_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<MaterialButton>(R.id.add_url_button).setOnClickListener { showUrlDialog() }
        findViewById<MaterialButton>(R.id.add_file_button).setOnClickListener {
            filePicker.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain"))
        }

        loadBundledPlaylist()
    }

    private fun loadBundledPlaylist() {
        thread {
            try {
                assets.open("index.m3u").use { stream ->
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    showChannels(parseM3u(content))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load the bundled playlist: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply { hint = "https://example.com/playlist.m3u" }
        AlertDialog.Builder(this)
            .setTitle("Load M3U playlist")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                loadPlaylistFromUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPlaylistFromUrl(sourceUrl: String) {
        thread {
            try {
                val connection = URL(sourceUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.instanceFollowRedirects = true
                connection.inputStream.use { stream ->
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    showChannels(parseM3u(content, sourceUrl))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load playlist: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadPlaylistFromUri(uri: Uri) {
        thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    showChannels(parseM3u(content))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not import playlist: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseM3u(content: String, baseUrl: String? = null): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var currentName = "Unknown channel"
        var currentGroup = "Uncategorized"
        var currentHeaders = mutableMapOf<String, String>()

        for (line in lines) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val name = line.substringAfterLast(',').trim().ifEmpty { "Unknown channel" }
                val group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)
                    ?.ifEmpty { "Uncategorized" } ?: "Uncategorized"
                val userAgent = Regex("http-user-agent=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)
                val referrer = Regex("http-referrer=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)
                currentName = name
                currentGroup = group
                currentHeaders = mutableMapOf<String, String>().apply {
                    userAgent?.let { put("User-Agent", it) }
                    referrer?.let { put("Referer", it) }
                }
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val directive = line.substringAfter(':', "")
                val key = directive.substringBefore('=').trim().lowercase()
                val value = directive.substringAfter('=', "").trim()
                if (key == "http-user-agent") currentHeaders["User-Agent"] = value
                if (key == "http-referrer") currentHeaders["Referer"] = value
            } else if (!line.startsWith("#") && line.isNotEmpty()) {
                val resolvedUrl = resolveUrl(line, baseUrl)
                channels.add(Channel(currentName, resolvedUrl, currentGroup, currentHeaders.toMap()))
                currentHeaders = mutableMapOf<String, String>()
            }
        }
        return channels
    }

    private fun resolveUrl(streamUrl: String, baseUrl: String?): String {
        if (streamUrl.startsWith("http://", ignoreCase = true) ||
            streamUrl.startsWith("https://", ignoreCase = true)
        ) return streamUrl
        if (baseUrl == null) return streamUrl
        return try {
            java.net.URL(java.net.URL(baseUrl), streamUrl).toString()
        } catch (_: Exception) {
            streamUrl
        }
    }

    private fun showChannels(channels: List<Channel>) {
        runOnUiThread {
            adapter.update(channels)
            findViewById<TextView>(R.id.channel_count).text = if (channels.size == 1) {
                getString(R.string.single_channel)
            } else {
                getString(R.string.channel_count, channels.size)
            }
            if (channels.isEmpty()) {
                Toast.makeText(this, "No channels found in this playlist", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Playlist loaded", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun play(url: String, headers: Map<String, String> = emptyMap()) {
        player.release()
        val requestHeaders = headers.toMutableMap()
        requestHeaders.putIfAbsent("User-Agent", "Mozilla/5.0")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        findViewById<PlayerView>(R.id.player_view).player = player
        player.setMediaItem(MediaItem.fromUri(url.toUri()))
        player.prepare()
        player.play()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@androidx.media3.common.util.UnstableApi
class ChannelAdapter(
    private var channels: List<Channel>,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.channel_name)
        val group: TextView = view.findViewById(R.id.channel_group)
    }

    fun update(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.name.text = channel.name
        holder.group.text = channel.group
        holder.itemView.setOnClickListener { onClick(channel) }
    }

    override fun getItemCount() = channels.size
}
