package com.bombest.music.activities

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bombest.music.R
import com.bombest.music.databinding.ActivityPlaySongBinding
import com.bombest.music.model.TrackItem
import com.bombest.music.utils.SongTimer
import java.io.IOException
import java.util.*

class PlaySongActivity : AppCompatActivity(), OnSeekBarChangeListener, OnCompletionListener {

    private lateinit var binding: ActivityPlaySongBinding
    lateinit var mediaPlayer: MediaPlayer
    lateinit var songTimer: SongTimer
    lateinit var songTitle: String
    var handler = Handler(Looper.getMainLooper())
    var seekForwardTime = 5000
    var seekBackwardTime = 5000
    var currentSongIndex = 0
    var isShuffle = false
    var isRepeat = false
    var songList = ArrayList<TrackItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaySongBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= 21) {
            setWindowFlag(this, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, false)
            window.statusBarColor = Color.TRANSPARENT
        }

        setSupportActionBar(binding.toolbar)
        assert(supportActionBar != null)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.tvJudulLagu.setSelected(true)

        //get data intent from adapter
        val bundle = intent.extras
        songList = intent.getParcelableArrayListExtra("songs") ?: arrayListOf()
        currentSongIndex = bundle?.getInt("songIndex") ?: 0

        mediaPlayer = MediaPlayer()
        songTimer = SongTimer()

        binding.seekBar.setOnSeekBarChangeListener(this)
        mediaPlayer.setOnCompletionListener(this)

        if (songList.isEmpty()) {
            Toast.makeText(this, "No tracks available", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        getPlaySong(currentSongIndex)

        //methods button action
        getButtonSong()
    }

    fun getButtonSong() {
        binding.imagePlay.setOnClickListener {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                binding.imagePlay.setBackgroundResource(R.drawable.ic_play)
            } else {
                try {
                    mediaPlayer.start()
                    binding.visualizerView.getPathMedia(mediaPlayer)
                    binding.imagePlay.setBackgroundResource(R.drawable.ic_pause)
                    updateSeekBar()
                } catch (e: Exception) {
                    Toast.makeText(this@PlaySongActivity, "Unable to play track", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.imageNext.setOnClickListener {
            val nextIndex = currentSongIndex + 1
            if (nextIndex < songList.size) {
                mediaPlayer.stop()
                binding.imagePlay.setBackgroundResource(R.drawable.ic_play)
                currentSongIndex = nextIndex
                getPlaySong(currentSongIndex)
            }
        }

        binding.imagePrev.setOnClickListener {
            val prevIndex = currentSongIndex - 1
            if (prevIndex >= 0) {
                mediaPlayer.stop()
                binding.imagePlay.setBackgroundResource(R.drawable.ic_play)
                currentSongIndex = prevIndex
                getPlaySong(currentSongIndex)
            }
        }

        binding.imageForward.setOnClickListener {
            val currentPosition = mediaPlayer.currentPosition
            if (currentPosition + seekForwardTime <= mediaPlayer.duration) {
                mediaPlayer.seekTo(currentPosition + seekForwardTime)
            } else {
                mediaPlayer.seekTo(mediaPlayer.duration)
            }
        }

        binding.imageRewind.setOnClickListener {
            val currentPosition = mediaPlayer.currentPosition
            if (currentPosition - seekBackwardTime >= 0) {
                mediaPlayer.seekTo(currentPosition - seekBackwardTime)
            } else {
                mediaPlayer.seekTo(0)
            }
        }

        binding.imageRepeat.setOnClickListener {
            if (isRepeat) {
                isRepeat = false
                Toast.makeText(this@PlaySongActivity, "Repeat off", Toast.LENGTH_SHORT).show()
                binding.imageRepeat.setImageResource(R.drawable.btn_repeat)
            } else {
                isRepeat = true
                Toast.makeText(this@PlaySongActivity, "Repeat on", Toast.LENGTH_SHORT).show()
                isShuffle = false
                binding.imageRepeat.setImageResource(R.drawable.btn_repeat_focused)
                binding.imageShuffle.setImageResource(R.drawable.btn_shuffle)
            }
        }

        binding.imageShuffle.setOnClickListener {
            if (isShuffle) {
                isShuffle = false
                Toast.makeText(this@PlaySongActivity, "Shuffle off", Toast.LENGTH_SHORT).show()
                binding.imageShuffle.setImageResource(R.drawable.btn_shuffle)
            } else {
                isShuffle = true
                Toast.makeText(this@PlaySongActivity, "Shuffle on", Toast.LENGTH_SHORT).show()
                isRepeat = false
                binding.imageShuffle.setImageResource(R.drawable.btn_shuffle_focused)
                binding.imageRepeat.setImageResource(R.drawable.btn_repeat)
            }
        }
    }

    private fun getPlaySong(songIndex: Int) {
        try {
            mediaPlayer.reset()
            val track = songList[songIndex]
            mediaPlayer.setDataSource(track.streamUrl)
            mediaPlayer.setOnPreparedListener { mp ->
                binding.seekBar.progress = 0
                binding.seekBar.max = 100
                binding.tvJudulLagu.text = track.title
                mp.start()
                binding.visualizerView.getPathMedia(mp)
                binding.imagePlay.setBackgroundResource(R.drawable.ic_pause)
                updateSeekBar()
            }
            mediaPlayer.prepareAsync()
            songTitle = track.title

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Unable to play ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSeekBar() {
        handler.postDelayed(runnable, 100)
    }

    private val runnable: Runnable = object : Runnable {
        override fun run() {
            val totalDuration = mediaPlayer.duration.toLong()
            val currentDuration = mediaPlayer.currentPosition.toLong()
            binding.tvTotalDuration.text = "" + songTimer.milliSecondsToTimer(totalDuration)
            binding.tvCurrentDuration.text = "" + songTimer.milliSecondsToTimer(currentDuration)
            val progress = songTimer.getProgressPercentage(currentDuration, totalDuration)
            binding.seekBar.progress = progress
            handler.postDelayed(this, 100)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        handler.removeCallbacks(runnable)
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        handler.removeCallbacks(runnable)
        val totalDuration = mediaPlayer.duration
        val currentPosition = songTimer.progressToTimer(binding.seekBar.progress, totalDuration)
        mediaPlayer.seekTo(currentPosition)

        //run seekbar
        updateSeekBar()
    }

    override fun onCompletion(mp: MediaPlayer) {
        if (isRepeat) {
            getPlaySong(currentSongIndex)
        } else if (isShuffle) {
            val rand = Random()
            currentSongIndex = rand.nextInt(songList.size)
            getPlaySong(currentSongIndex)
        } else {
            if (currentSongIndex < songList.size - 1) {
                currentSongIndex += 1
                getPlaySong(currentSongIndex)
            } else {
                currentSongIndex = 0
                getPlaySong(currentSongIndex)
            }
        }
    }

    companion object {
        fun setWindowFlag(activity: Activity, bits: Int, on: Boolean) {
            val window = activity.window
            val layoutParams = window.attributes
            if (on) {
                layoutParams.flags = layoutParams.flags or bits
            } else {
                layoutParams.flags = layoutParams.flags and bits.inv()
            }
            window.attributes = layoutParams
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    public override fun onDestroy() {
        handler.removeCallbacks(runnable)
        super.onDestroy()
        mediaPlayer.release()
    }

}