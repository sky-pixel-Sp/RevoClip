package com.revclip.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var selectedVideo: TextView
    private lateinit var status: TextView
    private lateinit var transcript: EditText
    private lateinit var apiKey: EditText
    private lateinit var suggestions: TextView
    private lateinit var startSeconds: EditText
    private lateinit var endSeconds: EditText
    private lateinit var provider: Spinner
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            videoUri = uri
            selectedVideo.text = "Video dipilih: ${uri.lastPathSegment ?: "media"}"
            status.text = "Siap dianalisis atau dipotong."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 32)
            setBackgroundColor(0xFF111820.toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label("RevoClip", 30, 0xFFFFFFFF.toInt()))
        root.addView(label("AI Auto Clipping  •  Video Editor", 14, 0xFF9CB7AC.toInt()))
        root.addView(space())
        root.addView(label("1. Import video panjang", 18))
        root.addView(button("Pilih video") { picker.launch(arrayOf("video/*")) })
        selectedVideo = label("Belum ada video dipilih", 13, 0xFFB9C4CE.toInt())
        root.addView(selectedVideo)
        root.addView(space())
        root.addView(label("2. AI cari momen terbaik", 18))
        root.addView(label("Tempel transkrip bertimestamp. AI mengusulkan momen menarik; pengguna memeriksa timestamp sebelum ekspor. Analisis MVP ini memakai teks, bukan pemahaman frame video.", 13, 0xFFB9C4CE.toInt()))
        provider = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("OpenRouter", "OpenAI", "Claude", "DeepSeek"))
        }
        root.addView(provider)
        apiKey = edit("API key provider (hanya dipakai selama sesi ini)", password = true)
        root.addView(apiKey)
        transcript = edit("Tempel transkrip + timestamp di sini", multiline = true)
        root.addView(transcript)
        root.addView(button("Analisis transkrip dengan AI") { analyzeTranscript() })
        suggestions = label("Saran klip AI akan muncul di sini.", 13, 0xFFB9C4CE.toInt())
        root.addView(suggestions)
        root.addView(space())
        root.addView(label("3. Potong & ekspor MP4", 18))
        root.addView(label("Atur waktu dalam detik berdasarkan saran AI. Pemotongan video dilakukan lokal dengan Media3 Transformer.", 13, 0xFFB9C4CE.toInt()))
        startSeconds = edit("Mulai (detik)", value = "0")
        endSeconds = edit("Selesai (detik)", value = "30")
        root.addView(startSeconds); root.addView(endSeconds)
        root.addView(button("Masukkan ke antrean render") { enqueueExport() })
        root.addView(button("Lihat status pekerjaan") { showJobs() })
        status = label("Pekerjaan render berjalan di latar belakang.", 14, 0xFF9CB7AC.toInt())
        root.addView(status)
        root.addView(space())
        root.addView(label("RevoClip MVP • AI highlight assistant + real local video trimming", 12, 0xFF82909C.toInt()))
    }

    private fun analyzeTranscript() {
        val text = transcript.text.toString().trim()
        val key = apiKey.text.toString().trim()
        if (text.isBlank() || key.isBlank()) { status.text = "Isi transkrip bertimestamp dan API key terlebih dahulu."; return }
        status.text = "AI sedang menganalisis transkrip…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { requestClipIdeas(text, key, provider.selectedItem.toString()) }
                suggestions.text = result
                status.text = "Analisis selesai. Periksa timestamp dan tentukan rentang klip sebelum render."
            } catch (e: Exception) {
                status.text = "Analisis gagal: ${e.message ?: "periksa koneksi, API key, dan kuota"}"
            }
        }
    }

    private fun requestClipIdeas(transcriptText: String, key: String, selected: String): String {
        val prompt = "Find 3-5 engaging short-form clips in this timestamped transcript. For each return start/end timestamps copied exactly, hook/title, why it works, and confidence. Never invent timestamps. Transcript:\\n$transcriptText"
        val (url, model) = when (selected) {
            "OpenAI" -> "https://api.openai.com/v1/chat/completions" to "gpt-4o-mini"
            "DeepSeek" -> "https://api.deepseek.com/chat/completions" to "deepseek-chat"
            "Claude" -> "https://api.anthropic.com/v1/messages" to "claude-3-5-haiku-latest"
            else -> "https://openrouter.ai/api/v1/chat/completions" to "openai/gpt-4o-mini"
        }
        val body = if (selected == "Claude") JSONObject().put("model", model).put("max_tokens", 1200)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        else JSONObject().put("model", model).put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json").apply {
                if (selected == "Claude") { header("x-api-key", key); header("anthropic-version", "2023-06-01") }
                else header("Authorization", "Bearer $key")
            }.build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${raw.take(250)}")
            val json = JSONObject(raw)
            if (selected == "Claude") json.getJSONArray("content").getJSONObject(0).getString("text")
            else json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun enqueueExport() {
        val uri = videoUri ?: run { status.text = "Pilih video terlebih dahulu."; return }
        val start = startSeconds.text.toString().toLongOrNull() ?: 0L
        val end = endSeconds.text.toString().toLongOrNull() ?: 0L
        if (start < 0 || end <= start) { status.text = "Waktu selesai harus lebih besar dari waktu mulai."; return }
        val data = workDataOf(ClipExportWorker.KEY_URI to uri.toString(),
            ClipExportWorker.KEY_START_MS to start * 1000, ClipExportWorker.KEY_END_MS to end * 1000)
        val request = OneTimeWorkRequestBuilder<ClipExportWorker>().setInputData(data).addTag(ClipExportWorker.TAG).build()
        WorkManager.getInstance(this).enqueue(request)
        status.text = "Render masuk antrean. Tutup aplikasi bila perlu; pekerjaan dapat diperiksa kembali."
    }

    private fun showJobs() {
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(ClipExportWorker.TAG).observe(this) { infos ->
            status.text = if (infos.isNullOrEmpty()) "Belum ada pekerjaan." else infos.joinToString("\\n") {
                "${it.state} • ${it.progress.getInt("progress", 0)}% • ${it.outputData.getString("output") ?: it.outputData.getString("error") ?: it.id}"
            }
        }
    }

    private fun label(text: String, size: Int, color: Int = 0xFFE8EDF2.toInt()) =
        TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); setPadding(0, 8, 0, 8) }
    private fun edit(hintText: String, value: String = "", multiline: Boolean = false, password: Boolean = false) =
        EditText(this).apply {
            hint = hintText; setText(value); textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8996A3.toInt()); setPadding(14, 12, 14, 12); setBackgroundColor(0xFF202A35.toInt())
            if (multiline) { minLines = 4; maxLines = 8; gravity = android.view.Gravity.TOP }
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 }
        }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
    }
    private fun space() = TextView(this).apply { height = 18 }
}
