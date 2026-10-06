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
import kotlinx.coroutines.flow.collectLatest
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
private val AppBg = Color(0xFFF6F7F3)
private val Ink = Color(0xFF20231F)
private val Muted = Color(0xFF6C716B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Sage, background = AppBg, surface = Color.White)) { App() }
        }
    }
}

enum class ScanMode(val title: String) { FOOD("Еда"), FRIDGE("Холодильник") }
data class DetectedItem(val name:String,val grams:Int?,val calories:Int?,val protein:Int?,val fat:Int?,val carbs:Int?,val confidence:Double?)
data class AiResult(val items:List<DetectedItem>,val note:String,val ideas:List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).dao() }
    var diary by remember { mutableStateOf<List<DiaryEntity>>(emptyList()) }
    LaunchedEffect(Unit) { dao.observeDiary().collectLatest { diary = it } }

    var tab by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(ScanMode.FOOD) }
    var status by remember { mutableStateOf("Сфотографируй еду — AI поможет сделать черновую оценку состава") }
    var loading by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<List<DetectedItem>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var ideas by remember { mutableStateOf<List<String>>(emptyList()) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(SecureKeyStore.hasKey(context)) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    fun analyze(uri:Uri) {
        val apiKey = SecureKeyStore.load(context)
        if (apiKey.isNullOrBlank()) {
            status = "Сначала добавь API-ключ в профиле."
            showSettings = true
            tab = 3
            return
        }
        loading = true
        detected = emptyList()
        note = ""
        ideas = emptyList()
        status = "AI анализирует фотографию…"
        scope.launch {
            runCatching { OpenAiVision.analyze(context, uri, mode, apiKey) }
                .onSuccess { result ->
                    detected = result.items
                    note = result.note
                    ideas = result.ideas
                    status = if (result.items.isEmpty() && result.ideas.isEmpty()) "На фото не удалось уверенно распознать продукты." else "Готово. Проверь результат перед сохранением."
                }
                .onFailure { status = "Ошибка анализа: ${it.message ?: "неизвестная ошибка"}" }
            loading = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::analyze) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::analyze) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { cameraUri = createTempImageUri(context); camera.launch(cameraUri!!) }
        else status = "Доступ к камере не дан. Можно выбрать фото из галереи."
    }
    val launchCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraUri = createTempImageUri(context); camera.launch(cameraUri!!)
        } else permission.launch(Manifest.permission.CAMERA)
    }

    Scaffold(containerColor=AppBg,bottomBar={NavigationBar(containerColor=Color.White){
        NavigationBarItem(tab==0,{tab=0},{Icon(Icons.Default.Home,null)},label={Text("Сегодня")})
        NavigationBarItem(tab==1,{tab=1},{Icon(Icons.Default.CameraAlt,null)},label={Text("Скан")})
        NavigationBarItem(tab==2,{tab=2},{Icon(Icons.Default.MenuBook,null)},label={Text("Дневник")})
        NavigationBarItem(tab==3,{tab=3},{Icon(Icons.Default.Person,null)},label={Text("Профиль")})
    }}) { pad ->
        when(tab) {
            0 -> TodayScreen(pad,diary,{tab=1},{tab=2})
            1 -> ScanScreen(pad,mode,{mode=it},loading,status,detected,note,ideas,launchCamera,{gallery.launch("image/*")},{editIndex=it}) {
                if (detected.isNotEmpty()) {
                    val title = detected.joinToString(", "){it.name}.take(90)
                    scope.launch {
                        dao.insertDiary(DiaryEntity(title=title,calories=detected.sumOf{it.calories?:0},protein=detected.sumOf{it.protein?:0},fat=detected.sumOf{it.fat?:0},carbs=detected.sumOf{it.carbs?:0}))
                        status="Сохранено в дневник"
                        tab=0
                    }
                }
            }
            2 -> DiaryScreen(pad,diary,{tab=1}) { entry -> scope.launch { dao.deleteDiary(entry) } }
            else -> ProfileScreen(pad,keySaved,showSettings,{showSettings=!showSettings},apiKeyDraft,{apiKeyDraft=it.trim()},{
                if(apiKeyDraft.isNotBlank()){SecureKeyStore.save(context,apiKeyDraft);apiKeyDraft="";keySaved=true;showSettings=false}
            },{SecureKeyStore.delete(context);keySaved=false;apiKeyDraft=""})
        }
    }

    editIndex?.let { idx ->
        detected.getOrNull(idx)?.let { item ->
            EditItemDialog(item,{editIndex=null}) { updated ->
                detected=detected.toMutableList().also{it[idx]=updated}
                editIndex=null
            }
        }
    }
}

@Composable private fun PageHeader(eyebrow:String,title:String){
    Text("Мой рацион AI",fontSize=25.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
    Spacer(Modifier.height(20.dp));Text(eyebrow,fontSize=13.sp,color=Muted);Text(title,fontSize=28.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,color=Ink)
}
@Composable private fun SoftCard(content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(18.dp),content=content)}}

@Composable private fun TodayScreen(pad:PaddingValues,diary:List<DiaryEntity>,onScan:()->Unit,onDiary:()->Unit){
    val p=diary.sumOf{it.protein};val f=diary.sumOf{it.fat};val c=diary.sumOf{it.carbs}
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(top=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader("Сегодня","Питание без лишней рутины")}
        item{SoftCard{Text("Сводка дневника",fontSize=18.sp,fontWeight=FontWeight.Bold);Text("Ориентир по записанным продуктам, не строгий лимит",color=Muted,fontSize=13.sp);Spacer(Modifier.height(12.dp));Text("Белки $p г · Жиры $f г · Углеводы $c г",fontWeight=FontWeight.SemiBold)}}
        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick=onScan,modifier=Modifier.weight(1f).height(58.dp),shape=RoundedCornerShape(18.dp)){Icon(Icons.Default.CameraAlt,null);Spacer(Modifier.width(6.dp));Text("Скан еды")};FilledTonalButton(onClick=onDiary,modifier=Modifier.weight(1f).height(58.dp),shape=RoundedCornerShape(18.dp)){Icon(Icons.Default.MenuBook,null);Spacer(Modifier.width(6.dp));Text("Дневник")}}}
        item{Text("Последние записи",fontSize=18.sp,fontWeight=FontWeight.Bold)}
        if(diary.isEmpty()) item{SoftCard{Text("Пока записей нет");Text("Добавь приём пищи через AI-скан.",color=Muted)}}
        items(diary.take(3),key={it.id}){e->SoftCard{Text(e.title,fontWeight=FontWeight.Bold);Text("${e.calories} ккал · Б ${e.protein} · Ж ${e.fat} · У ${e.carbs} г",color=Muted)}}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScanScreen(pad:PaddingValues,mode:ScanMode,onMode:(ScanMode)->Unit,loading:Boolean,status:String,detected:List<DetectedItem>,note:String,ideas:List<String>,onCamera:()->Unit,onGallery:()->Unit,onEdit:(Int)->Unit,onAdd:()->Unit){
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(top=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader("AI-скан",if(mode==ScanMode.FOOD)"Что у тебя на тарелке?" else "Что есть в холодильнике?")}
        item{SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){ScanMode.entries.forEachIndexed{i,m->SegmentedButton(selected=mode==m,onClick={onMode(m)},shape=SegmentedButtonDefaults.itemShape(i,ScanMode.entries.size)){Text(m.title)}}}}
        item{SoftCard{Text("Сфотографируй или выбери фото",fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick=onCamera,enabled=!loading,modifier=Modifier.weight(1f)){Text("Камера")};FilledTonalButton(onClick=onGallery,enabled=!loading,modifier=Modifier.weight(1f)){Text("Галерея")}}}}
        item{SoftCard{if(loading){LinearProgressIndicator(Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp))};Text(status)}}
        items(detected.size){i->val d=detected[i];SoftCard{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(d.name,fontWeight=FontWeight.Bold);Text("≈ ${d.grams?:"?"} г · ${d.calories?:"?"} ккал",color=Muted);Text("Б ${d.protein?:0} · Ж ${d.fat?:0} · У ${d.carbs?:0} г",color=Muted);d.confidence?.let{Text("Уверенность AI: ${(it*100).toInt().coerceIn(0,100)}%",fontSize=12.sp,color=Muted)}};IconButton({onEdit(i)}){Icon(Icons.Default.Edit,"Изменить")}}}}
        if(detected.isNotEmpty()&&mode==ScanMode.FOOD)item{Button(onClick=onAdd,modifier=Modifier.fillMaxWidth().height(54.dp)){Text("Добавить в дневник")}}
        if(note.isNotBlank())item{Text(note,color=Muted)}
        if(mode==ScanMode.FRIDGE&&ideas.isNotEmpty())item{SoftCard{Text("Идеи из холодильника",fontWeight=FontWeight.Bold);ideas.forEach{Text("• $it",modifier=Modifier.padding(top=7.dp))};Text("Проверь состав продуктов и учитывай аллергию или непереносимость.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))}}
    }
}

@Composable private fun EditItemDialog(item:DetectedItem,onDismiss:()->Unit,onSave:(DetectedItem)->Unit){
    var name by remember(item){mutableStateOf(item.name)}
    var grams by remember(item){mutableStateOf(item.grams?.toString().orEmpty())}
    var calories by remember(item){mutableStateOf(item.calories?.toString().orEmpty())}
    var protein by remember(item){mutableStateOf(item.protein?.toString().orEmpty())}
    var fat by remember(item){mutableStateOf(item.fat?.toString().orEmpty())}
    var carbs by remember(item){mutableStateOf(item.carbs?.toString().orEmpty())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Проверить результат")},text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
        OutlinedTextField(name,{name=it},label={Text("Название")})
        OutlinedTextField(grams,{grams=it.filter(Char::isDigit)},label={Text("Граммы")})
        OutlinedTextField(calories,{calories=it.filter(Char::isDigit)},label={Text("Ккал")})
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(protein,{protein=it.filter(Char::isDigit)},label={Text("Б")},modifier=Modifier.weight(1f));OutlinedTextField(fat,{fat=it.filter(Char::isDigit)},label={Text("Ж")},modifier=Modifier.weight(1f));OutlinedTextField(carbs,{carbs=it.filter(Char::isDigit)},label={Text("У")},modifier=Modifier.weight(1f))}
    }},confirmButton={Button(onClick={onSave(DetectedItem(name.ifBlank{"Продукт"},grams.toIntOrNull(),calories.toIntOrNull(),protein.toIntOrNull(),fat.toIntOrNull(),carbs.toIntOrNull(),item.confidence))}){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@Composable private fun DiaryScreen(pad:PaddingValues,diary:List<DiaryEntity>,onScan:()->Unit,onDelete:(DiaryEntity)->Unit){
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(top=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader("Дневник","История сохраняется на телефоне")}
        if(diary.isEmpty())item{SoftCard{Text("Дневник пуст");Spacer(Modifier.height(8.dp));Button(onClick=onScan){Text("Добавить по фото")}}}
        items(diary,key={it.id}){e->SoftCard{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(e.title,fontWeight=FontWeight.Bold);Text("${e.calories} ккал · Б ${e.protein} · Ж ${e.fat} · У ${e.carbs} г",color=Muted)};IconButton({onDelete(e)}){Icon(Icons.Default.Delete,"Удалить")}}}}
    }
}

@Composable private fun ProfileScreen(pad:PaddingValues,keySaved:Boolean,showSettings:Boolean,onToggle:()->Unit,key:String,onKey:(String)->Unit,onSave:()->Unit,onDelete:()->Unit){
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(top=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader("Профиль","Настройки приложения")}
        item{SoftCard{Text("Подход",fontWeight=FontWeight.Bold);Text("Приложение помогает вести дневник питания и замечать привычки. Оно не оценивает внешность и не задаёт строгие ограничения питания.",color=Muted)}}
        item{OutlinedButton(onClick=onToggle,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Key,null);Spacer(Modifier.width(7.dp));Text("AI · ${if(keySaved)"ключ сохранён" else "нужен ключ"}")}}
        if(showSettings)item{SoftCard{OutlinedTextField(key,onKey,label={Text("OpenAI API key")},singleLine=true,modifier=Modifier.fillMaxWidth());Text("Ключ шифруется локально через Android Keystore.",fontSize=12.sp,color=Muted);Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=onSave,enabled=key.isNotBlank()){Text("Сохранить")};OutlinedButton(onClick=onDelete){Text("Удалить")}}}}
        item{Text("AI-оценки еды приблизительные. При аллергии, медицинских состояниях или специальных требованиях к питанию ориентируйся на рекомендации врача или родителя, а не на приложение.",fontSize=12.sp,color=Muted)}
    }
}

fun createTempImageUri(context:Context):Uri{val dir=File(context.cacheDir,"images").apply{mkdirs()};val file=File.createTempFile("meal_",".jpg",dir);return FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)}

object OpenAiVision {
    suspend fun analyze(context:Context,uri:Uri,mode:ScanMode,apiKey:String):AiResult=withContext(Dispatchers.IO){
        val base64=imageAsBase64(context,uri)
        val instruction=if(mode==ScanMode.FOOD) "Определи только видимые блюда и продукты. Приблизительно оцени массу и КБЖУ каждого элемента." else "Определи видимые продукты и предложи до 4 простых идей блюд из подходящих видимых продуктов. Не выдумывай содержимое закрытых упаковок."
        val prompt=instruction+" Верни только JSON с полями items, note, ideas. items — массив объектов name, grams, calories, protein, fat, carbs, confidence. Не давай медицинских советов, не оценивай тело или внешность и не предлагай ограничительные диеты. Если число неизвестно, используй null."
        val content=JSONArray().put(JSONObject().put("type","input_text").put("text",prompt)).put(JSONObject().put("type","input_image").put("image_url","data:image/jpeg;base64,$base64").put("detail","low"))
        val input=JSONArray().put(JSONObject().put("role","user").put("content",content))
        val body=JSONObject().put("model","gpt-6-luna").put("input",input).put("max_output_tokens",1400)
        val conn=(URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;setRequestProperty("Authorization","Bearer $apiKey");setRequestProperty("Content-Type","application/json");connectTimeout=30000;readTimeout=60000}
        try {
            conn.outputStream.use{it.write(body.toString().toByteArray())}
            val code=conn.responseCode
            val stream=if(code in 200..299)conn.inputStream else conn.errorStream
            val responseText=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299) error("OpenAI HTTP $code: ${responseText.take(220)}")
            val outputText=extractOutputText(JSONObject(responseText))
            parseAiJson(outputText)
        } finally { conn.disconnect() }
    }

    internal fun parseAiJson(raw:String):AiResult {
        val clean=raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed=JSONObject(clean)
        val found=mutableListOf<DetectedItem>()
        val arr=parsed.optJSONArray("items")?:JSONArray()
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue
            fun nullableInt(key:String):Int?=if(o.isNull(key)||!o.has(key))null else o.optInt(key)
            val confidence=if(o.isNull("confidence")||!o.has("confidence"))null else o.optDouble("confidence")
            found.add(DetectedItem(o.optString("name","Продукт"),nullableInt("grams"),nullableInt("calories"),nullableInt("protein"),nullableInt("fat"),nullableInt("carbs"),confidence))
        }
        val ideaArray=parsed.optJSONArray("ideas")?:JSONArray()
        val ideaList=mutableListOf<String>()
        for(i in 0 until ideaArray.length()){val idea=ideaArray.optString(i).trim();if(idea.isNotBlank())ideaList.add(idea)}
        return AiResult(found,parsed.optString("note",""),ideaList)
    }

    private fun extractOutputText(root:JSONObject):String{
        val output=root.optJSONArray("output")?:error("В ответе API нет output")
        for(i in 0 until output.length()){
            val content=output.optJSONObject(i)?.optJSONArray("content")?:continue
            for(j in 0 until content.length()){
                val part=content.optJSONObject(j)?:continue
                if(part.optString("type")=="output_text")return part.optString("text")
            }
        }
        error("Модель не вернула текстовый результат")
    }

    private fun imageAsBase64(context:Context,uri:Uri):String{
        val bytes=context.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Не удалось открыть изображение")
        val original=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Неверный формат изображения")
        val scale=minOf(1f,1280f/maxOf(original.width,original.height))
        val bitmap=if(scale<1f)Bitmap.createScaledBitmap(original,(original.width*scale).toInt(),(original.height*scale).toInt(),true) else original
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,78,out)
        if(bitmap!==original)bitmap.recycle();original.recycle()
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
    }
}

object SecureKeyStore {
    private const val KS="AndroidKeyStore";private const val ALIAS="myration_openai_key";private const val PREFS="secure_ai_settings";private const val CT="api_key_cipher";private const val IV="api_key_iv"
    private fun secretKey():SecretKey{val ks=KeyStore.getInstance(KS).apply{load(null)};(ks.getKey(ALIAS,null) as? SecretKey)?.let{return it};val g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,KS);g.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return g.generateKey()}
    fun save(context:Context,value:String){val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secretKey());val encrypted=c.doFinal(value.toByteArray());context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(CT,Base64.encodeToString(encrypted,Base64.NO_WRAP)).putString(IV,Base64.encodeToString(c.iv,Base64.NO_WRAP)).apply()}
    fun load(context:Context):String?=runCatching{val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val enc=p.getString(CT,null)?:return null;val iv=p.getString(IV,null)?:return null;val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));String(c.doFinal(Base64.decode(enc,Base64.NO_WRAP)))}.getOrNull()
    fun hasKey(context:Context)=!load(context).isNullOrBlank()
    fun delete(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply()}
}
