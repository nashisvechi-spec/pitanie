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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val Sage = Color(0xFF55745B)
private val SoftSage = Color(0xFFE8EFE8)
private val AppBg = Color(0xFFF6F7F3)
private val Ink = Color(0xFF20231F)
private val Muted = Color(0xFF6C716B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Sage, background = AppBg, surface = Color.White)) {
                App()
            }
        }
    }
}

enum class ScanMode(val title: String) { FOOD("Еда"), FRIDGE("Холодильник") }
data class DetectedItem(val name: String, val grams: Int?, val calories: Int?, val protein: Int?, val fat: Int?, val carbs: Int?, val confidence: Double?)
data class DiaryEntry(val title: String, val calories: Int, val protein: Int, val fat: Int, val carbs: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(ScanMode.FOOD) }
    var status by remember { mutableStateOf("Сфотографируй еду — AI поможет сделать черновую оценку состава") }
    var loading by remember { mutableStateOf(false) }
    var detectedItems by remember { mutableStateOf<List<DetectedItem>>(emptyList()) }
    var rawNote by remember { mutableStateOf("") }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(SecureKeyStore.hasKey(context)) }
    var diary by remember { mutableStateOf<List<DiaryEntry>>(emptyList()) }

    fun analyze(uri: Uri) {
        val apiKey = SecureKeyStore.load(context)
        if (apiKey.isNullOrBlank()) {
            status = "Сначала добавь API-ключ в профиле."
            showSettings = true
            tab = 3
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
                    status = "Готово. Проверь продукты и порции перед сохранением."
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
    val launchCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraUri = createTempImageUri(context)
            camera.launch(cameraUri!!)
        } else permission.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        containerColor = AppBg,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Сегодня") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.CameraAlt, null) }, label = { Text("Скан") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.MenuBook, null) }, label = { Text("Дневник") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Person, null) }, label = { Text("Профиль") })
            }
        }
    ) { pad ->
        when (tab) {
            0 -> TodayScreen(pad, diary, onScan = { tab = 1 }, onDiary = { tab = 2 })
            1 -> ScanScreen(pad, mode, { mode = it }, loading, status, detectedItems, rawNote, launchCamera, { gallery.launch("image/*") }) {
                val calories = detectedItems.sumOf { it.calories ?: 0 }
                val protein = detectedItems.sumOf { it.protein ?: 0 }
                val fat = detectedItems.sumOf { it.fat ?: 0 }
                val carbs = detectedItems.sumOf { it.carbs ?: 0 }
                val title = detectedItems.joinToString(", ") { it.name }.take(70)
                diary = diary + DiaryEntry(title, calories, protein, fat, carbs)
                status = "Добавлено в дневник"
                tab = 0
            }
            2 -> DiaryScreen(pad, diary) { tab = 1 }
            else -> ProfileScreen(pad, keySaved, showSettings, { showSettings = !showSettings }, apiKeyDraft, { apiKeyDraft = it.trim() }, onSave = {
                if (apiKeyDraft.isNotBlank()) {
                    SecureKeyStore.save(context, apiKeyDraft); apiKeyDraft = ""; keySaved = true; showSettings = false
                }
            }, onDelete = { SecureKeyStore.delete(context); keySaved = false; apiKeyDraft = "" })
        }
    }
}

@Composable
private fun PageHeader(eyebrow: String, title: String) {
    Text("Мой рацион AI", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
    Spacer(Modifier.height(20.dp))
    Text(eyebrow, fontSize = 13.sp, color = Muted)
    Text(title, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
}

@Composable
private fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun MacroRow(label: String, value: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Ink); Text("$value г", fontWeight = FontWeight.Bold)
    }
    LinearProgressIndicator(progress = { (value.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp), color = Sage, trackColor = Color(0xFFEDEFEB))
}

@Composable
private fun TodayScreen(pad: PaddingValues, diary: List<DiaryEntry>, onScan: () -> Unit, onDiary: () -> Unit) {
    val p = diary.sumOf { it.protein }; val f = diary.sumOf { it.fat }; val c = diary.sumOf { it.carbs }
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Сегодня", "Питание без лишней рутины") }
        item {
            SoftCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Баланс дня", fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Ориентир, а не строгий лимит", fontSize = 13.sp, color = Muted) }
                    Surface(shape = RoundedCornerShape(14.dp), color = SoftSage) { Text("Хороший ритм", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, color = Sage) }
                }
                Spacer(Modifier.height(18.dp)); MacroRow("Белки", p, 100); Spacer(Modifier.height(10.dp)); MacroRow("Жиры", f, 80); Spacer(Modifier.height(10.dp)); MacroRow("Углеводы", c, 240)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScan, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Скан еды") }
                FilledTonalButton(onClick = onDiary, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("Дневник") }
            }
        }
        item { Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFFF0F4ED)) { Column(Modifier.padding(15.dp)) { Text("AI-подсказка", fontWeight = FontWeight.Bold); Text("Фото — быстрый черновик. Перед сохранением проверь продукт и размер порции.", color = Color(0xFF455445)) } } }
        item { Text("Сегодняшний дневник", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        if (diary.isEmpty()) item { SoftCard { Text("Пока записей нет"); Text("Добавь первый приём пищи через AI-скан.", color = Muted, fontSize = 13.sp) } }
        items(diary.takeLast(3).reversed()) { entry ->
            SoftCard { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(58.dp).background(SoftSage, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Restaurant, null, tint = Sage) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(entry.title, fontWeight = FontWeight.Bold); Text("Б ${entry.protein} · Ж ${entry.fat} · У ${entry.carbs} г", fontSize = 13.sp, color = Muted) } } }
        }
    }
}

@Composable
private fun ScanScreen(pad: PaddingValues, mode: ScanMode, onMode: (ScanMode) -> Unit, loading: Boolean, status: String, detected: List<DetectedItem>, note: String, onCamera: () -> Unit, onGallery: () -> Unit, onAdd: () -> Unit) {
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("AI-скан", if (mode == ScanMode.FOOD) "Что у тебя на тарелке?" else "Что есть в холодильнике?") }
        item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { ScanMode.entries.forEachIndexed { index, item -> SegmentedButton(selected = mode == item, onClick = { onMode(item) }, shape = SegmentedButtonDefaults.itemShape(index, ScanMode.entries.size)) { Text(item.title) } } } }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF4EC))) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Фото помогает быстро начать", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Результат можно проверить перед сохранением", color = Muted, fontSize = 13.sp)
                    Box(Modifier.fillMaxWidth().height(170.dp).padding(vertical = 14.dp).background(Color.White.copy(alpha = .65f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(54.dp), tint = Sage) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onCamera, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Text("Камера") }; FilledTonalButton(onClick = onGallery, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Text("Галерея") } }
                }
            }
        }
        item { SoftCard { if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text(status, color = if (loading) Sage else Ink) } }
        if (detected.isNotEmpty()) item { Text("После распознавания", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(detected) { item -> SoftCard { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Icon(Icons.Default.Edit, null, tint = Sage) }; val bits = mutableListOf<String>(); item.grams?.let { bits.add("≈ $it г") }; item.confidence?.let { bits.add("AI уверен на ${(it * 100).toInt()}%") }; Text(bits.joinToString(" · "), color = Muted, fontSize = 13.sp); if (item.calories != null) Text("${item.calories} ккал · Б ${item.protein ?: 0} · Ж ${item.fat ?: 0} · У ${item.carbs ?: 0}") } }
        if (detected.isNotEmpty() && mode == ScanMode.FOOD) item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить в дневник") } }
        if (note.isNotBlank()) item { Text(note, color = Muted, fontSize = 13.sp) }
        if (mode == ScanMode.FRIDGE) item { SoftCard { Text("Идеи из холодильника", fontWeight = FontWeight.Bold); Text("AI учитывает только то, что видно на фото, и не выдумывает содержимое закрытых упаковок.", color = Muted, fontSize = 13.sp) } }
    }
}

@Composable
private fun DiaryScreen(pad: PaddingValues, diary: List<DiaryEntry>, onScan: () -> Unit) {
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Дневник", "Всё за день в одном месте") }
        if (diary.isEmpty()) item { SoftCard { Text("Дневник пока пуст", fontWeight = FontWeight.Bold); Text("Сканируй еду, проверь результат и добавь запись.", color = Muted); Spacer(Modifier.height(12.dp)); Button(onClick = onScan) { Text("Открыть скан") } } }
        items(diary.reversed()) { entry -> SoftCard { Text(entry.title, fontWeight = FontWeight.Bold); Text("${entry.calories} ккал", color = Muted); Text("Б ${entry.protein} · Ж ${entry.fat} · У ${entry.carbs} г") } }
    }
}

@Composable
private fun ProfileScreen(pad: PaddingValues, keySaved: Boolean, showSettings: Boolean, toggle: () -> Unit, key: String, onKey: (String) -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Профиль", "Настройки приложения") }
        item { SoftCard { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(48.dp).background(SoftSage, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = Sage) }; Spacer(Modifier.width(12.dp)); Column { Text("Мой рацион", fontWeight = FontWeight.Bold); Text("Фокус на привычках и удобном дневнике", color = Muted, fontSize = 13.sp) } } } }
        item { OutlinedButton(onClick = toggle, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text("AI · ${if (keySaved) "ключ сохранён" else "ключ не задан"}") } }
        if (showSettings) item { SoftCard { Text("OpenAI API key", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); OutlinedTextField(value = key, onValueChange = onKey, label = { Text("API-ключ") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Text("Ключ шифруется Android Keystore и остаётся на телефоне.", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onSave, enabled = key.isNotBlank()) { Text("Сохранить") }; OutlinedButton(onClick = onDelete) { Text("Удалить") } } } }
        item { Text("Распознавание еды и размеры порций приблизительные. Приложение не оценивает внешность и не заменяет медицинские рекомендации.", color = Muted, fontSize = 13.sp) }
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
