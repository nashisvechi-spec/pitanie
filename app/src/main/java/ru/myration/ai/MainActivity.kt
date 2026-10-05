package ru.myration.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

enum class ScanMode(val title: String) { FOOD("Еда"), FRIDGE("Холодильник") }
data class DetectedItem(val name: String, val grams: Int?, val confidence: Double?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ScanMode.FOOD) }
    var status by remember { mutableStateOf("Готово к распознаванию") }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<DetectedItem>>(emptyList()) }
    var rawNote by remember { mutableStateOf("") }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(SecureKeyStore.hasKey(context)) }

    fun analyze(uri: Uri) {
        val apiKey = SecureKeyStore.load(context)
        if (apiKey.isNullOrBlank()) {
            status = "API-ключ не задан. Открой «Настройки AI» и вставь ключ."
            showSettings = true
            return
        }
        loading = true; items = emptyList(); rawNote = ""; status = "Распознаю…"
        scope.launch {
            runCatching { OpenAiVision.analyze(context, uri, mode, apiKey) }
                .onSuccess { r -> items = r.first; rawNote = r.second; status = "Проверь результат — распознавание и порции приблизительные." }
                .onFailure { e -> status = "Ошибка: ${e.message ?: "неизвестная ошибка"}" }
            loading = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::analyze) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::analyze) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraUri = createTempImageUri(context)
            camera.launch(cameraUri!!)
        } else status = "Без разрешения камеры можно выбрать фото из галереи."
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Мой рацион AI") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Личная тестовая сборка", style = MaterialTheme.typography.titleLarge)
                Text("Фото отправляется напрямую в OpenAI API. Не публикуй APK с личным API-ключом.")
            }
            item {
                OutlinedButton(onClick = { showSettings = !showSettings }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Настройки AI · ${if (keySaved) "ключ сохранён" else "ключ не задан"}")
                }
            }
            if (showSettings) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("OpenAI API key", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = apiKeyDraft, onValueChange = { apiKeyDraft = it.trim() }, label = { Text("Вставь API-ключ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Ключ сохраняется только на этом телефоне в зашифрованном виде и не добавляется в GitHub.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (apiKeyDraft.isNotBlank()) { SecureKeyStore.save(context, apiKeyDraft); apiKeyDraft = ""; keySaved = true; status = "API-ключ сохранён."; showSettings = false }
                        }, enabled = apiKeyDraft.isNotBlank()) { Text("Сохранить") }
                        OutlinedButton(onClick = { SecureKeyStore.delete(context); apiKeyDraft = ""; keySaved = false; status = "API-ключ удалён." }) { Text("Удалить") }
                    }
                }}
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ScanMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, ScanMode.entries.size)) { Text(m.title) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            cameraUri = createTempImageUri(context); camera.launch(cameraUri!!)
                        } else permission.launch(Manifest.permission.CAMERA)
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Камера") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Галерея") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp)); Text(status)
                }}
            }
            if (items.isNotEmpty()) {
                item { Text(if (mode == ScanMode.FOOD) "Что найдено" else "Продукты в холодильнике", style = MaterialTheme.typography.titleMedium) }
                items(items) { x ->
                    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(x.name)
                        Text(listOfNotNull(x.grams?.let { "~$it г" }, x.confidence?.let { "${(it*100).toInt()}%" }).joinToString(" · "))
                    }}
                }
            }
            if (rawNote.isNotBlank()) item { Text(rawNote, style = MaterialTheme.typography.bodySmall) }
            item {
                HorizontalDivider(); Spacer(Modifier.height(6.dp))
                Text("Важно: модель может ошибаться и не может точно определить массу по одной фотографии. Перед сохранением результата проверяй продукты и порции.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("meal_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

object OpenAiVision {
    suspend fun analyze(context: Context, uri: Uri, mode: ScanMode, apiKey: String): Pair<List<DetectedItem>, String> = withContext(Dispatchers.IO) {
        val base64 = imageAsBase64(context, uri)
        val instruction = if (mode == ScanMode.FOOD)
            "Определи видимые продукты/блюда на фото. Оцени граммы только приблизительно."
        else "Определи видимые продукты в холодильнике. Для закрытых упаковок используй только то, что можно уверенно определить. Граммы можно не указывать."
        val prompt = "$instruction Верни ТОЛЬКО JSON без markdown: {\"items\":[{\"name\":\"название по-русски\",\"grams\":120,\"confidence\":0.85}],\"note\":\"краткое замечание\"}. confidence от 0 до 1; если grams неизвестны, используй null. Не делай медицинских выводов и не оценивай внешность."
        val body = JSONObject().apply {
            put("model", "gpt-6-luna")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", prompt))
                    .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$base64").put("detail", "low")))
            }))
            put("max_output_tokens", 900)
        }
        val conn = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000; readTimeout = 60_000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("OpenAI HTTP $code: ${text.take(250)}")
        val response = JSONObject(text)
        val outputText = extractOutputText(response)
        val clean = outputText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = JSONObject(clean)
        val arr = parsed.optJSONArray("items") ?: JSONArray()
        val found = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(DetectedItem(o.optString("name", "Продукт"), if (o.isNull("grams")) null else o.optInt("grams"), if (o.isNull("confidence")) null else o.optDouble("confidence")))
            }
        }
        found to parsed.optString("note", "")
    }

    private fun extractOutputText(root: JSONObject): String {
        val output = root.optJSONArray("output") ?: error("В ответе нет output")
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") return c.optString("text")
            }
        }
        error("Модель не вернула текстовый результат")
    }

    private fun imageAsBase64(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось открыть изображение")
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Неверный формат изображения")
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width*scale).toInt(), (original.height*scale).toInt(), true) else original
        val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (bitmap !== original) bitmap.recycle(); original.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}


object SecureKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "myration_openai_key"
    private const val PREFS = "secure_ai_settings"
    private const val CIPHER_TEXT = "api_key_cipher"
    private const val IV = "api_key_iv"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun save(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CIPHER_TEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enc = prefs.getString(CIPHER_TEXT, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun hasKey(context: Context) = !load(context).isNullOrBlank()
    fun delete(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}
