package com.revclip.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var videoUri: Uri? = null
    private var lastExportUri: Uri? = null
    private val clipSuggestions = mutableListOf<ClipSuggestion>()

    private lateinit var selectedVideo: TextView
    private lateinit var status: TextView
    private lateinit var transcript: EditText
    private lateinit var transcriptWarning: TextView
    private lateinit var apiKey: EditText
    private lateinit var modelField: EditText
    private lateinit var suggestionsContainer: LinearLayout
    private lateinit var startSeconds: EditText
    private lateinit var endSeconds: EditText
    private lateinit var provider: Spinner
    private lateinit var shareButton: Button
    private lateinit var openButton: Button

    private val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers do not grant persistable access.
                // Keep the URI for the current session rather than crashing.
            } catch (_: IllegalArgumentException) {
                // Provider-specific URI implementations may reject persistence.
            }
            videoUri = uri
            selectedVideo.text = "Video dipilih: ${uri.lastPathSegment ?: "media"}"
            status.text = "Video siap dianalisis atau dipotong."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
            setBackgroundColor(0xFF111820.toInt())
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                dp(24) + bars.left,
                dp(28) + bars.top,
                dp(24) + bars.right,
                dp(32) + bars.bottom
            )
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(root)

        root.addView(label("RevoClip", 30, 0xFFFFFFFF.toInt()))
        root.addView(label("AI Auto Clipping  •  Video Editor", 14, 0xFF9CB7AC.toInt()))
        root.addView(space())
        root.addView(label("1. Import video panjang", 18))
        root.addView(button("Pilih video") { picker.launch(arrayOf("video/*")) })
        selectedVideo = label("Belum ada video dipilih", 13, 0xFFB9C4CE.toInt())
        root.addView(selectedVideo)

        root.addView(space())
        root.addView(label("2. AI cari momen terbaik", 18))
        root.addView(
            label(
                "Tempel transkrip yang sudah memiliki timestamp. AI akan mengusulkan klip dalam JSON terstruktur. Speech-to-text otomatis belum tersedia.",
                13,
                0xFFB9C4CE.toInt()
            )
        )
        provider = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenRouter", "OpenAI", "Claude", "DeepSeek")
            )
        }
        root.addView(provider)
        modelField = edit("Model ID (bisa diubah)", value = defaultModel("OpenRouter"))
        root.addView(modelField)
        apiKey = edit("API key provider (tidak disimpan)", password = true)
        root.addView(apiKey)
        transcript = edit("Tempel transkrip + timestamp di sini", multiline = true)
        root.addView(transcript)
        transcriptWarning = label("", 12, 0xFFFFC47A.toInt())
        root.addView(transcriptWarning)
        root.addView(button("Analisis transkrip dengan AI") { analyzeTranscript() })
        suggestionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(suggestionsContainer)

        root.addView(space())
        root.addView(label("3. Potong & ekspor MP4", 18))
        root.addView(
            label(
                "Masukkan detik desimal (12.5) atau menit:detik (02:15.5). Ekspor dilakukan lokal melalui Media3 Transformer.",
                13,
                0xFFB9C4CE.toInt()
            )
        )
        startSeconds = edit("Mulai (detik atau mm:ss)", value = "0", decimal = true)
        endSeconds = edit("Selesai (detik atau mm:ss)", value = "30", decimal = true)
        root.addView(startSeconds)
        root.addView(endSeconds)
        root.addView(button("Masukkan ke antrean render") { enqueueExport() })
        root.addView(button("Lihat status pekerjaan") { showJobs() })
        shareButton = button("Bagikan hasil terakhir") { shareExport() }
        shareButton.isEnabled = false
        root.addView(shareButton)
        openButton = button("Buka hasil terakhir") { openExport() }
        openButton.isEnabled = false
        root.addView(openButton)
        status = label("Pekerjaan render akan menampilkan progres dan notifikasi.", 14, 0xFF9CB7AC.toInt())
        root.addView(status)
        root.addView(space())
        root.addView(label("RevoClip v0.2 • AI highlight assistant + local video trimming", 12, 0xFF82909C.toInt()))

        provider.setSelection(savedInstanceState?.getInt(STATE_PROVIDER) ?: 0)
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                modelField.setText(defaultModel(provider.selectedItem.toString()))
            }
        }

        transcript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                transcriptWarning.text = when {
                    length > MAX_TRANSCRIPT_CHARS ->
                        "Terlalu panjang: batas pengiriman ${MAX_TRANSCRIPT_CHARS} karakter."
                    length >= TRANSCRIPT_WARNING_CHARS ->
                        "Peringatan: transkrip panjang ($length karakter); periksa batas token dan biaya provider."
                    else -> ""
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        restoreState(savedInstanceState)
    }

    private fun restoreState(state: Bundle?) {
        if (state == null) return
        videoUri = state.getString(STATE_VIDEO_URI)?.let(Uri::parse)
        lastExportUri = state.getString(STATE_EXPORT_URI)?.let(Uri::parse)
        transcript.setText(state.getString(STATE_TRANSCRIPT).orEmpty())
        startSeconds.setText(state.getString(STATE_START).orEmpty())
        endSeconds.setText(state.getString(STATE_END).orEmpty())
        modelField.setText(state.getString(STATE_MODEL) ?: defaultModel(provider.selectedItem.toString()))
        selectedVideo.text = videoUri?.let { "Video dipilih: ${it.lastPathSegment ?: "media"}" }
            ?: "Belum ada video dipilih"
        val savedSuggestions = state.getString(STATE_SUGGESTIONS).orEmpty()
        if (savedSuggestions.isNotBlank()) {
            runCatching { ClipSuggestionParser.parse(savedSuggestions) }
                .onSuccess {
                    clipSuggestions.clear()
                    clipSuggestions.addAll(it)
                    renderSuggestions()
                }
        }
        updateExportActions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_VIDEO_URI, videoUri?.toString())
        outState.putString(STATE_EXPORT_URI, lastExportUri?.toString())
        outState.putString(STATE_TRANSCRIPT, transcript.text.toString())
        outState.putString(STATE_START, startSeconds.text.toString())
        outState.putString(STATE_END, endSeconds.text.toString())
        outState.putString(STATE_MODEL, modelField.text.toString())
        outState.putInt(STATE_PROVIDER, provider.selectedItemPosition)
        outState.putString(STATE_SUGGESTIONS, suggestionsAsJson())
        // API key is intentionally not persisted.
        super.onSaveInstanceState(outState)
    }

    private fun analyzeTranscript() {
        val sourceText = transcript.text.toString().trim()
        val key = apiKey.text.toString().trim()
        val model = modelField.text.toString().trim()
        if (sourceText.isBlank() || key.isBlank() || model.isBlank()) {
            status.text = "Isi transkrip, API key, dan Model ID terlebih dahulu."
            return
        }
        if (sourceText.length > MAX_TRANSCRIPT_CHARS) {
            status.text = "Transkrip melebihi batas $MAX_TRANSCRIPT_CHARS karakter. Ringkas atau pecah transkrip terlebih dahulu."
            return
        }

        status.text = "AI sedang menganalisis transkrip…"
        lifecycleScope.launch {
            try {
                val selected = provider.selectedItem.toString()
                val raw = withContext(Dispatchers.IO) {
                    requestClipIdeas(sourceText, key, selected, model)
                }
                val parsed = ClipSuggestionParser.parse(raw)
                clipSuggestions.clear()
                clipSuggestions.addAll(parsed)
                renderSuggestions()
                status.text = "AI menemukan ${parsed.size} kandidat. Ketuk salah satu untuk mengisi rentang klip."
            } catch (e: Exception) {
                status.text = "Analisis gagal: ${e.message ?: "periksa koneksi, model, API key, dan kuota"}"
            }
        }
    }

    private fun requestClipIdeas(
        transcriptText: String,
        key: String,
        selected: String,
        model: String
    ): String {
        val systemPrompt = """
            You are a short-form video editor. Find 3 to 5 compelling clip candidates from the timestamped transcript.
            Return ONLY a valid JSON array. Do not use Markdown fences or commentary.
            Every array item MUST contain exactly these fields:
            {"start_sec": number, "end_sec": number, "title": string, "reason": string, "confidence": number}
            start_sec and end_sec are seconds from the video's beginning. Copy or derive timestamps only from the supplied transcript; never invent timing. end_sec must be greater than start_sec. confidence must be between 0 and 1. If there is not enough timestamp information, return [].
        """.trimIndent()
        val userPrompt = "Find the best short-form moments in this timestamped transcript:\n$transcriptText"

        val (url, isClaude) = when (selected) {
            "OpenAI" -> "https://api.openai.com/v1/chat/completions" to false
            "DeepSeek" -> "https://api.deepseek.com/chat/completions" to false
            "Claude" -> "https://api.anthropic.com/v1/messages" to true
            else -> "https://openrouter.ai/api/v1/chat/completions" to false
        }

        val body = if (isClaude) {
            JSONObject()
                .put("model", model)
                .put("max_tokens", 1600)
                .put("system", systemPrompt)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
        } else {
            JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt))
                )
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .apply {
                if (isClaude) {
                    header("x-api-key", key)
                    header("anthropic-version", "2023-06-01")
                } else {
                    header("Authorization", "Bearer $key")
                    if (selected == "OpenRouter") {
                        header("HTTP-Referer", "https://github.com/sky-pixel-Sp/RevoClip")
                        header("X-Title", "RevoClip")
                    }
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(explainHttpError(response.code, raw))
            val json = JSONObject(raw)
            val content = if (isClaude) {
                json.optJSONArray("content")?.let { parts ->
                    (0 until parts.length()).firstNotNullOfOrNull { index ->
                        parts.optJSONObject(index)?.takeIf { it.optString("type") == "text" }
                            ?.optString("text")
                    }
                }
            } else {
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
            }
            content?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Provider mengembalikan respons kosong atau tanpa teks JSON.")
        }
    }

    private fun explainHttpError(code: Int, body: String): String {
        val detail = body.replace(Regex("""\s+"""), " ").take(220)
        val explanation = when (code) {
            400 -> "Permintaan ditolak. Periksa format prompt dan kompatibilitas parameter model."
            401 -> "API key salah atau tidak valid (401)."
            403 -> "Akses provider/model ditolak (403). Periksa izin akun dan saldo."
            404 -> "Model atau endpoint tidak ditemukan (404). Periksa Model ID."
            413 -> "Permintaan terlalu besar (413). Ringkas transkrip."
            429 -> "Batas rate atau kredit provider tercapai (429). Coba lagi nanti atau periksa kuota."
            in 500..599 -> "Provider sedang bermasalah (HTTP $code)."
            else -> "Provider mengembalikan HTTP $code."
        }
        return if (detail.isBlank()) explanation else "$explanation Detail: $detail"
    }

    private fun renderSuggestions() {
        suggestionsContainer.removeAllViews()
        if (clipSuggestions.isEmpty()) {
            suggestionsContainer.addView(label("Tidak ada saran klip AI.", 13, 0xFFB9C4CE.toInt()))
            return
        }
        clipSuggestions.forEachIndexed { index, item ->
            val summary = "${index + 1}. ${item.title}\n" +
                "${ClipTimeParser.formatSeconds(item.startSec)}s – ${ClipTimeParser.formatSeconds(item.endSec)}s • confidence ${(item.confidence * 100).toInt()}%\n" +
                item.reason
            suggestionsContainer.addView(button(summary) {
                startSeconds.setText(ClipTimeParser.formatSeconds(item.startSec))
                endSeconds.setText(ClipTimeParser.formatSeconds(item.endSec))
                status.text = "Rentang klip #${index + 1} dipilih. Kamu bisa menyesuaikannya sebelum render."
            })
        }
    }

    private fun suggestionsAsJson(): String {
        val array = JSONArray()
        clipSuggestions.forEach { clip ->
            array.put(
                JSONObject()
                    .put("start_sec", clip.startSec)
                    .put("end_sec", clip.endSec)
                    .put("title", clip.title)
                    .put("reason", clip.reason)
                    .put("confidence", clip.confidence)
            )
        }
        return array.toString()
    }

    private fun enqueueExport() {
        val uri = videoUri ?: run { status.text = "Pilih video terlebih dahulu."; return }
        val start = ClipTimeParser.parseSeconds(startSeconds.text.toString())
        val end = ClipTimeParser.parseSeconds(endSeconds.text.toString())
        if (start == null || end == null) {
            status.text = "Format waktu tidak valid. Gunakan detik desimal atau mm:ss."
            return
        }
        if (!ClipRangeValidator.isValid(start, end)) {
            status.text = "Waktu selesai harus lebih besar dari waktu mulai."
            return
        }
        val data = workDataOf(
            ClipExportWorker.KEY_URI to uri.toString(),
            ClipExportWorker.KEY_START_MS to (start * 1000.0).toLong(),
            ClipExportWorker.KEY_END_MS to (end * 1000.0).toLong()
        )
        val request = OneTimeWorkRequestBuilder<ClipExportWorker>()
            .setInputData(data)
            .addTag(ClipExportWorker.TAG)
            .build()
        WorkManager.getInstance(this).enqueue(request)
        status.text = "Render masuk antrean. Progres render tersedia melalui notifikasi dan status pekerjaan."
    }

    private fun showJobs() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(ClipExportWorker.TAG)
            .observe(this) { infos ->
                if (infos.isNullOrEmpty()) {
                    status.text = "Belum ada pekerjaan."
                    return@observe
                }
                val completed = infos.firstOrNull {
                    it.state == WorkInfo.State.SUCCEEDED &&
                        it.outputData.getString(ClipExportWorker.KEY_MEDIA_URI) != null
                }
                completed?.outputData?.getString(ClipExportWorker.KEY_MEDIA_URI)?.let {
                    lastExportUri = Uri.parse(it)
                    updateExportActions()
                }
                status.text = infos.joinToString("\n") { info ->
                    val progress = info.progress.getInt(ClipExportWorker.KEY_PROGRESS, 0)
                    val output = info.outputData.getString(ClipExportWorker.KEY_MEDIA_URI)
                        ?: info.outputData.getString(ClipExportWorker.KEY_ERROR)
                        ?: info.id.toString()
                    "${info.state} • $progress% • $output"
                }
            }
    }

    private fun shareExport() {
        val uri = lastExportUri ?: run { status.text = "Belum ada hasil ekspor."; return }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(contentResolver, "RevoClip video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan video RevoClip"))
    }

    private fun openExport() {
        val uri = lastExportUri ?: run { status.text = "Belum ada hasil ekspor."; return }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { status.text = "Tidak ada aplikasi yang dapat membuka video ini." }
    }

    private fun updateExportActions() {
        val enabled = lastExportUri != null
        if (::shareButton.isInitialized) shareButton.isEnabled = enabled
        if (::openButton.isInitialized) openButton.isEnabled = enabled
    }

    private fun defaultModel(selected: String): String = when (selected) {
        "OpenAI" -> "gpt-5.6-terra"
        "Claude" -> "claude-haiku-5-5"
        "DeepSeek" -> "deepseek-flash"
        else -> "openai/gpt-5.6-terra"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Int, color: Int = 0xFFE8EDF2.toInt()) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun edit(
        hintText: String,
        value: String = "",
        multiline: Boolean = false,
        password: Boolean = false,
        decimal: Boolean = false
    ) = EditText(this).apply {
        hint = hintText
        setText(value)
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF8996A3.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(0xFF202A35.toInt())
        if (multiline) {
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP
        }
        if (password) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else if (decimal) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun space() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(18))
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 20_000
        private const val TRANSCRIPT_WARNING_CHARS = 16_000

        private const val STATE_VIDEO_URI = "state_video_uri"
        private const val STATE_EXPORT_URI = "state_export_uri"
        private const val STATE_TRANSCRIPT = "state_transcript"
        private const val STATE_START = "state_start"
        private const val STATE_END = "state_end"
        private const val STATE_MODEL = "state_model"
        private const val STATE_PROVIDER = "state_provider"
        private const val STATE_SUGGESTIONS = "state_suggestions"
    }
}
