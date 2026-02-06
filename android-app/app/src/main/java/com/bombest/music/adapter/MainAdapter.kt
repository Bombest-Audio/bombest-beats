package com.bombest.music.adapter

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bombest.music.R
import com.bombest.music.activities.PlaySongActivity
import com.bombest.music.databinding.ListItemMainBinding
import com.bombest.music.model.TrackItem
import java.util.*

/**
 * Created by Azhar Rivaldi on 26-06-2021
 * Youtube Channel : https://bit.ly/2PJMowZ
 * Github : https://github.com/AzharRivaldi
 * Twitter : https://twitter.com/azharrvldi_
 * Instagram : https://www.instagram.com/azhardvls_
 * Linkedin : https://www.linkedin.com/in/azhar-rivaldi
 */

class MainAdapter(private var songList: ArrayList<TrackItem>, private val context: Context) : RecyclerView.Adapter<MainAdapter.MyViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ListItemMainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.binding.tvJudulLagu.text = songList[position].title
        holder.binding.cvListMusic.setOnClickListener {
            val intent = Intent(context, PlaySongActivity::class.java)
            intent.putParcelableArrayListExtra("songs", songList)
            intent.putExtra("songIndex", position)
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int {
        return songList.size
    }

    fun updateData(newList: ArrayList<TrackItem>) {
        songList = newList
        notifyDataSetChanged()
    }

    class MyViewHolder(val binding: ListItemMainBinding) : RecyclerView.ViewHolder(binding.root)

}