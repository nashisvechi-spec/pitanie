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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { App() } }
    }
}

enum class ScanMode(val title: String) { FOOD("Еда"), FRIDGE("Холодильник") }
enum class Goal(val title: String) { LOSE("Снизить вес"), MAINTAIN("Поддерживать"), GAIN("Набрать вес") }
data class DetectedItem(val name: String, val grams: Int?, val calories: Int?, val protein: Int?, val fat: Int?, val carbs: Int?, val confidence: Double?)
data class DiaryEntry(val title: String, val calories: Int, val protein: Int, val fat: Int, val carbs: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(ScanMode.FOOD) }
    var status by remember { mutableStateOf("Сфотографируй еду — AI поможет оценить состав и КБЖУ") }
    var loading by remember { mutableStateOf(false) }
    var detectedItems by remember { mutableStateOf<List<DetectedItem>>(emptyList()) }
    var rawNote by remember { mutableStateOf("") }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(SecureKeyStore.hasKey(context)) }
    var goal by remember { mutableStateOf(Goal.MAINTAIN) }
    var dailyTarget by remember { mutableIntStateOf(2000) }
    var diary by remember { mutableStateOf<List<DiaryEntry>>(emptyList()) }

    fun analyze(uri: Uri) {
        val apiKey = SecureKeyStore.load(context)
        if (apiKey.isNullOrBlank()) {
            status = "Сначала добавь API-ключ в настройках AI."
            showSettings = true
            tab = 2
            return
        }
        loading = true
        detectedItems = emptyList()
        rawNote = ""
        status = "AI анализирует фотографию…"
        scope.launch {
            runCatching { OpenAiVision.analyze(context, uri, mode, apiKey) }
                .onSuccess { result ->
                    detectedItems = result.first
                    rawNote = result.second
                    status = "Готово. Проверь продукты и порции перед добавлением."
                }
                .onFailure { error -> status = "Ошибка: ${error.message ?: "неизвестная ошибка"}" }
            loading = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::analyze) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::analyze) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraUri = createTempImageUri(context)
            camera.launch(cameraUri!!)
        } else status = "Без камеры можно выбрать фото из галереи."
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Мой рацион AI") }, actions = { IconButton(onClick = { tab = 2; showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") } }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Сегодня") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.CameraAlt, null) }, label = { Text("AI-скан") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Профиль") })
            }
        }
    ) { pad ->
        when (tab) {
            0 -> {
                val eaten = diary.sumOf { entry -> entry.calories }
                LazyColumn(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Сегодня", style = MaterialTheme.typography.headlineMedium); Text("Цель: ${goal.title}") }
                    item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text("$eaten / $dailyTarget ккал", style = MaterialTheme.typography.headlineSmall); LinearProgressIndicator(progress = { (eaten.toFloat() / dailyTarget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); Text("Осталось примерно ${(dailyTarget - eaten).coerceAtLeast(0)} ккал") } } }
                    item { Button(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Распознать еду по фото") } }
                    item { Text("Дневник", style = MaterialTheme.typography.titleLarge) }
                    if (diary.isEmpty()) item { Text("Пока пусто. Добавь первый приём пищи через AI-скан.") }
                    items(diary) { entry -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(entry.title, style = MaterialTheme.typography.titleMedium); Text("${entry.calories} ккал · Б ${entry.protein} · Ж ${entry.fat} · У ${entry.carbs} г") } } }
                }
            }
            1 -> LazyColumn(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("AI-распознавание", style = MaterialTheme.typography.headlineSmall); Text("Фото помогает оценить продукты, порцию и КБЖУ. Все значения приблизительные.") }
                item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { ScanMode.entries.forEachIndexed { index, scanMode -> SegmentedButton(selected = mode == scanMode, onClick = { mode = scanMode }, shape = SegmentedButtonDefaults.itemShape(index, ScanMode.entries.size)) { Text(scanMode.title) } } } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { cameraUri = createTempImageUri(context); camera.launch(cameraUri!!) } else permission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Камера") }
                    OutlinedButton(onClick = { gallery.launch("image/*") }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Галерея") }
                } }
                item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status) } } }
                items(detectedItems) { detected -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(detected.name, style = MaterialTheme.typography.titleMedium); val details = mutableListOf<String>(); detected.grams?.let { grams -> details.add("~$grams г") }; detected.calories?.let { calories -> details.add("$calories ккал") }; detected.confidence?.let { confidence -> details.add("уверенность ${(confidence * 100).toInt()}%") }; Text(details.joinToString(" · ")); if (detected.calories != null) Text("Б ${detected.protein ?: 0} · Ж ${detected.fat ?: 0} · У ${detected.carbs ?: 0} г") } } }
                if (detectedItems.isNotEmpty() && mode == ScanMode.FOOD) item {
                    Button(onClick = {
                        val calories = detectedItems.sumOf { detected -> detected.calories ?: 0 }
                        val protein = detectedItems.sumOf { detected -> detected.protein ?: 0 }
                        val fat = detectedItems.sumOf { detected -> detected.fat ?: 0 }
                        val carbs = detectedItems.sumOf { detected -> detected.carbs ?: 0 }
                        val title = detectedItems.joinToString(", ") { detected -> detected.name }.take(60)
                        diary = diary + DiaryEntry(title, calories, protein, fat, carbs)
                        status = "Добавлено в дневник"
                        tab = 0
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить в дневник") }
                }
                if (rawNote.isNotBlank()) item { Text(rawNote, style = MaterialTheme.typography.bodySmall) }
            }
            else -> LazyColumn(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Цель и настройки", style = MaterialTheme.typography.headlineSmall) }
                item { Text("Выбери цель. Ориентир калорий можно менять вручную.") }
                item { Column { Goal.entries.forEach { choice -> FilterChip(selected = goal == choice, onClick = { goal = choice; dailyTarget = when (choice) { Goal.LOSE -> 1800; Goal.MAINTAIN -> 2000; Goal.GAIN -> 2200 } }, label = { Text(choice.title) }); Spacer(Modifier.height(6.dp)) } } }
                item { OutlinedTextField(value = dailyTarget.toString(), onValueChange = { text -> text.toIntOrNull()?.let { value -> dailyTarget = value.coerceIn(1200, 4000) } }, label = { Text("Дневной ориентир, ккал") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedButton(onClick = { showSettings = !showSettings }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text("Настройки AI · ${if (keySaved) "ключ сохранён" else "ключ не задан"}") } }
                if (showSettings) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("OpenAI API key", style = MaterialTheme.typography.titleMedium); OutlinedTextField(value = apiKeyDraft, onValueChange = { text -> apiKeyDraft = text.trim() }, label = { Text("Вставь API-ключ") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Text("Ключ шифруется Android Keystore и остаётся на телефоне.", style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { if (apiKeyDraft.isNotBlank()) { SecureKeyStore.save(context, apiKeyDraft); apiKeyDraft = ""; keySaved = true; showSettings = false } }, enabled = apiKeyDraft.isNotBlank()) { Text("Сохранить") }; OutlinedButton(onClick = { SecureKeyStore.delete(context); keySaved = false; apiKeyDraft = "" }) { Text("Удалить") } } } } }
                item { Text("Важно: распознавание еды и расчёт порций приблизительные. Для медицинских целей приложение не предназначено.", style = MaterialTheme.typography.bodySmall) }
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
        val instruction = if (mode == ScanMode.FOOD) "Определи видимые блюда и продукты. Приблизительно оцени массу и КБЖУ каждого элемента." else "Определи видимые продукты в холодильнике. Не выдумывай содержимое закрытых непрозрачных упаковок."
        val prompt = "$instruction Верни ТОЛЬКО JSON: {\"items\":[{\"name\":\"название по-русски\",\"grams\":120,\"calories\":180,\"protein\":12,\"fat\":7,\"carbs\":18,\"confidence\":0.85}],\"note\":\"краткое замечание\"}. Если значение неизвестно — null. Не делай медицинских выводов и не оценивай внешность."
        val body = JSONObject().apply {
            put("model", "gpt-6-luna")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt)).put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$base64").put("detail", "low")))
            }))
            put("max_output_tokens", 1200)
        }
        val conn = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000; readTimeout = 60_000
        }
        conn.outputStream.use { stream -> stream.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { reader -> reader.readText() }
        if (code !in 200..299) error("OpenAI HTTP $code: ${text.take(250)}")
        val clean = extractOutputText(JSONObject(text)).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = JSONObject(clean)
        val arr = parsed.optJSONArray("items") ?: JSONArray()
        val found = mutableListOf<DetectedItem>()
        for (index in 0 until arr.length()) {
            val obj = arr.getJSONObject(index)
            fun optionalInt(key: String): Int? = if (obj.isNull(key)) null else obj.optInt(key)
            found.add(DetectedItem(obj.optString("name", "Продукт"), optionalInt("grams"), optionalInt("calories"), optionalInt("protein"), optionalInt("fat"), optionalInt("carbs"), if (obj.isNull("confidence")) null else obj.optDouble("confidence")))
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
        error("Модель не вернула результат")
    }

    private fun imageAsBase64(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось открыть изображение")
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Неверный формат")
        val scale = minOf(1f, 1280f / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
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
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    fun save(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CIPHER_TEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
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
