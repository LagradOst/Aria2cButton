package com.lagradost.fetchbutton.example

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import com.lagradost.fetchbutton.NotificationMetaData
import com.lagradost.fetchbutton.aria2c.Aria2Settings
import com.lagradost.fetchbutton.aria2c.Aria2Starter
import com.lagradost.fetchbutton.aria2c.DownloadStatusTell
import com.lagradost.fetchbutton.aria2c.newUriRequest
import com.lagradost.fetchbutton.ui.PieFetchButton
import java.util.*
import kotlin.concurrent.thread


/**id, stringRes */
@SuppressLint("RestrictedApi")
fun View.popupMenuNoIcons(
    items: List<Pair<Int, String>>,
    onMenuItemClick: MenuItem.() -> Unit,
): PopupMenu {
    val popup = PopupMenu(context, this, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)

    items.forEach { (id, stringRes) ->
        popup.menu.add(0, id, 0, stringRes)
    }

    (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

    popup.setOnMenuItemClickListener {
        it.onMenuItemClick()
        true
    }

    popup.show()
    return popup
}

class MainActivity : AppCompatActivity() {
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                Log.d("TAG", "onActivityResult: " + uri.path);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }

            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val downloadButton = findViewById<PieFetchButton>(R.id.download_button)

        thread {
            Aria2Starter.start(
                this,
                Aria2Settings(
                    UUID.randomUUID().toString(),
                    4337,
                    filesDir.path,
                    //"${filesDir.path}/session"
                )
            )
        }

        val pId = 1L
        downloadButton.setPersistentId(pId)

        //val image = BitmapFactory.decodeResource(resources, R.drawable.example_poster)

        val uriReq = newUriRequest(
            pId,
            "https://speed.hetzner.de/100MB.bin",
            "Hello World",
            "/storage/emulated/0/Download",
            notificationMetaData = NotificationMetaData(
                pId.toInt(),
                0xFF0000,
                "contentTitle",
                "Subtext",
                "https://www.royalroadcdn.com/public/covers-full/36735-the-perfect-run.jpg",
                "SpeedTest",
                "secondRow"
            )
        )

        //downloadButton.setDefaultClickListener {
        //    listOf(uriReq)
        //}

        downloadButton.setOnClickListener { view ->
            if (view !is PieFetchButton) return@setOnClickListener
            val id = view.persistentId
            when (view.currentStatus) {
                null, DownloadStatusTell.Removed -> {
                    view.setStatus(DownloadStatusTell.Waiting)
                    Aria2Starter.download(
                        uriReq
                    )
                }
                DownloadStatusTell.Paused -> {
                    view.popupMenuNoIcons(listOf(1 to "Resume", 2 to "Play Files", 3 to "Delete")) {
                        when (itemId) {
                            1 -> if (!view.resumeDownload()) {
                                Aria2Starter.download(
                                    uriReq
                                )
                            }
                            2 -> {
                                val files = view.getFiles()
                                println("FILES:$files")
                            }
                            3 -> {
                                view.deleteAllFiles()
                            }
                        }
                    }

                }
                DownloadStatusTell.Complete -> {
                    view.popupMenuNoIcons(listOf(2 to "Play Files", 3 to "Delete")) {
                        when (itemId) {
                            2 -> {
                                val files = view.getFiles()
                                println("FILES:$files")
                            }
                            3 -> {
                                view.deleteAllFiles()
                            }
                        }
                    }
                }
                DownloadStatusTell.Active -> {
                    view.pauseDownload()
                }
                DownloadStatusTell.Error -> {
                    view.redownload()
                }
                DownloadStatusTell.Waiting -> {
                    println("REMOVED")
                }
                else -> {}
            }
        }
        //downloadButton.setDefaultClickListener {
        //    val id = downloadButton.persistentId ?: return@setDefaultClickListener listOf()
        //    listOf(
        //        //newUriRequest(id,"magnet:?xt=urn:btih:f7a543c036b06ba973beea56e98543c9e315314d&dn=%5BSubsPlease%5D%20Yofukashi%20no%20Uta%20-%2010%20%281080p%29%20%5B52314177%5D.mkv&tr=http%3A%2F%2Fnyaa.tracker.wf%3A7777%2Fannounce&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fexodus.desync.com%3A6969%2Fannounce&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce")
        //        newUriRequest(
        //            id, "https://speed.hetzner.de/1010MB.bin", "Hello World",
        //        ),
        //        newUriRequest(
        //            id, "https://speed.hetzner.de/100MB.bin", "Hello World2",
        //        ),
        //        //newUriRequest(
        //        //    id, "https://speed.hetzner.de/100MB.bin", "Hello World",
        //        //)
        //    )
        //}
    }
}

