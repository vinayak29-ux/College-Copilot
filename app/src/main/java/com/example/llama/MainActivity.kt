package com.example.llama

import android.net.Uri
import android.graphics.ImageDecoder
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.view.ViewGroup
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import com.example.llama.alarm.AlarmScheduler
import com.example.llama.tools.ToolCommand
import com.example.llama.tools.AlarmSlashCommandParser
import com.example.llama.tools.ToolRouter
import com.example.llama.ocr.OcrResult
import com.example.llama.ocr.TextRecognizer
import com.example.llama.timetable.TimetableDebugFormatter
import com.example.llama.timetable.TimetableParser
import com.example.llama.assistant.AssistantApiClient
import com.example.llama.assistant.AssistantAttachment
import com.example.llama.assistant.AssistantRepository
import com.example.llama.assistant.BackboardAssistantRepository
import com.example.llama.assistant.ChatMode
import org.json.JSONArray
import org.json.JSONObject

import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var ggufTv: TextView
    private lateinit var topTitleTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var slashAlarmSuggestion: MaterialButton
    private lateinit var btnModelSwap: MaterialButton
    private lateinit var btnChatMode: MaterialButton
    private lateinit var recentChatsRv: RecyclerView
    private lateinit var imagePreviewContainer: FrameLayout
    private lateinit var imagePreviewIv: ImageView

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null
    private val modelPreferences by lazy { getSharedPreferences(PREFS_MODEL, MODE_PRIVATE) }
    private val profilePreferences by lazy { getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE) }
    private val assistantRepository: AssistantRepository by lazy {
        BackboardAssistantRepository(
            AssistantApiClient(BuildConfig.BACKBOARD_API_KEY),
            getSharedPreferences(PREFS_ASSISTANT_THREAD, MODE_PRIVATE)
        )
    }

    // Conversation & session states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)
    private val chatSessions = mutableListOf<ChatSession>()
    private var currentSession: ChatSession? = null
    private var lastLocalSessionId: String? = null
    private lateinit var recentChatAdapter: RecentChatAdapter
    private var isSessionRestored = false
    private var chatMode = ChatMode.LOCAL
    private var assistantUsesLocalModel = false
    private var localModelLabel = "Select Model"
    private var localModelStatus = "Selected GGUF model's metadata will show here."
    private var assistantRequestJob: Job? = null
    private var assistantRequestGeneration = 0
    private var selectedImageUri: Uri? = null
    private var ocrRecognizer: TextRecognizer? = null
    private val alarmScheduler by lazy { AlarmScheduler(this) }
    private val toolRouter by lazy { ToolRouter(alarmScheduler) }
    private var pendingToolCommand: ToolCommand? = null
    private var awaitingExactAlarmSettings = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingToolCommand?.let { command ->
            if (!granted) notificationPermissionDenied = true
            routeToolCommand(command)
        }
    }
    private var notificationPermissionDenied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Handle back press to close drawer if open
        onBackPressedDispatcher.addCallback {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                Log.w(TAG, "Ignore back press for simplicity")
            }
        }

        // Find main views
        drawerLayout = findViewById(R.id.drawer_layout)
        ggufTv = findViewById(R.id.gguf)
        topTitleTv = findViewById(R.id.top_title)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        userInputEt.isEnabled = true
        userInputEt.hint = "Type / for commands or select a model"
        userActionFab.setImageResource(R.drawable.outline_send_24)
        slashAlarmSuggestion = findViewById(R.id.slash_alarm_suggestion)
        userInputEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                slashAlarmSuggestion.visibility = if (s?.toString()?.trimStart()?.startsWith("/") == true) View.VISIBLE else View.GONE
            }
        })
        slashAlarmSuggestion.setOnClickListener {
            userInputEt.setText("/alarm set an alarm for ")
            userInputEt.setSelection(userInputEt.text.length)
            userInputEt.requestFocus()
        }
        btnModelSwap = findViewById(R.id.btn_model_swap)
        btnChatMode = findViewById(R.id.btn_chat_mode)
        assistantUsesLocalModel = assistantTranscriptPreferences().getBoolean(KEY_ASSISTANT_LOCAL_PROVIDER, false)
        updateChatModeAppearance()
        recentChatsRv = findViewById(R.id.recent_chats_rv)

        // Find image preview views
        imagePreviewContainer = findViewById(R.id.image_preview_container)
        imagePreviewIv = findViewById(R.id.image_preview)
        findViewById<ImageButton>(R.id.btn_remove_image).setOnClickListener {
            clearAttachedImage()
        }

        // Initialize chat sessions from storage
        initChatSessions()

        // Top-left menu button opens drawer
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        // Drawer buttons
        findViewById<MaterialButton>(R.id.btn_new_chat).setOnClickListener {
            createNewChatSession()
        }
        findViewById<MaterialButton>(R.id.btn_my_assistant).setOnClickListener {
            openMyAssistantChat()
        }
        findViewById<MaterialButton>(R.id.btn_clear_all_chats).setOnClickListener {
            confirmClearAllChats()
        }
        findViewById<MaterialButton>(R.id.btn_user_profile).setOnClickListener {
            showUserProfileDialog()
        }

        // Model selector pill button in chat bar
        btnModelSwap.setOnClickListener {
            showModelChooserOrPicker()
        }
        btnChatMode.setOnClickListener {
            if (chatMode == ChatMode.ASSISTANT) {
                assistantUsesLocalModel = !assistantUsesLocalModel
                assistantTranscriptPreferences().edit().putBoolean(KEY_ASSISTANT_LOCAL_PROVIDER, assistantUsesLocalModel).apply()
                updateChatModeAppearance()
                if (assistantUsesLocalModel && !isModelReady) {
                    Toast.makeText(this, "Choose a local GGUF model to use it in My Assistant.", Toast.LENGTH_LONG).show()
                    showModelChooserOrPicker()
                }
            } else {
                openMyAssistantChat()
            }
        }

        // Plus button in chat bar opens attachment options (image, file, model)
        findViewById<ImageButton>(R.id.btn_import_model).setOnClickListener {
            showAttachmentOptionsDialog()
        }

        ggufTv.setOnClickListener {
            showModelChooserOrPicker()
        }

        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            loadLastSelectedModelIfAny()
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (chatMode == ChatMode.ASSISTANT || isModelReady || AlarmSlashCommandParser.parse(userInputEt.text.toString()) != AlarmSlashCommandParser.Result.NotCommand) {
                handleUserInput()
            } else {
                showModelChooserOrPicker()
            }
        }

        userActionFab.setOnLongClickListener {
            showModelChooserOrPicker()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingExactAlarmSettings) {
            awaitingExactAlarmSettings = false
            pendingToolCommand?.let { command ->
                val exactAccess = alarmScheduler.canScheduleExactAlarms()
                finishToolRouting(command, exactAccess)
            }
        }
    }

    private fun initChatSessions() {
        chatSessions.clear()
        chatSessions.addAll(ChatHistoryStorage.loadSessions(this))
        if (chatSessions.none { it.id != ChatSession.MY_ASSISTANT_ID }) {
            val defaultSession = ChatSession(
                id = UUID.randomUUID().toString(),
                title = "New Chat",
                timestamp = System.currentTimeMillis()
            )
            chatSessions.add(0, defaultSession)
        }
        currentSession = chatSessions.first { it.id != ChatSession.MY_ASSISTANT_ID }
        messages.clear()
        messages.addAll(currentSession!!.messages)
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            messagesRv.scrollToPosition(messages.size - 1)
            isSessionRestored = true
        }

        recentChatAdapter = RecentChatAdapter(
            sessions = chatSessions,
            onChatClick = { session -> switchChatSession(session) },
            onChatDelete = { session -> confirmDeleteChatSession(session) }
        )
        recentChatsRv.layoutManager = LinearLayoutManager(this)
        recentChatsRv.adapter = recentChatAdapter
    }

    private fun switchChatSession(session: ChatSession) {
        assistantRequestGeneration += 1
        assistantRequestJob?.cancel()
        chatMode = ChatMode.LOCAL
        userInputEt.isEnabled = true
        userActionFab.isEnabled = true
        if (currentSession?.id == session.id) {
            updateChatModeAppearance()
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        generationJob?.cancel()
        currentSession = session
        messages.clear()
        messages.addAll(session.messages)
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            messagesRv.scrollToPosition(messages.size - 1)
            isSessionRestored = true
        } else {
            isSessionRestored = false
        }
        updateChatModeAppearance()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun createNewChatSession() {
        assistantRequestGeneration += 1
        assistantRequestJob?.cancel()
        chatMode = ChatMode.LOCAL
        userInputEt.isEnabled = true
        userActionFab.isEnabled = true
        generationJob?.cancel()
        val newSession = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            timestamp = System.currentTimeMillis()
        )
        chatSessions.add(0, newSession)
        currentSession = newSession
        messages.clear()
        messageAdapter.notifyDataSetChanged()
        recentChatAdapter.notifyDataSetChanged()
        isSessionRestored = false
        updateChatModeAppearance()
        ChatHistoryStorage.saveSessions(this, chatSessions)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun openMyAssistantChat() {
        currentSession?.let { lastLocalSessionId = it.id }
        generationJob?.cancel()
        assistantRequestJob?.cancel()
        chatMode = ChatMode.ASSISTANT
        currentSession = null
        messages.clear()
        val savedTranscript = assistantTranscriptPreferences().getString(KEY_ASSISTANT_TRANSCRIPT, null)
        if (!savedTranscript.isNullOrBlank()) {
            runCatching {
                val saved = JSONArray(savedTranscript)
                for (index in 0 until saved.length()) {
                    val item = saved.optJSONObject(index) ?: continue
                    messages += Message(item.optString("id"), item.optString("content"), item.optBoolean("isUser"), item.optString("imageUri").takeIf { it.isNotBlank() })
                }
            }.onFailure { Log.w(TAG, "Could not restore assistant transcript", it) }
        }
        messageAdapter.notifyDataSetChanged()
        updateChatModeAppearance()
        drawerLayout.closeDrawer(GravityCompat.START)
        if (messages.isNotEmpty()) {
            messagesRv.scrollToPosition(messages.lastIndex)
        } else {
            loadAssistantThread()
        }
    }

    private fun updateChatModeAppearance() {
        if (!::topTitleTv.isInitialized || !::btnModelSwap.isInitialized || !::btnChatMode.isInitialized) return
        val isAssistant = chatMode == ChatMode.ASSISTANT
        topTitleTv.text = if (isAssistant) "My Assistant" else "llama.android"
        btnModelSwap.text = if (isAssistant && !assistantUsesLocalModel) "College Copilot" else localModelLabel
        btnModelSwap.isEnabled = !isAssistant || assistantUsesLocalModel
        btnChatMode.text = if (!isAssistant || assistantUsesLocalModel) "Use Backboard" else "Use local"
        btnChatMode.contentDescription = if (isAssistant && !assistantUsesLocalModel) "Switch to local model in this chat" else "Switch to Backboard in this chat"
        if (::ggufTv.isInitialized) {
            ggufTv.text = if (isAssistant) "My Assistant: ${if (assistantUsesLocalModel) "Local GGUF - $localModelLabel" else "College Copilot (Backboard)"}" else localModelStatus
        }
        if (::userInputEt.isInitialized) {
            userInputEt.hint = if (isAssistant) "Message My Assistant" else if (isModelReady) "Type and send a message!" else "Type / for commands or select a model"
            if (!isAssistant) userInputEt.isEnabled = true
        }
        if (!isAssistant && ::userActionFab.isInitialized) userActionFab.isEnabled = true
    }

    private fun setLocalModelLabel(label: String) {
        localModelLabel = label
        if ((chatMode == ChatMode.LOCAL || (chatMode == ChatMode.ASSISTANT && assistantUsesLocalModel)) && ::btnModelSwap.isInitialized) btnModelSwap.text = label
        if (chatMode == ChatMode.ASSISTANT && assistantUsesLocalModel && ::ggufTv.isInitialized) ggufTv.text = "My Assistant: Local GGUF - $label"
    }

    private fun setLocalModelStatus(status: String) {
        localModelStatus = status
        if (chatMode == ChatMode.LOCAL && ::ggufTv.isInitialized) ggufTv.text = status
    }

    private fun loadAssistantThread() {
        val requestGeneration = ++assistantRequestGeneration
        if (!hasInternetConnection()) {
            appendAssistantNotice("College Copilot requires an internet connection.")
            userInputEt.isEnabled = true
            userActionFab.isEnabled = true
            return
        }
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        assistantRequestJob = lifecycleScope.launch {
            try {
                val thread = withContext(Dispatchers.IO) { assistantRepository.openOrCreateThread() }
                if (chatMode != ChatMode.ASSISTANT || requestGeneration != assistantRequestGeneration) return@launch
                messages.clear()
                thread.messages.forEach { message ->
                    messages += Message(message.id, message.content, message.role == "user")
                }
                saveAssistantTranscript()
                messageAdapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) messagesRv.scrollToPosition(messages.lastIndex)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Could not open College Copilot thread", error)
                if (chatMode == ChatMode.ASSISTANT && requestGeneration == assistantRequestGeneration) {
                    appendAssistantNotice(assistantFailureMessage())
                }
            } finally {
                if (chatMode == ChatMode.ASSISTANT && requestGeneration == assistantRequestGeneration) {
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                }
            }
        }
    }

    private fun sendAssistantMessage(content: String) {
        val requestGeneration = ++assistantRequestGeneration
        val imageUri = selectedImageUri
        val prompt = content.ifBlank { "Please look at this image." }
        userInputEt.setText("")
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        val pendingId = UUID.randomUUID().toString()
        messages += Message(UUID.randomUUID().toString(), prompt, true, imageUri?.toString())
        messages += Message(pendingId, "Thinking...", false)
        saveAssistantTranscript()
        clearAttachedImage()
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        messagesRv.scrollToPosition(messages.lastIndex)

        assistantRequestJob = lifecycleScope.launch {
            try {
                if (!hasInternetConnection()) throw AssistantOfflineException()
                val storedThreadId = assistantRepository.storedThreadId()
                val threadId = storedThreadId ?: withContext(Dispatchers.IO) {
                    assistantRepository.openOrCreateThread().threadId
                }
                val attachment = imageUri?.let { uri -> withContext(Dispatchers.IO) { prepareAssistantAttachment(uri) } }
                val earlierMessages = messages.dropLast(2).filter { it.content.isNotBlank() }.takeLast(12)
                val requestContent = if (earlierMessages.isEmpty()) {
                    prompt
                } else {
                    buildString {
                        append("Conversation context from this same My Assistant chat. Use it as prior conversation, then answer the latest message.\n")
                        earlierMessages.forEach { message ->
                            append(if (message.isUser) "User: " else "Assistant: ")
                            append(message.content.trim())
                            append('\n')
                        }
                        append("Latest user message: ")
                        append(prompt)
                    }
                }
                val reply = withContext(Dispatchers.IO) {
                    assistantRepository.sendMessage(threadId, buildPromptWithProfile(requestContent, prompt), attachment)
                }
                replaceAssistantMessage(pendingId, reply.content)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "College Copilot request failed", error)
                val failure = if (error is AssistantAttachmentException) {
                    error.message ?: "Could not prepare that image for upload."
                } else {
                    assistantFailureMessage()
                }
                replaceAssistantMessage(pendingId, failure)
            } finally {
                if (chatMode == ChatMode.ASSISTANT && requestGeneration == assistantRequestGeneration) {
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                }
            }
        }
    }

    private fun replaceAssistantMessage(messageId: String, content: String) {
        if (chatMode != ChatMode.ASSISTANT) return
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        messages[index] = messages[index].copy(content = content)
        saveAssistantTranscript()
        messageAdapter.notifyItemChanged(index)
        messagesRv.scrollToPosition(index)
    }

    private fun appendAssistantNotice(content: String) {
        messages += Message(UUID.randomUUID().toString(), content, false)
        messageAdapter.notifyItemInserted(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
    }

    private fun assistantFailureMessage(): String = if (hasInternetConnection()) {
        "Assistant is temporarily unavailable. Your local AI is still available."
    } else {
        "College Copilot requires an internet connection."
    }

    private fun hasInternetConnection(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private class AssistantOfflineException : IOException()

    private fun assistantTranscriptPreferences() = getSharedPreferences(PREFS_ASSISTANT_THREAD, MODE_PRIVATE)

    private fun saveAssistantTranscript() {
        if (chatMode != ChatMode.ASSISTANT) return
        val transcript = JSONArray()
        messages.forEach { message ->
            transcript.put(JSONObject().apply {
                put("id", message.id)
                put("content", message.content)
                put("isUser", message.isUser)
                put("imageUri", message.imageUri)
            })
        }
        assistantTranscriptPreferences().edit().putString(KEY_ASSISTANT_TRANSCRIPT, transcript.toString()).apply()
    }

    private fun sendLocalAssistantMessage(content: String) {
        if (!isModelReady) {
            Toast.makeText(this, "Choose a local GGUF model first.", Toast.LENGTH_LONG).show()
            showModelChooserOrPicker()
            return
        }
        val prompt = content.ifBlank { "Please look at this image." }
        val imageUri = selectedImageUri
        val priorMessages = messages.toList()
        userInputEt.setText("")
        val userMessage = Message(UUID.randomUUID().toString(), prompt, true, imageUri?.toString())
        val pendingId = UUID.randomUUID().toString()
        messages += userMessage
        messages += Message(pendingId, "", false)
        clearAttachedImage()
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        messagesRv.scrollToPosition(messages.lastIndex)
        saveAssistantTranscript()
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        val historyPrompt = buildPromptWithHistory(priorMessages, prompt)
        val promptToSend = buildPromptWithProfile(historyPrompt, prompt)
        lastAssistantMsg.clear()
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val responseFlow = if (imageUri != null) {
                    if (!engine.isMmprojLoaded()) throw IOException("No mmproj loaded. Use + -> Load mmproj to enable vision.")
                    val decoded = decodeImageToRgb(imageUri) ?: throw IOException("Could not decode image.")
                    engine.sendUserPromptWithImage(promptToSend, decoded.first, decoded.second, decoded.third)
                } else {
                    engine.sendUserPrompt(promptToSend)
                }
                responseFlow.collect { token ->
                    withContext(Dispatchers.Main) {
                        val index = messages.indexOfFirst { it.id == pendingId }
                        if (index < 0) return@withContext
                        messages[index] = messages[index].copy(content = lastAssistantMsg.append(token).toString())
                        messageAdapter.notifyItemChanged(index)
                        messagesRv.scrollToPosition(index)
                        saveAssistantTranscript()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Local model request in My Assistant failed", error)
                withContext(Dispatchers.Main) {
                    val index = messages.indexOfFirst { it.id == pendingId }
                    if (index >= 0) {
                        messages[index] = messages[index].copy(content = error.message ?: "Local model could not answer.")
                        messageAdapter.notifyItemChanged(index)
                        saveAssistantTranscript()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                }
            }
        }
    }

    private fun confirmDeleteChatSession(session: ChatSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Chat")
            .setMessage("Delete \"${session.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val wasActive = currentSession?.id == session.id
                chatSessions.remove(session)
                if (chatSessions.isEmpty()) {
                    val defaultSession = ChatSession(
                        id = UUID.randomUUID().toString(),
                        title = "New Chat",
                        timestamp = System.currentTimeMillis()
                    )
                    chatSessions.add(defaultSession)
                }
                if (wasActive) {
                    currentSession = chatSessions.first()
                    messages.clear()
                    messages.addAll(currentSession!!.messages)
                    messageAdapter.notifyDataSetChanged()
                    if (messages.isNotEmpty()) {
                        messagesRv.scrollToPosition(messages.size - 1)
                        isSessionRestored = true
                    } else {
                        isSessionRestored = false
                    }
                }
                recentChatAdapter.notifyDataSetChanged()
                ChatHistoryStorage.saveSessions(this, chatSessions)
                Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAllChats() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All Chats")
            .setMessage("Are you sure you want to delete all chat history?")
            .setPositiveButton("Clear All") { _, _ ->
                generationJob?.cancel()
                chatSessions.clear()
                val defaultSession = ChatSession(
                    id = UUID.randomUUID().toString(),
                    title = "New Chat",
                    timestamp = System.currentTimeMillis()
                )
                chatSessions.add(defaultSession)
                currentSession = defaultSession
                messages.clear()
                messageAdapter.notifyDataSetChanged()
                recentChatAdapter.notifyDataSetChanged()
                isSessionRestored = false
                ChatHistoryStorage.clearAll(this)
                drawerLayout.closeDrawer(GravityCompat.START)
                Toast.makeText(this, "All chat history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onImageSelected(it) }
    }

    private val pickOcrImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { runOcrOnImage(it) }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onDocumentSelected(it) }
    }

    private val pickMmprojLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedMmproj(it) }
    }

    private fun showAttachmentOptionsDialog() {
        val mmprojLabel = if (engine.isMmprojLoaded()) "Reload mmproj (Vision - loaded)" else "Load mmproj (Vision)"
        val imageOption = if (chatMode == ChatMode.ASSISTANT) "Attach Image to My Assistant" else "Upload Image to VLM"
        val options = arrayOf("Run on-device OCR", imageOption, "Upload Document / File", "Import GGUF Model", mmprojLabel)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Attachment")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickOcrImageLauncher.launch("image/*")
                    1 -> pickImageLauncher.launch("image/*")
                    2 -> pickFileLauncher.launch("*/*")
                    3 -> getContent.launch(arrayOf("*/*"))
                    4 -> pickMmprojLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun runOcrOnImage(uri: Uri) {
        Toast.makeText(this, "Reading text on this device...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val decodedBitmap = withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
                bitmap = decodedBitmap
                val recognizer = ocrRecognizer ?: TextRecognizer().also { ocrRecognizer = it }
                val result = recognizer.recognize(decodedBitmap)
                showOcrResult(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "On-device OCR failed", error)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("OCR failed")
                    .setMessage(error.message ?: "The selected image could not be read.")
                    .setPositiveButton("OK", null)
                    .show()
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun showOcrResult(result: OcrResult) {
        val timetable = try {
            TimetableParser().parse(result)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Timetable parsing failed", error)
            null
        }
        val previewLines = result.fullText.lineSequence().filter { it.isNotBlank() }.take(8).toList()
        val summary = buildString {
            append("Image: ${result.imageWidth} x ${result.imageHeight}\n")
            append("Detected ${result.blocks.size} blocks, ${result.lines.size} lines, and ${result.elements.size} text elements.")
            if (timetable != null) {
                append("\nTimetable layout: ${timetable.layoutName}")
                append("\nStructured entries: ${timetable.entries.size}")
                append("\nSpatial assignments: ${timetable.assignments.size}")
                append("\n\nReview low-confidence cells in the assignment debug view.")
            } else {
                append("\nTimetable could not be parsed from this image.")
            }
            if (result.fullText.isBlank()) {
                append("\nNo text was detected.")
            } else if (timetable == null && previewLines.isNotEmpty()) {
                append("\n\nPreview:\n")
                append(previewLines.joinToString("\n") { it.take(90) })
                if (result.fullText.lineSequence().count { it.isNotBlank() } > previewLines.size) append("\n...")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Timetable parsing complete")
            .setMessage(summary)
            .setPositiveButton("Entries JSON") { _, _ ->
                timetable?.let { showOcrDetailDialog("Structured timetable JSON", TimetableDebugFormatter.formatJson(it)) }
            }
            .setNeutralButton("Assignment debug") { _, _ ->
                timetable?.let { showOcrDetailDialog("Timetable assignment debug", TimetableDebugFormatter.formatAssignments(it)) }
            }
            .setNegativeButton("OCR details") { _, _ -> showOcrDetailsOptions(result) }
            .show()
    }

    private fun showOcrDetailsOptions(result: OcrResult) {
        MaterialAlertDialogBuilder(this)
            .setTitle("OCR details")
            .setItems(arrayOf("Full text", "Bounding boxes")) { _, which ->
                if (which == 0) {
                    showOcrDetailDialog("Full OCR text", result.fullText.ifBlank { "No text was detected." })
                } else {
                    showOcrDetailDialog("OCR bounding boxes", formatOcrBoundingBoxes(result))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun formatOcrBoundingBoxes(result: OcrResult): String = buildString {
        append("Image ${result.imageWidth} x ${result.imageHeight}\n")
        append("Coordinates: left,top,right,bottom. ML Kit order; not timetable order.\n\n")
        if (result.lines.isEmpty()) append("No text elements were detected.\n")
        result.lines.forEach { line ->
            append("Line[${line.left},${line.top},${line.right},${line.bottom}]: ")
            append(line.elements.joinToString(" ") { element ->
                "${element.text}[${element.left},${element.top},${element.right},${element.bottom}]"
            })
            append('\n')
        }
    }

    private fun showOcrDetailDialog(title: String, content: String) {
        val textView = TextView(this).apply {
            text = content
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.65f).toInt())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun onImageSelected(uri: Uri) {
        selectedImageUri = uri
        imagePreviewIv.setImageURI(uri)
        imagePreviewContainer.visibility = android.view.View.VISIBLE
    }

    private fun prepareAssistantAttachment(uri: Uri): AssistantAttachment {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val maxSide = 2048
                if (maxOf(width, height) > maxSide) {
                    val scale = maxSide.toFloat() / maxOf(width, height)
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (error: Exception) {
            throw AssistantAttachmentException("Could not read the selected image.", error)
        }
        val bytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                bitmap.recycle()
                throw AssistantAttachmentException("Could not encode the selected image.")
            }
            bitmap.recycle()
            output.toByteArray()
        }
        if (bytes.isEmpty() || bytes.size > MAX_ASSISTANT_IMAGE_BYTES) {
            throw AssistantAttachmentException("That image is too large to upload. Choose a smaller image.")
        }
        val originalName = getFileInfo(uri).first.orEmpty().substringBeforeLast('.', "image")
            .ifBlank { "image" }
        return AssistantAttachment("$originalName.jpg", "image/jpeg", bytes)
    }

    private class AssistantAttachmentException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun clearAttachedImage() {
        selectedImageUri = null
        imagePreviewContainer.visibility = android.view.View.GONE
    }

    /**
     * Decodes the attached image URI into a flat RGB byte array for the VLM.
     * Returns null if decoding fails.
     */
    private fun decodeImageToRgb(uri: Uri): Triple<ByteArray, Int, Int>? {
        return try {
            val bmp = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null
            // Keep enough detail for text in screenshots while limiting image memory use.
            val maxSide = 1024
            val scaled = if (bmp.width > maxSide || bmp.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgb[i * 3]     = ((p shr 16) and 0xFF).toByte()
                rgb[i * 3 + 1] = ((p shr 8)  and 0xFF).toByte()
                rgb[i * 3 + 2] = (p           and 0xFF).toByte()
            }
            Triple(rgb, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image", e)
            null
        }
    }

    /**
     * Handles importing a mmproj GGUF file (visual encoder for VLMs).
     */
    private fun handleSelectedMmproj(uri: Uri) {
        if (!isModelReady) {
            Toast.makeText(this, "Load a text model first before adding mmproj.", Toast.LENGTH_SHORT).show()
            return
        }
        val (fileName, expectedSize) = getFileInfo(uri)
        val mmprojName = fileName ?: "mmproj.gguf"
        val displayName = mmprojName.removeSuffix(FILE_EXTENSION_GGUF)

        Toast.makeText(this, "Copying $displayName...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mmprojFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(mmprojName, input, expectedSize)
                } ?: throw IOException("Cannot open mmproj stream")

                val ok = engine.loadMmproj(mmprojFile.absolutePath)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity,
                            "$displayName loaded - vision enabled!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity,
                            "Failed to load mmproj (check logcat)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "mmproj error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onDocumentSelected(uri: Uri) {
        val (fileName, fileSize) = getFileInfo(uri)
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                if (fileSize in 1..30000) {
                    val content = stream.bufferedReader().use { it.readText() }
                    val currentText = userInputEt.text?.toString().orEmpty()
                    val newText = if (currentText.isBlank()) content else "$currentText\n\n$content"
                    userInputEt.setText(newText)
                    userInputEt.setSelection(newText.length)
                    Toast.makeText(this, "Loaded ${fileName ?: "file"}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Attached ${fileName ?: "file"}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Helper to retrieve display name and size from content Uri
     */
    private fun getFileInfo(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size: Long = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query file info from uri: $uri", e)
        }
        return Pair(name, size)
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        // Update UI states
        userActionFab.isEnabled = false
        userInputEt.hint = "Parsing GGUF..."
        setLocalModelLabel("Loading...")
        setLocalModelStatus("Parsing metadata from selected file \n$uri")

        lifecycleScope.launch(Dispatchers.IO) {
            var metadataSummary: String? = null
            try {
                // Parse GGUF metadata
                Log.i(TAG, "Parsing GGUF metadata...")
                val metadata = contentResolver.openInputStream(uri)?.use { stream ->
                    GgufMetadataReader.create().readStructuredMetadata(stream)
                } ?: throw IOException("Could not open input stream for selected file.")

                metadataSummary = "Model: ${metadata.basic.name ?: "Unknown"}\n" +
                        "Architecture: ${metadata.architecture?.architecture ?: "Unknown"}\n" +
                        "Version: ${metadata.version}\n" +
                        "Tensors: ${metadata.tensorCount}, KV pairs: ${metadata.kvCount}"

                // Update UI to show GGUF metadata to user
                Log.i(TAG, "GGUF parsed: \n$metadata")
                withContext(Dispatchers.Main) {
                    setLocalModelStatus(metadataSummary.orEmpty())
                }

                // Ensure the model file is available
                val (originalName, expectedSize) = getFileInfo(uri)
                val baseName = originalName ?: (metadata.filename() + FILE_EXTENSION_GGUF)
                val modelName = if (baseName.endsWith(FILE_EXTENSION_GGUF, ignoreCase = true)) baseName else "$baseName$FILE_EXTENSION_GGUF"
                val modelFile = contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input, expectedSize)
                } ?: throw IOException("Could not open input stream to copy model.")

                loadModel(modelName, modelFile)

                withContext(Dispatchers.Main) {
                    isModelReady = true
                    userInputEt.hint = "Type and send a message!"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    setLocalModelLabel(modelName.removeSuffix(FILE_EXTENSION_GGUF))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling selected model from $uri", e)
                withContext(Dispatchers.Main) {
                    val errorDetail = when (e) {
                        is com.arm.aichat.gguf.InvalidFileFormatException ->
                            "Selected file is not a valid GGUF model file."
                        is com.arm.aichat.UnsupportedArchitectureException ->
                            "Model architecture or tensor format is not supported by llama.cpp."
                        is IllegalStateException ->
                            e.message ?: "Illegal state error"
                        is IOException ->
                            "IO / Storage Error: ${e.localizedMessage ?: e.message}"
                        is OutOfMemoryError ->
                            "Out of memory while copying or loading model."
                        else ->
                            "Error: ${e.localizedMessage ?: e.message}"
                    }
                    Toast.makeText(this@MainActivity, errorDetail, Toast.LENGTH_LONG).show()
                    val fullText = if (metadataSummary != null) {
                        "$metadataSummary\n\n$errorDetail"
                    } else {
                        errorDetail
                    }
                    setLocalModelStatus(fullText)
                    userInputEt.hint = "Select a GGUF model file"
                    userActionFab.isEnabled = true
                    setLocalModelLabel("Select Model")
                }
            }
        }
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream, expectedSize: Long = -1L): File =
        withContext(Dispatchers.IO) {
            val modelsDir = ensureModelsDirectory()
            val file = File(modelsDir, modelName)
            val tempFile = File(modelsDir, "$modelName.tmp")

            val isFileValid = file.exists() && file.length() > 0L && (expectedSize <= 0L || file.length() == expectedSize)

            if (!isFileValid) {
                Log.i(TAG, "Start copying file to $modelName (expected size: $expectedSize, existing: ${file.length()})")
                withContext(Dispatchers.Main) {
                    userInputEt.hint = "Copying file..."
                }

                try {
                    if (tempFile.exists()) tempFile.delete()
                    if (file.exists()) file.delete()
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    if (tempFile.length() == 0L) {
                        tempFile.delete()
                        throw IOException("Failed to copy file: 0 bytes copied")
                    }
                    if (expectedSize > 0L && tempFile.length() != expectedSize) {
                        val copied = tempFile.length()
                        tempFile.delete()
                        throw IOException("Incomplete file copy: copied $copied bytes, expected $expectedSize bytes")
                    }
                    if (!tempFile.renameTo(file)) {
                        tempFile.copyTo(file, overwrite = true)
                        tempFile.delete()
                    }
                    Log.i(TAG, "Finished copying file to $modelName (${file.length()} bytes)")
                } catch (e: Exception) {
                    if (tempFile.exists()) tempFile.delete()
                    if (file.exists()) file.delete()
                    throw e
                }
            } else {
                Log.i(TAG, "File already exists and valid $modelName (${file.length()} bytes)")
            }
            file
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            generationJob?.cancel()
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Loading model..."
                setLocalModelLabel("Loading...")
            }
            engine.loadModel(modelFile.path)
            modelPreferences.edit().putString(PREF_SELECTED_MODEL, modelName).apply()
        }

    private fun showModelChooserOrPicker() {
        val storedModels = getStoredModelFiles()
        if (storedModels.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No Saved Models")
                .setMessage("No saved models found in app storage. Would you like to import a GGUF model?")
                .setPositiveButton("Import") { _, _ ->
                    getContent.launch(arrayOf("*/*"))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val currentModel = modelPreferences.getString(PREF_SELECTED_MODEL, null)
        val displayNames = storedModels.map { file ->
            val cleanName = file.name.removeSuffix(FILE_EXTENSION_GGUF)
            if (file.name == currentModel && isModelReady) {
                "$cleanName (Active)"
            } else {
                cleanName
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Saved Models")
            .setItems(displayNames) { _, which ->
                val selectedFile = storedModels[which]
                showModelActionDialog(selectedFile)
            }
            .setPositiveButton("Import New") { _, _ ->
                getContent.launch(arrayOf("*/*"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showModelActionDialog(modelFile: File) {
        val cleanName = modelFile.name.removeSuffix(FILE_EXTENSION_GGUF)
        val options = arrayOf("Load Model", "Delete Model")
        MaterialAlertDialogBuilder(this)
            .setTitle(cleanName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadSelectedModelFile(modelFile)
                    1 -> confirmDeleteModel(modelFile)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showModelChooserOrPicker()
            }
            .show()
    }

    private fun loadSelectedModelFile(selectedFile: File) {
        val currentModel = modelPreferences.getString(PREF_SELECTED_MODEL, null)
        if (isModelReady && selectedFile.name == currentModel) {
            Toast.makeText(this, "${selectedFile.name.removeSuffix(FILE_EXTENSION_GGUF)} is already active", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                userActionFab.isEnabled = false
                btnModelSwap.isEnabled = false
                setLocalModelLabel("Loading...")
                userInputEt.hint = "Loading model..."
            }
            try {
                loadModel(selectedFile.name, selectedFile)
                withContext(Dispatchers.Main) {
                    isModelReady = true
                    userInputEt.hint = "Type and send a message!"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    btnModelSwap.isEnabled = true
                    setLocalModelLabel(selectedFile.name.removeSuffix(FILE_EXTENSION_GGUF))
                    setLocalModelStatus("Loaded model: ${selectedFile.name}")
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    userActionFab.isEnabled = true
                    btnModelSwap.isEnabled = true
                    setLocalModelLabel(currentModel?.removeSuffix(FILE_EXTENSION_GGUF) ?: "Select Model")
                    Toast.makeText(this@MainActivity, "Failed to load ${selectedFile.name}: ${e.message}", Toast.LENGTH_LONG).show()
                    setLocalModelStatus("Failed to load model: ${selectedFile.name}\n${e.message}")
                }
            }
        }
    }

    private fun confirmDeleteModel(modelFile: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Model")
            .setMessage("Delete ${modelFile.name} from app storage?")
            .setPositiveButton("Delete") { _, _ ->
                val currentModel = modelPreferences.getString(PREF_SELECTED_MODEL, null)
                val isCurrent = modelFile.name == currentModel
                if (modelFile.delete()) {
                    Toast.makeText(this, "Model deleted", Toast.LENGTH_SHORT).show()
                    if (isCurrent) {
                        modelPreferences.edit().remove(PREF_SELECTED_MODEL).apply()
                        isModelReady = false
                        userInputEt.text = null
                        userInputEt.isEnabled = false
                        userInputEt.hint = "Please select or import a GGUF model."
                        userActionFab.setImageResource(R.drawable.outline_folder_open_24)
                        setLocalModelLabel("Select Model")
                        setLocalModelStatus("Model deleted. Please select or import a model.")
                    }
                } else {
                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                }
                showModelChooserOrPicker()
            }
            .setNegativeButton("Cancel") { _, _ ->
                showModelChooserOrPicker()
            }
            .show()
    }

    private fun getStoredModelFiles(): List<File> = MainActivityModelStorage.getStoredModelFiles(ensureModelsDirectory())

    private suspend fun loadLastSelectedModelIfAny() {
        val selectedModel = modelPreferences.getString(PREF_SELECTED_MODEL, null) ?: return
        val modelFile = File(ensureModelsDirectory(), selectedModel)
        if (!modelFile.exists() || !modelFile.isFile) {
            modelPreferences.edit().remove(PREF_SELECTED_MODEL).apply()
            return
        }

        try {
            loadModel(selectedModel, modelFile)
            withContext(Dispatchers.Main) {
                isModelReady = true
                userInputEt.hint = "Type and send a message!"
                userInputEt.isEnabled = true
                userActionFab.setImageResource(R.drawable.outline_send_24)
                userActionFab.isEnabled = true
                setLocalModelLabel(modelFile.name.removeSuffix(FILE_EXTENSION_GGUF))
                setLocalModelStatus("Loaded model: ${modelFile.name}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to restore model $selectedModel", e)
            withContext(Dispatchers.Main) {
                setLocalModelStatus("Stored model not available anymore: $selectedModel\n${e.message}")
                userInputEt.hint = "Select a GGUF model file"
                userActionFab.isEnabled = true
                setLocalModelLabel("Select Model")
            }
        }
    }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        if (chatMode == ChatMode.ASSISTANT) {
            val assistantPrompt = userInputEt.text.toString()
            if (handleAssistantAlarmCommand(assistantPrompt)) return
            if (assistantPrompt.isBlank() && selectedImageUri == null) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                profileAnswerFor(assistantPrompt)?.let { answer ->
                    userInputEt.setText("")
                    messages += Message(UUID.randomUUID().toString(), assistantPrompt, true, selectedImageUri?.toString())
                    messages += Message(UUID.randomUUID().toString(), answer, false)
                    clearAttachedImage()
                    saveAssistantTranscript()
                    messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                    messagesRv.scrollToPosition(messages.lastIndex)
                    return
                }
                if (assistantUsesLocalModel) sendLocalAssistantMessage(assistantPrompt) else sendAssistantMessage(assistantPrompt)
            }
            return
        }
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // If session title is default, name it after the first prompt
                currentSession?.let { session ->
                    if (session.title == "New Chat" && session.messages.isEmpty()) {
                        session.title = if (userMsg.length > 28) userMsg.take(28) + "..." else userMsg
                        recentChatAdapter.notifyDataSetChanged()
                    }
                }

                val basePrompt = if (isSessionRestored && messages.isNotEmpty()) {
                    buildPromptWithHistory(messages, userMsg)
                } else {
                    userMsg
                }
                val promptToSend = buildPromptWithProfile(basePrompt, userMsg)
                isSessionRestored = false

                // Snapshot image before clearing the preview
                val imageUriSnapshot = selectedImageUri
                val attachedImageStr = imageUriSnapshot?.toString()
                val userMessage = Message(UUID.randomUUID().toString(), userMsg, true, imageUri = attachedImageStr)
                messages.add(userMessage)
                currentSession?.messages?.add(userMessage)
                clearAttachedImage()

                lastAssistantMsg.clear()
                val assistantMessage = Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false)
                messages.add(assistantMessage)
                currentSession?.messages?.add(assistantMessage)

                messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                messagesRv.scrollToPosition(messages.size - 1)
                ChatHistoryStorage.saveSessions(this, chatSessions)

                when (val command = AlarmSlashCommandParser.parse(userMsg)) {
                    AlarmSlashCommandParser.Result.NotCommand -> Unit
                    is AlarmSlashCommandParser.Result.Invalid -> {
                        showToolResponse(command.message)
                        userInputEt.isEnabled = true
                        userActionFab.isEnabled = true
                        return@also
                    }
                    is AlarmSlashCommandParser.Result.Command -> {
                        pendingToolCommand = command.value
                        if (command.value is ToolCommand.SetAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            routeToolCommand(command.value)
                        }
                        userInputEt.isEnabled = true
                        userActionFab.isEnabled = true
                        return@also
                    }
                }

                profileAnswerFor(userMsg)?.let { answer ->
                    showToolResponse(answer)
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                    return@also
                }

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    val responseFlow = if (imageUriSnapshot != null) {
                        if (!engine.isMmprojLoaded()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity,
                                    "No mmproj loaded. Use + -> Load mmproj to enable vision.",
                                    Toast.LENGTH_LONG).show()
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                            return@launch
                        }
                        val decoded = decodeImageToRgb(imageUriSnapshot)
                        if (decoded == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity,
                                    "Could not decode image.", Toast.LENGTH_SHORT).show()
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                            return@launch
                        }
                        val (rgb, w, h) = decoded
                        engine.sendUserPromptWithImage(promptToSend, rgb, w, h)
                    } else {
                        engine.sendUserPrompt(promptToSend)
                    }

                    responseFlow
                        .onCompletion { cause ->
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                                ChatHistoryStorage.saveSessions(this@MainActivity, chatSessions)
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                val updatedMsg = messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                )
                                messages.add(updatedMsg)

                                currentSession?.messages?.let { sessMsgs ->
                                    if (sessMsgs.isNotEmpty() && !sessMsgs.last().isUser) {
                                        sessMsgs[sessMsgs.size - 1] = updatedMsg
                                    }
                                }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                                messagesRv.scrollToPosition(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    private fun handleAssistantAlarmCommand(input: String): Boolean {
        when (val parsed = AlarmSlashCommandParser.parse(input)) {
            AlarmSlashCommandParser.Result.NotCommand -> return false
            is AlarmSlashCommandParser.Result.Invalid -> {
                userInputEt.setText("")
                messages += Message(UUID.randomUUID().toString(), input, true)
                messages += Message(UUID.randomUUID().toString(), parsed.message, false)
                saveAssistantTranscript()
                messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                messagesRv.scrollToPosition(messages.lastIndex)
            }
            is AlarmSlashCommandParser.Result.Command -> {
                userInputEt.setText("")
                messages += Message(UUID.randomUUID().toString(), input, true)
                messages += Message(UUID.randomUUID().toString(), "", false)
                saveAssistantTranscript()
                messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                messagesRv.scrollToPosition(messages.lastIndex)
                pendingToolCommand = parsed.value
                if (parsed.value is ToolCommand.SetAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    routeToolCommand(parsed.value)
                }
            }
        }
        return true
    }

    private fun buildPromptWithHistory(history: List<Message>, currentPrompt: String): String {
        val nonBlank = history.filter { it.content.isNotBlank() }
        if (nonBlank.isEmpty()) return currentPrompt
        val recent = nonBlank.takeLast(10)
        val sb = StringBuilder()
        sb.append("Previous conversation:\n")
        for (msg in recent) {
            val role = if (msg.isUser) "User" else "Assistant"
            sb.append("$role: ${msg.content.trim()}\n")
        }
        sb.append("\nUser: $currentPrompt")
        return sb.toString()
    }

    private fun buildPromptWithProfile(prompt: String, userMessage: String): String {
        if (!isProfileQuestion(userMessage)) return prompt
        val bio = profilePreferences.getString(PROFILE_BIO, "").orEmpty().trim()
        if (bio.isEmpty()) return prompt
        return "The following is the user's saved profile. Use only details stated here, keep names of people and schools exactly as written, and do not guess or expand abbreviations. Use it only when it helps answer the user's question.\n<profile>\n$bio\n</profile>\n\n$prompt"
    }

    private fun isProfileQuestion(message: String): Boolean = Regex(
        "(?i)\\b(who am i|what do you know about me|tell me about myself|about me|my name|my college|my university|my school|my institute|my education|my studies|my course|my degree|my profile|my bio|my background|introduce me)\\b"
    ).containsMatchIn(message)

    private fun profileAnswerFor(question: String): String? {
        val bio = profilePreferences.getString(PROFILE_BIO, "").orEmpty()
        if (Regex("(?i)\\b(what(?:'s| is) my name|who am i|do you know my name)\\b").containsMatchIn(question)) {
            val name = Regex("(?i)\\bmy name is\\s+([^,.;\\n]+)").find(bio)?.groupValues?.get(1)?.trim()
            return if (name.isNullOrBlank()) "Your profile bio does not include your name yet."
            else "Your profile lists your name as \"$name\"."
        }
        if (Regex("(?i)\\b(what(?:'s| is) my (?:college|university|school|institute)|where do i study|which (?:college|university) do i attend)\\b").containsMatchIn(question)) {
            val institution = Regex("(?i)\\b(?:student at|study at|college is|university is|institute is)\\s+([^,.;\\n]+)")
                .find(bio)?.groupValues?.get(1)?.trim()
            return if (institution.isNullOrBlank()) "Your profile bio does not include your college or university yet."
            else "Your profile lists your college as \"$institution\"."
        }
        return null
    }

    private fun showUserProfileDialog() {
        val bioEditor = EditText(this).apply {
            hint = "Write a short bio about yourself"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 8
            filters = arrayOf(InputFilter.LengthFilter(1000))
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(profilePreferences.getString(PROFILE_BIO, ""))
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Your profile")
            .setMessage("This bio stays on this device and is added as context when either model replies. Up to 1000 characters.")
            .setView(bioEditor)
            .setPositiveButton("Save") { _, _ ->
                profilePreferences.edit().putString(PROFILE_BIO, bioEditor.text.toString().trim()).apply()
                Toast.makeText(this, "Profile bio saved on this device", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Clear") { _, _ ->
                profilePreferences.edit().remove(PROFILE_BIO).apply()
                bioEditor.text.clear()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun routeToolCommand(command: ToolCommand) {
        when (val result = toolRouter.route(command)) {
            AlarmScheduler.Result.NeedsExactAlarmPermission -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Allow exact alarms")
                    .setMessage("Android needs Alarms & reminders access to ring at the requested time.")
                    .setPositiveButton("Open settings") { _, _ ->
                        awaitingExactAlarmSettings = true
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
                                    Uri.parse("package:$packageName")
                                )
                            )
                        } catch (_: Exception) {
                            awaitingExactAlarmSettings = false
                            finishToolRouting(command, false)
                        }
                    }
                    .setNegativeButton("Use approximate time") { _, _ -> finishToolRouting(command, false) }
                    .setOnCancelListener { finishToolRouting(command, false) }
                    .show()
            }
            else -> presentToolResult(command, result)
        }
    }

    private fun finishToolRouting(command: ToolCommand, exactAccess: Boolean) {
        val result = toolRouter.route(command, exactAccess)
        presentToolResult(command, result)
    }

    private fun presentToolResult(command: ToolCommand, result: AlarmScheduler.Result) {
        val message = result.message(command) + if (notificationPermissionDenied && command is ToolCommand.SetAlarm) {
            " Enable notifications to see the alarm alert."
        } else ""
        showToolResponse(message)
        pendingToolCommand = null
        notificationPermissionDenied = false
    }

    private fun AlarmScheduler.Result.message(command: ToolCommand): String = when (this) {
        is AlarmScheduler.Result.Scheduled -> when (command) {
            is ToolCommand.SetAlarm -> "${command.label} alarm set for ${command.time}${if (command.repeat == ToolCommand.Repeat.DAILY) " daily" else ""}." +
                if (exact) "" else " Android may deliver it later."
            is ToolCommand.CancelAlarm -> "Alarm command completed."
        }
        AlarmScheduler.Result.Cancelled -> "Alarm cancelled."
        AlarmScheduler.Result.NotFound -> "No matching alarm was found."
        AlarmScheduler.Result.NeedsExactAlarmPermission -> "Exact alarm access is required."
        AlarmScheduler.Result.InvalidTime -> "That alarm time is no longer in the future. No alarm was scheduled."
    }

    private fun showToolResponse(text: String) {
        val index = messages.lastIndex
        if (index < 0 || messages[index].isUser) return
        val updated = messages[index].copy(content = text)
        messages[index] = updated
        if (chatMode == ChatMode.LOCAL) {
            currentSession?.messages?.let { history ->
                if (history.isNotEmpty() && !history.last().isUser) history[history.lastIndex] = updated
            }
            ChatHistoryStorage.saveSessions(this, chatSessions)
        } else {
            saveAssistantTranscript()
        }
        messageAdapter.notifyItemChanged(index)
        messagesRv.scrollToPosition(index)
    }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        ChatHistoryStorage.saveSessions(this, chatSessions)
        super.onStop()
    }

    override fun onDestroy() {
        ocrRecognizer?.close()
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
        private const val PREFS_MODEL = "gguf_models"
        private const val PREF_SELECTED_MODEL = "selected_model"
        private const val PREFS_PROFILE = "user_profile"
        private const val PROFILE_BIO = "bio"
        private const val PREFS_ASSISTANT_THREAD = "assistant_thread"
        private const val KEY_ASSISTANT_TRANSCRIPT = "my_assistant_transcript"
        private const val KEY_ASSISTANT_LOCAL_PROVIDER = "my_assistant_local_provider"
        private const val MAX_ASSISTANT_IMAGE_BYTES = 15 * 1024 * 1024
    }
}

internal object MainActivityModelStorage {
    @JvmStatic
    fun getStoredModelFiles(modelsDir: File): List<File> =
        modelsDir.takeIf { it.exists() && it.isDirectory }
            ?.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
}

internal object ChatHistoryStorage {
    private const val CHAT_SESSIONS_FILE = "chat_sessions.json"
    private const val TAG = "ChatHistoryStorage"

    fun saveSessions(context: android.content.Context, sessions: List<ChatSession>) {
        try {
            val jsonArray = JSONArray()
            for (session in sessions) {
                val sObj = JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("timestamp", session.timestamp)
                    val msgArray = JSONArray()
                    for (msg in session.messages) {
                        if (msg.content.isNotBlank()) {
                            val mObj = JSONObject().apply {
                                put("id", msg.id)
                                put("content", msg.content)
                                put("isUser", msg.isUser)
                                msg.imageUri?.let { put("imageUri", it) }
                            }
                            msgArray.put(mObj)
                        }
                    }
                    put("messages", msgArray)
                }
                jsonArray.put(sObj)
            }
            val file = File(context.filesDir, CHAT_SESSIONS_FILE)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat sessions", e)
        }
    }

    fun loadSessions(context: android.content.Context): MutableList<ChatSession> {
        val file = File(context.filesDir, CHAT_SESSIONS_FILE)
        if (!file.exists()) return mutableListOf()
        return try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val result = mutableListOf<ChatSession>()
            for (i in 0 until jsonArray.length()) {
                val sObj = jsonArray.getJSONObject(i)
                val id = sObj.optString("id", UUID.randomUUID().toString())
                val title = sObj.optString("title", "Chat")
                val timestamp = sObj.optLong("timestamp", System.currentTimeMillis())
                val msgArray = sObj.optJSONArray("messages") ?: JSONArray()
                val msgs = mutableListOf<Message>()
                for (j in 0 until msgArray.length()) {
                    val mObj = msgArray.getJSONObject(j)
                    msgs.add(
                        Message(
                            id = mObj.optString("id", UUID.randomUUID().toString()),
                            content = mObj.optString("content", ""),
                            isUser = mObj.optBoolean("isUser", false),
                            imageUri = mObj.optString("imageUri").takeIf { it.isNotBlank() }
                        )
                    )
                }
                result.add(ChatSession(id, title, timestamp, msgs))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat sessions", e)
            mutableListOf()
        }
    }

    fun clearAll(context: android.content.Context): Boolean {
        val file = File(context.filesDir, CHAT_SESSIONS_FILE)
        return if (file.exists()) file.delete() else true
    }
}

fun GgufMetadata.filename(): String {
    val rawName = when {
        basic.name != null -> {
            basic.name?.let { name ->
                basic.sizeLabel?.let { size ->
                    "$name-$size"
                } ?: name
            }
        }
        architecture?.architecture != null -> {
            architecture?.architecture?.let { arch ->
                basic.uuid?.let { uuid ->
                    "$arch-$uuid"
                } ?: "$arch-${System.currentTimeMillis()}"
            }
        }
        else -> {
            "model-${System.currentTimeMillis()}"
        }
    } ?: "model-${System.currentTimeMillis()}"

    return rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
