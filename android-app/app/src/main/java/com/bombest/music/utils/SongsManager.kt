package com.bombest.music.utils

import android.os.Environment
import java.io.File
import java.io.FilenameFilter
import java.util.*

/**
 * Created by Azhar Rivaldi on 26-06-2021
 * Youtube Channel : https://bit.ly/2PJMowZ
 * Github : https://github.com/AzharRivaldi
 * Twitter : https://twitter.com/azharrvldi_
 * Instagram : https://www.instagram.com/azhardvls_
 * Linkedin : https://www.linkedin.com/in/azhar-rivaldi
 */

class SongsManager {

    var MEDIA_PATH = Environment.getExternalStorageDirectory().toString()
    private val songsList = ArrayList<HashMap<String, String>>()

    fun getPlayList(): ArrayList<HashMap<String, String>> {
        val home = File(MEDIA_PATH)
        val files = home.listFiles(FileExtensionFilter()) ?: emptyArray()
        for (file in files) {
            val song = HashMap<String, String>()
            song["songTitle"] = file.name.substring(0, file.name.length - 4)
            song["songPath"] = file.path
            songsList.add(song)
        }
        return songsList
    }

    internal inner class FileExtensionFilter : FilenameFilter {
        override fun accept(dir: File, name: String): Boolean {
            return name.endsWith(".mp3") || name.endsWith(".MP3")
        }
    }

}