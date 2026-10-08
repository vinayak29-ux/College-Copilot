package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatSession(
    val id: String,
    var title: String,
    var timestamp: Long,
    val messages: MutableList<Message> = mutableListOf()
) {
    companion object {
        const val MY_ASSISTANT_ID = "my-assistant"
    }
}

class RecentChatAdapter(
    private val sessions: List<ChatSession>,
    private val onChatClick: (ChatSession) -> Unit,
    private val onChatDelete: (ChatSession) -> Unit
) : RecyclerView.Adapter<RecentChatAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleTv: TextView = view.findViewById(R.id.chat_title)
        val deleteBtn: ImageButton = view.findViewById(R.id.btn_delete_chat)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_chat, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = recentSessions()[position]
        holder.titleTv.text = session.title
        holder.itemView.setOnClickListener { onChatClick(session) }
        holder.deleteBtn.setOnClickListener { onChatDelete(session) }
    }

    override fun getItemCount(): Int = recentSessions().size

    private fun recentSessions(): List<ChatSession> =
        sessions.filterNot { it.id == ChatSession.MY_ASSISTANT_ID }
}
