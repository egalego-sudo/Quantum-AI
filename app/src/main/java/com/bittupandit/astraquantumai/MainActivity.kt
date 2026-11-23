package com.bittupandit.astraquantumai

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

// QUANTUM AI ChatGPT-inspired neutral charcoal palette.
private val ink = Color(0xFF212121)
private val charcoal = Color(0xFF2F2F2F)
private val graphite = Color(0xFF3A3A3A)
private val paper = Color(0xFF212121)
private val white = Color(0xFFECECEC)
private val muted = Color(0xFFB4B4B4)
private val line = Color(0xFF454545)
private val violet = Color(0xFF8E8EA0)
private val mint = Color(0xFFD6D6D6)
private const val TRUSTED_BACKEND_URL = "https://astraquantum-ai.onrender.com"

class MainActivity : FragmentActivity() {
    private var speechText by mutableStateOf("")
    private var pickedFile by mutableStateOf("")
    private var pickedMime by mutableStateOf("")
    private var pickedUri by mutableStateOf<Uri?>(null)
    private var speaking: TextToSpeech? = null
    private var deviceUnlocked by mutableStateOf(false)
    private var securityMessage by mutableStateOf("Fingerprint या फोन का Screen Lock सत्यापित करें।")
    private var needsUnlock = true
    private var promptInProgress = false
    private var authPrompt: BiometricPrompt? = null
    private var authPromptInfo: BiometricPrompt.PromptInfo? = null

    private fun isAppLockEnabled(): Boolean = getSharedPreferences("quantum_app_security", Context.MODE_PRIVATE).getBoolean("app_lock_enabled", true)

    private fun authenticateDevice() {
        if (!isAppLockEnabled()) {
            promptInProgress = false
            needsUnlock = false
            deviceUnlocked = true
            return
        }
        if (promptInProgress || deviceUnlocked) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            val prompt = authPrompt
            val info = authPromptInfo
            if (prompt != null && info != null) {
                promptInProgress = true
                prompt.authenticate(info)
            }
        } else {
            securityMessage = "पहले फोन की Settings में Screen Lock (PIN / Pattern / Password) सेट करें।"
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && isAppLockEnabled()) {
            deviceUnlocked = false
            needsUnlock = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isAppLockEnabled()) {
            deviceUnlocked = true
            needsUnlock = false
        } else if (needsUnlock && !promptInProgress) authenticateDevice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaking = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) speaking?.language = Locale("hi", "IN")
        }
        val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                speechText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            }
        }
        val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                pickedUri = it
                pickedFile = it.lastPathSegment ?: it.toString()
                pickedMime = contentResolver.getType(it) ?: "application/octet-stream"
            }
        }
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        authPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptInProgress = false
                needsUnlock = false
                deviceUnlocked = true
                securityMessage = ""
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptInProgress = false
                deviceUnlocked = false
                needsUnlock = false
                securityMessage = errString.toString()
            }
            override fun onAuthenticationFailed() { securityMessage = "पहचान नहीं हुई। दोबारा प्रयास करें।" }
        })
        authPromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("QUANTUM AI सुरक्षा लॉक")
            .setSubtitle("Fingerprint या फोन का PIN / Pattern / Password")
            .setAllowedAuthenticators(authenticators)
            .build()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = white, onPrimary = ink, secondary = violet,
                background = ink, surface = charcoal, onSurface = white
            )) {
                if (deviceUnlocked) AstraApp(
                    activity = this,
                    speechText = speechText,
                    pickedFile = pickedFile,
                    pickedMime = pickedMime,
                    pickedUri = pickedUri,
                    onSpeak = { text -> speaking?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "astra_reply") },
                    onListen = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "QUANTUM AI से हिंदी में बोलकर पूछें")
                        }
                        try {
                            if (intent.resolveActivity(packageManager) != null) speechLauncher.launch(intent)
                            else Toast.makeText(this, "फोन में Speech Recognition सेवा उपलब्ध नहीं है।", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Voice Search शुरू नहीं हो सका: " + (e.localizedMessage ?: "सेवा उपलब्ध नहीं"), Toast.LENGTH_LONG).show()
                        }
                    },
                    onPickFile = { fileLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) }
                ) else SecurityGate(message = securityMessage, onUnlock = { authenticateDevice() })
            }
        }
        authenticateDevice()
    }

    override fun onDestroy() {
        speaking?.stop()
        speaking?.shutdown()
        super.onDestroy()
    }
}


internal fun securePreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    val secure = EncryptedSharedPreferences.create(context, "quantum_private_secure", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val legacy = context.getSharedPreferences("astra_private", Context.MODE_PRIVATE)
    migrateLegacyPreferences(secure, legacy)
    return secure
}
private object AppSecurity { @Volatile var accessToken: String = "" }
@Composable
private fun SecurityGate(message: String, onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(ink), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("QUANTUM AI", color = white, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("SECURITY LOCK", color = mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(message, color = white, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onUnlock) { Text("Fingerprint / Screen Lock से खोलें") }
            Text("Creator by Bittu Pandit", color = muted, fontSize = 11.sp)
        }
    }
}

private data class ChatLine(val from: String, val text: String)

private fun restoreChatHistory(raw: String?): List<ChatLine> {
    if (raw.isNullOrBlank()) return listOf(ChatLine("QUANTUM AI", "नमस्ते! मैं QUANTUM AI हूँ। अपना सवाल पूछिए—मैं Groq AI से उत्तर तैयार करूँगा।"))
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ChatLine(item.optString("from", "QUANTUM AI"), item.optString("text"))
        }.filter { it.text.isNotBlank() }.ifEmpty {
            listOf(ChatLine("QUANTUM AI", "नमस्ते! मैं QUANTUM AI हूँ। अपना सवाल पूछिए।"))
        }
    }.getOrElse { listOf(ChatLine("QUANTUM AI", "पुरानी चैट पढ़ी नहीं जा सकी। नई बातचीत शुरू करें।")) }
}

private fun encodeChatHistory(chat: List<ChatLine>): String {
    val array = JSONArray()
    chat.takeLast(100).forEach { item ->
        array.put(JSONObject().put("from", item.from).put("text", item.text))
    }
    return array.toString()
}

private data class AstraModule(val title: String, val subtitle: String, val symbol: String)

@Composable
private fun AstraApp(
    activity: MainActivity,
    speechText: String,
    pickedFile: String,
    pickedMime: String,
    pickedUri: Uri?,
    onSpeak: (String) -> Unit,
    onListen: () -> Unit,
    onPickFile: () -> Unit
) {
    val context = LocalContext.current
    val markdownRenderer = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
    }
    val prefs = remember { securePreferences(context) }
    AppSecurity.accessToken = prefs.getString("api_access_token", "") ?: ""
    var apiAccessToken by remember { mutableStateOf(prefs.getString("api_access_token", "") ?: "") }
    val appSecurityPrefs = remember { context.getSharedPreferences("quantum_app_security", Context.MODE_PRIVATE) }
    var appLockEnabled by remember { mutableStateOf(appSecurityPrefs.getBoolean("app_lock_enabled", true)) }
    val offlineEngine = remember { OfflineAiEngine(context) }
    var page by remember { mutableStateOf("Home") }
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1800); showSplash = false }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("GROQ AI · READY") }
    var backend by remember { mutableStateOf(TRUSTED_BACKEND_URL) }
    var memoryInput by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf(prefs.getString("memories", "") ?: "") }
    var memoryDraft by remember { mutableStateOf(prefs.getString("memories", "") ?: "") }
    var useMemoryInChat by remember { mutableStateOf(prefs.getBoolean("use_memory_in_chat", false)) }
    var projectInput by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(prefs.getString("projects", "") ?: "") }
    var chat by remember { mutableStateOf(restoreChatHistory(prefs.getString("chat_history", null))) }
    var qubitAngle by remember { mutableFloatStateOf(45f) }
    var qubitCount by remember { mutableIntStateOf(4) }
    var qubitResult by remember { mutableStateOf("Qubit तैयार है। Qubit संख्या और कोण चुनकर गणना करें।") }
    var fileAnalysis by remember { mutableStateOf("") }
    var fileBusy by remember { mutableStateOf(false) }
    var offlineProgress by remember { mutableIntStateOf(0) }
    var offlineStatus by remember { mutableStateOf("मॉडल अभी डाउनलोड नहीं हुआ है।") }
    var offlineBusy by remember { mutableStateOf(false) }
    var offlineTestPrompt by remember { mutableStateOf("नमस्ते QUANTUM AI, अपना परिचय दीजिए।") }
    var offlineTestAnswer by remember { mutableStateOf("") }
    var researchResult by remember { mutableStateOf("") }
    var researchBusy by remember { mutableStateOf(false) }
    var useDeepResearchInChat by remember { mutableStateOf(false) }
    var scienceQuestion by remember { mutableStateOf("") }
    var scienceAnswer by remember { mutableStateOf("") }
    var scienceBusy by remember { mutableStateOf(false) }
    var scienceResearchBusy by remember { mutableStateOf(false) }
    var actionRequest by remember { mutableStateOf("") }
    var actionPlan by remember { mutableStateOf("") }
    var actionBusy by remember { mutableStateOf(false) }
    var confirmSaveActionPlan by remember { mutableStateOf(false) }

    LaunchedEffect(speechText) { if (speechText.isNotBlank()) { prompt = speechText; page = "AI Chat" } }
    LaunchedEffect(chat) { prefs.edit().putString("chat_history", encodeChatHistory(chat)).apply() }
    LaunchedEffect(Unit) {
        offlineStatus = if (offlineEngine.isModelInstalled) "ऑफलाइन मॉडल फोन में उपलब्ध है।" else "पहली बार लगभग 328 MiB मॉडल डाउनलोड करना होगा।"
    }
    DisposableEffect(offlineEngine) {
        onDispose { offlineEngine.close() }
    }
    var healthBusy by remember { mutableStateOf(false) }
    var quantumBackendName by remember { mutableStateOf("") }
    var quantumJobId by remember { mutableStateOf("") }
    var quantumHardwareResult by remember { mutableStateOf("") }
    var quantumHardwareBusy by remember { mutableStateOf(false) }
    var confirmQuantumJob by remember { mutableStateOf(false) }

    fun copyText(value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("QUANTUM AI", value))
        Toast.makeText(context, "कॉपी हो गया", Toast.LENGTH_SHORT).show()
    }
    fun addMemory(value: String) {
        val note = value.trim()
        if (note.isBlank()) return
        memories = if (memories.isBlank()) "• " + note else memories.trimEnd() + "\n• " + note
        prefs.edit().putString("memories", memories).commit()
        memoryDraft = memories
        memoryInput = ""
        Toast.makeText(context, "Memory सुरक्षित हो गई", Toast.LENGTH_SHORT).show()
    }
    fun addProject(value: String) {
        projects = (projects + if (projects.isBlank()) "" else "\n" + "• " + value).trim()
        prefs.edit().putString("projects", projects).commit()
        projectInput = ""
    }

    Box(Modifier.fillMaxSize().background(ink)) {
        Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF2F2F2F)).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(white), contentAlignment = Alignment.Center) {
                    Text("✦", color = ink, fontWeight = FontWeight.Black, fontSize = 24.sp)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("QUANTUM", color = white, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("AI", color = muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("Creator by Bittu Pandit", color = mint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
                Surface(color = Color(0xFF383838), shape = CircleShape) {
                    Text("✦ GROQ", color = mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().testTag("main_navigation").horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Home", "AI Chat", "Benchmark", "App Builder", "Website Builder", "Quantum", "Cores", "Science Lab", "Calculator", "Science QA", "Offline AI", "Research", "Action", "Memory", "Voice", "Files", "Projects", "Updates", "Settings").forEach { item ->
                    val selected = page == item
                    Surface(
                        onClick = { page = item },
                        color = if (selected) white else charcoal,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(item, color = if (selected) ink else muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = line)
        when (page) {
            "Benchmark" -> BenchmarkScreen(backend)
            "App Builder" -> InstantAppBuilderScreen(backend, apiAccessToken, initialWebsiteMode = false)
            "Website Builder" -> InstantAppBuilderScreen(backend, apiAccessToken, initialWebsiteMode = true)
            "Cores" -> SimplePage("QUANTUM AI FIVE CORE ARCHITECTURE", "पाँचों Core मिलकर QUANTUM AI का काम बाँटते हैं। किसी सुविधा की उपलब्धता उसके वास्तविक मॉड्यूल और डिवाइस/सर्वर समर्थन पर निर्भर है।") {
                val coreCards = listOf(
                    Triple("01 · PERCEPTION CORE", "देखना • सुनना • दस्तावेज़ समझना", "Files" to "इमेज/PDF विश्लेषण और Voice इनपुट"),
                    Triple("02 · REASONING CORE", "तर्क • विश्लेषण • समस्या समाधान", "AI Chat" to "ऑनलाइन AI; स्थानीय मॉडल अलग Offline AI स्क्रीन में"),
                    Triple("03 · KNOWLEDGE CORE", "स्मृति • शोध • स्रोत जाँच", "Research" to "Wikipedia/Crossref शोध और फोन में निजी Memory"),
                    Triple("04 · ACTION CORE", "योजना • कार्य-सूची • अनुमति", "Action" to "समीक्षा योग्य योजना और अनुमति-आधारित स्थानीय checkpoint"),
                    Triple("05 · QUANTUM LEARNING CORE", "क्वांटम सिद्धांत • गणित • सिमुलेशन", "Quantum" to "क्लासिकल product-state simulator; IBM connector अलग")
                )
                val coreTints = listOf(Color(0xFF383838), Color(0xFF414141), Color(0xFF343434), Color(0xFF454545), Color(0xFF3B3B3B))
                coreCards.forEachIndexed { index, core ->
                    Card(
                        onClick = { page = core.third.first },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = coreTints[index].copy(alpha = .92f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(core.first, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text(core.second, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(core.third.second, color = Color.White.copy(alpha = .88f), fontSize = 11.sp)
                            Text("खोलें  →", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("ईमानदार क्षमता स्थिति", color = mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("• इमेज समझना अभी ऑनलाइन Vision पर निर्भर है; Offline Vision उपलब्ध नहीं।\n• आवाज़ की पहचान फोन के speech provider पर निर्भर है।\n• स्रोत जुटाना और AI रिपोर्ट बनाना उपलब्ध है, पर स्वतः निर्णायक सत्यापन नहीं।\n• Action Core अभी सुरक्षित planning/workspace स्तर पर है; कोई संदेश, भुगतान या फोन-सेटिंग बिना स्पष्ट अनुमति नहीं की जाती।\n• Quantum Learning अभी शैक्षिक classical simulation है; वास्तविक QPU के लिए IBM credentials और अलग पुष्टि चाहिए।", color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("QUANTUM AI INTELLIGENCE LAYER", color = Color(0xFFD6D6D6), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                val intelligenceCards = listOf(
                    "EVIDENCE INTELLIGENCE" to "रिपोर्ट में समर्थित तथ्य, स्रोत-संदर्भ, संभावित विरोधाभास और अनिश्चितता अलग दिखाने का नियम। स्रोत-जाँच अभी सीमित Wikipedia/Crossref डेटा पर आधारित है।",
                    "LONG-TERM PERSONAL INTELLIGENCE" to "आपकी अनुमति पर Saved Memory, Projects और हाल की बातचीत का संदर्भ AI को दिया जाता है; इससे पिछला काम आगे बढ़ाने में मदद मिलती है।",
                    "ACTION INTELLIGENCE" to "अभी Projects workspace में योजना और checkpoint सेव करें। जुड़े हुए बाहरी टूल में स्वतः काम पूरा करना अभी विकसित होना बाकी है।",
                    "INDEPENDENT OFFLINE INTELLIGENCE" to "Qwen3-0.6B स्थानीय text model इंस्टॉल होने पर नेटवर्क विफलता में text chat fallback देता है। Offline image/voice/document intelligence अभी उपलब्ध नहीं।",
                    "MULTIMODAL INTELLIGENCE" to "Voice-to-text, image + prompt, और PDF text analysis उपलब्ध हैं; एक ही संयुक्त multi-file multimodal session अभी सीमित है।"
                )
                intelligenceCards.forEach { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF303030)), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.first, color = mint, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp)
                            Text(item.second, color = Color(0xFFE1E7FF), fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
            "Home" -> {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(white, violet, mint))), contentAlignment = Alignment.Center) {
                            Text("Q", color = ink, fontSize = 27.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("QUANTUM", color = white, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                            Text("AI", color = Color(0xFFFFD86B), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                        }
                        Surface(color = Color(0xAA152448), shape = CircleShape) {
                            Text("✦ AI", color = mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2F2F2F)), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(194.dp)) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF303030)))
                            Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                                Text("WELCOME TO", color = mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text("YOUR UNIVERSE", color = white, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                Text("Explore beyond limits", color = Color(0xFFE1E7FF), fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2F2F2F)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⌕", color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text("Ask QUANTUM AI anything", color = white, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onListen) { Text("🎙", color = mint, fontSize = 19.sp) }
                            }
                            OutlinedTextField(
                                value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search or ask anything…", color = muted) },
                                shape = RoundedCornerShape(14.dp), minLines = 1, maxLines = 2,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = white, unfocusedTextColor = white,
                                    focusedBorderColor = mint, unfocusedBorderColor = line,
                                    cursorColor = mint, focusedContainerColor = Color(0xFF303030), unfocusedContainerColor = Color(0x66101834)
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(enabled = prompt.isNotBlank(), onClick = { page = "AI Chat" }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A), contentColor = Color.White), shape = RoundedCornerShape(13.dp)) {
                                Text("ASK QUANTUM AI  →", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(17.dp))
                    Text("CENTRAL CONTROL", color = mint, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.height(9.dp))
                    val controlModules = listOf(
                        Triple("AI CHAT", "बातचीत और उत्तर", "AI Chat"),
                        Triple("APP BUILDER", "ऐप प्रोटोटाइप बनाएँ", "App Builder"),
                        Triple("WEBSITE BUILDER", "वेबसाइट और Preview", "Website Builder"),
                        Triple("QUANTUM LAB", "क्वांटम सिमुलेशन", "Quantum"),
                        Triple("PROJECTS", "आपके सेव किए प्रोजेक्ट और नोट्स", "Projects"),
                        Triple("UPDATES", "नया QUANTUM AI संस्करण जाँचें", "Updates")
                    )
                    controlModules.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { item ->
                                Card(
                                    onClick = { page = item.third },
                                    modifier = Modifier.weight(1f).height(88.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF383838)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
                                        Text(item.first, color = white, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = .5.sp)
                                        Spacer(Modifier.height(5.dp))
                                        Text(item.second, color = muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("EXPLORE QUANTUM AI", color = Color(0xFFFFD86B), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    val modules = listOf(
                        AstraModule("Smart Search", "Find anything", "⌕"),
                        AstraModule("AI Answers", "Ask AI anything", "✧"),
                        AstraModule("Deep Research", "Explore topics", "◉"),
                        AstraModule("Source Verification", "Check facts", "✓"),
                        AstraModule("Quantum Lab", "Quantum learning", "⚛"),
                        AstraModule("More Tools", "Your AI workspace", "✦")
                    )
                    val tileColors = listOf(Color(0xFF303030), Color(0xFF383838), Color(0xFF414141), Color(0xFF343434), Color(0xFF454545), Color(0xFF3A3A3A))
                    modules.chunked(2).forEachIndexed { rowIndex, pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEachIndexed { colIndex, module ->
                                val target = when (module.title) {
                                    "Smart Search", "Deep Research", "Source Verification" -> "Research"
                                    "AI Answers" -> "AI Chat"
                                    "Quantum Lab" -> "Quantum"
                                    "More Tools" -> "App Builder"
                                    else -> "Projects"
                                }
                                val tint = tileColors[rowIndex * 2 + colIndex]
                                Card(onClick = { page = target }, modifier = Modifier.weight(1f).height(104.dp), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = .94f)), shape = RoundedCornerShape(19.dp)) {
                                    Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(module.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                            Spacer(Modifier.height(4.dp))
                                            Text(module.subtitle, color = Color.White.copy(alpha = .88f), fontSize = 9.sp)
                                        }
                                        Text(module.symbol, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            }
                        }
                        if (rowIndex < 2) Spacer(Modifier.height(9.dp))
                    }
                    Spacer(Modifier.height(15.dp))
                    Text("Creator by Bittu Pandit", color = mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                }
            }
            "AI Chat" -> {
                Column(Modifier.weight(1f).fillMaxWidth().background(Color.Transparent).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("AI CONVERSATION", color = white, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(status, color = muted, fontSize = 9.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (useMemoryInChat) "संदर्भ चालू" else "संदर्भ बंद", color = if (useMemoryInChat) mint else muted, fontSize = 9.sp)
                            Switch(checked = useMemoryInChat, onCheckedChange = {
                                useMemoryInChat = it
                                prefs.edit().putBoolean("use_memory_in_chat", it).apply()
                            })
                            TextButton(onClick = { chat = listOf(ChatLine("QUANTUM AI", "नई बातचीत शुरू हुई। अपना सवाल पूछिए।")) }) { Text("नई चैट", color = muted) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { useDeepResearchInChat = !useDeepResearchInChat },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (useDeepResearchInChat) mint else muted)
                        ) {
                            Text(if (useDeepResearchInChat) "✓ गहन शोध चालू" else "गहन शोध मोड", fontSize = 11.sp)
                        }
                        Text(
                            if (useDeepResearchInChat) "5 सार्वजनिक स्रोतों में सीमित खोज" else "सामान्य AI बातचीत",
                            color = muted, fontSize = 9.sp
                        )
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                        items(chat) { item ->
                            val isAi = item.from == "QUANTUM AI" || item.from == "ASTRA"
                            Row(Modifier.fillMaxWidth().testTag("chat_message_${item.text}"), horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End) {
                                if (isAi) {
                                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF444444)), contentAlignment = Alignment.Center) {
                                        Text("Q", color = white, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(if (isAi) 8.dp else 20.dp)).background(if (isAi) Color(0xAA182747) else Color(0xCC123A52)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                                    if (isAi) Text("QUANTUM AI", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    AndroidView(
                                        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                                        factory = { viewContext ->
                                            TextView(viewContext).apply {
                                                setTextColor(android.graphics.Color.WHITE)
                                                textSize = 14f
                                                setLineSpacing(0f, 1.28f)
                                                setTextIsSelectable(true)
                                                setLinkTextColor(android.graphics.Color.rgb(150, 205, 255))
                                            }
                                        },
                                        update = { textView -> markdownRenderer.setMarkdown(textView, item.text) }
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { copyText(item.text) }, contentPadding = PaddingValues(horizontal = 3.dp)) { Text("कॉपी", color = muted, fontSize = 10.sp) }
                                        if (isAi) TextButton(onClick = { onSpeak(item.text) }, contentPadding = PaddingValues(horizontal = 3.dp)) { Text("सुनें", color = muted, fontSize = 10.sp) }
                                    }
                                }
                            }
                        }
                    }
                    if (pickedUri != null && pickedMime.startsWith("image/")) {
                        Text("📷 फोटो तैयार: $pickedFile", color = mint, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onPickFile, shape = CircleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = white), contentPadding = PaddingValues(horizontal = 9.dp), modifier = Modifier.size(42.dp)) { Text("+", fontSize = 22.sp) }
                        OutlinedButton(onClick = onListen, shape = CircleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = white), contentPadding = PaddingValues(horizontal = 9.dp), modifier = Modifier.size(42.dp)) { Text("🎙", fontSize = 15.sp) }
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("QUANTUM AI से कुछ भी पूछें", color = muted, fontSize = 13.sp) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = white, unfocusedTextColor = white,
                                focusedBorderColor = mint, unfocusedBorderColor = line,
                                focusedContainerColor = Color(0xCC101A34), unfocusedContainerColor = Color(0xCC101A34),
                                cursorColor = mint
                            )
                        )
                        Button(
                            enabled = (prompt.isNotBlank() || (pickedUri != null && pickedMime.startsWith("image/"))) && !busy,
                            onClick = {
                                val imageUri = pickedUri?.takeIf { pickedMime in listOf("image/jpeg", "image/png", "image/webp") }
                                val deepResearchMode = useDeepResearchInChat && imageUri == null
                                val question = prompt.trim().ifBlank { "इस फोटो में क्या दिखाई दे रहा है? विस्तार से हिंदी में विश्लेषण करें।" }
                                val rawQuestionWithMemory = ContextIntelligence.buildPrompt(
                                    question = question,
                                    history = chat.map { ContextTurn(it.from, it.text) },
                                    memories = memories,
                                    projects = projects,
                                    includePersonalContext = useMemoryInChat
                                )
                                val questionWithMemory = ScientificIntelligence.enhancePrompt(rawQuestionWithMemory)
                                chat = chat + ChatLine("You", question + if (imageUri != null) "  [फोटो: $pickedFile]" else "")
                                prompt = ""
                                busy = true
                                status = when {
                                    imageUri != null -> "VISION AI फोटो का विश्लेषण कर रहा है…"
                                    deepResearchMode -> "DEEP RESEARCH · 6 PUBLIC SOURCES"
                                    else -> "HYBRID ROUTER · ONLINE/OFFLINE"
                                }
                                activity.lifecycleScope.launch {
                                    val answer = withContext(Dispatchers.IO) {
                                        val onlineResult = if (imageUri != null) {
                                            runCatching {
                                                val bytes = context.contentResolver.openInputStream(imageUri)?.use {
                                                    BoundedInputReader.read(it, 7 * 1024 * 1024, "फोटो 7 MB से छोटी रखें।")
                                                } ?: error("फोटो पढ़ी नहीं जा सकी")
                                                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                                askBackend(backend, questionWithMemory, pickedMime, encoded)
                                            }.getOrElse { "फोटो विश्लेषण विफल: ${it.message}" }
                                        } else if (deepResearchMode) {
                                            val researchAnswer = askResearch(backend, question.take(500))
                                            if (researchAnswer.contains("HTTP 422") ||
                                                researchAnswer.contains("पर्याप्त विषय-संगत स्रोत नहीं मिले")) {
                                                val directAnswer = askBackend(backend, questionWithMemory)
                                                if (directAnswer.startsWith("Groq सेवा में त्रुटि") ||
                                                    directAnswer.startsWith("QUANTUM AI सर्वर से कनेक्शन नहीं हो पाया")) {
                                                    directAnswer
                                                } else {
                                                    "सामान्य AI उत्तर (Deep Research में पर्याप्त स्रोत नहीं मिले; नीचे उत्तर स्रोत-सत्यापित शोध रिपोर्ट नहीं है):\n\n" + directAnswer
                                                }
                                            } else {
                                                researchAnswer
                                            }
                                        } else {
                                            askBackend(backend, questionWithMemory)
                                        }
                                        val remoteFailed = onlineResult.startsWith("QUANTUM AI सर्वर से कनेक्शन नहीं हो पाया") ||
                                            onlineResult.startsWith("QUANTUM AI से जुड़ने के लिए Backend HTTPS URL") ||
                                            onlineResult.startsWith("Groq सेवा में त्रुटि") ||
                                            onlineResult.startsWith("सुरक्षा के लिए backend URL") ||
                                            onlineResult.startsWith("Deep Research कनेक्शन विफल") ||
                                            onlineResult.startsWith("Deep Research त्रुटि")
                                        if (remoteFailed && imageUri == null && offlineEngine.isModelInstalled) {
                                            runCatching { "ON-DEVICE · QWEN3-0.6B\n" + offlineEngine.answer(questionWithMemory) }
                                                .getOrElse { "ऑनलाइन सेवा और स्थानीय मॉडल दोनों उपलब्ध नहीं हुए: ${it.message}" }
                                        } else if (remoteFailed && imageUri != null) {
                                            onlineResult + "\n\nOFFLINE VISION अभी QUANTUM AI में उपलब्ध नहीं है।"
                                        } else if (imageUri != null) {
                                            "ONLINE · GROQ VISION\n" + onlineResult
                                        } else if (deepResearchMode && onlineResult.startsWith("सामान्य AI उत्तर")) {
                                            "ONLINE · GROQ\n" + onlineResult
                                        } else if (deepResearchMode) {
                                            "ONLINE · GROQ DEEP RESEARCH\n" + onlineResult
                                        } else {
                                            "ONLINE · GROQ\n" + onlineResult
                                        }
                                    }
                                    chat = chat + ChatLine("QUANTUM AI", answer)
                                    busy = false
                                    status = when {
                                        answer.startsWith("ON-DEVICE") -> "OFFLINE AI · READY"
                                        answer.startsWith("ONLINE · GROQ DEEP RESEARCH") -> "DEEP RESEARCH · READY"
                                        else -> "HYBRID AI · READY"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.size(44.dp)
                        ) { Text(if (busy) "…" else "↑", fontWeight = FontWeight.Bold, fontSize = 19.sp) }
                    }
                }
            }
            "Science Lab" -> ScienceLabScreen()
            "Calculator" -> ExpressionCalculatorScreen()
            "Science QA" -> SimplePage("QUANTUM AI SCIENTIFIC QUESTION & REASONING", "वैज्ञानिक प्रश्न पूछें। QUANTUM AI उत्तर में स्थापित तथ्य, गणना/मॉडल, मान्यताएँ, सीमाएँ और अनिश्चितता अलग रखने का प्रयास करेगा। शोध-स्रोतों की जाँच अलग बटन से करें।") {
                OutlinedTextField(scienceQuestion, { scienceQuestion = it }, Modifier.fillMaxWidth(), label = { Text("विज्ञान का प्रश्न लिखें…") }, minLines = 2)
                Button(enabled = scienceQuestion.isNotBlank() && !scienceBusy, onClick = {
                    scienceBusy = true
                    scienceAnswer = "वैज्ञानिक विश्लेषण तैयार हो रहा है…"
                    val q = scienceQuestion.trim()
                    val scientificPrompt = """
                        आप QUANTUM AI Scientific Reasoning Assistant हैं। उपयोगकर्ता के प्रश्न का उत्तर हिंदी में दें।
                        अनिवार्य ढाँचा:
                        1. संक्षिप्त उत्तर।
                        2. स्थापित वैज्ञानिक तथ्य बनाम मॉडल/सैद्धांतिक व्याख्या।
                        3. जहाँ लागू हो, समीकरण, प्रतीक, SI इकाइयाँ और चरणबद्ध गणना।
                        4. मान्यताएँ और वैधता-सीमा।
                        5. विरोधी साक्ष्य, वैकल्पिक व्याख्या और क्या अज्ञात है।
                        6. विश्वास-स्तर का कारण; बिना स्रोत देखे स्रोत या प्रयोग गढ़ें नहीं।
                        नई परिकल्पना को खोज/सिद्ध तथ्य न कहें। यदि पर्याप्त डेटा नहीं है तो स्पष्ट कहें।
                        प्रश्न: $q
                    """.trimIndent()
                    activity.lifecycleScope.launch {
                        scienceAnswer = withContext(Dispatchers.IO) { askBackend(backend, scientificPrompt) }
                        scienceBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = violet, contentColor = Color.White)) {
                    Text(if (scienceBusy) "विश्लेषण जारी…" else "QUANTUM AI से वैज्ञानिक उत्तर लें")
                }
                OutlinedButton(enabled = scienceQuestion.isNotBlank() && !scienceResearchBusy, onClick = {
                    scienceResearchBusy = true
                    scienceAnswer = "वैज्ञानिक साहित्य और उपलब्ध Open Access दस्तावेज़ खोजे जा रहे हैं…"
                    val q = scienceQuestion.trim()
                    activity.lifecycleScope.launch {
                        scienceAnswer = withContext(Dispatchers.IO) { askResearch(backend, q) }
                        scienceResearchBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scienceResearchBusy) "शोध जारी…" else "शोध-पत्र और स्रोत जाँचें")
                }
                if (scienceAnswer.isNotBlank()) {
                    SelectionContainer { Text(scienceAnswer, color = white, lineHeight = 20.sp) }
                    TextButton(onClick = { copyText(scienceAnswer) }) { Text("उत्तर/रिपोर्ट कॉपी करें", color = mint) }
                }
                Text("सावधानी: AI उत्तर वैज्ञानिक निर्णय का विकल्प नहीं। वास्तविक खोज के लिए पुनरुत्पादन, डेटा और स्वतंत्र विशेषज्ञ समीक्षा आवश्यक है।", color = muted, fontSize = 10.sp)
                val modules = listOf(
                    "01 · DEEP REASONING" to "वास्तविक प्रश्न, लक्ष्य, बाधाएँ और मूल कारण पहचानना; तथ्य, मान्यताएँ और अज्ञात अलग रखना।",
                    "02 · MULTI-LAYER THINKING" to "वैज्ञानिक, गणितीय और व्यावहारिक दृष्टिकोण जोड़ना।",
                    "03 · SELF-CORRECTION" to "विरोधाभास, छूटे चरण और गणना त्रुटि जाँचना।",
                    "04 · SCIENTIFIC VERIFICATION" to "स्थापित तथ्य, मॉडल-निर्भर निष्कर्ष, परिकल्पना और अज्ञात अलग रखना।",
                    "05 · CREATIVE INTELLIGENCE" to "वैकल्पिक समाधान और परिकल्पनाएँ बनाना, बिना उन्हें प्रमाणित खोज बताना।",
                    "06 · CONTEXT & MEMORY REASONING" to "उपलब्ध संदर्भ का उपयोग; निजी नोट्स को सार्वजनिक प्रमाण न मानना।",
                    "07 · STRATEGIC PLANNING" to "लक्ष्य को जाँचने योग्य चरणों और सफलता-मापदंडों में बाँटना।"
                )
                modules.forEach { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF303030)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.first, color = mint, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            Text(item.second, color = white, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
            "Research" -> SimplePage("DEEP RESEARCH ENGINE", "Wikipedia, Crossref, OpenAlex, Europe PMC, arXiv और Semantic Scholar में समानांतर शोध खोज। उपलब्ध Open Access full text पढ़ने का प्रयास, स्रोतों की तुलना और प्रमाण-सहित रिपोर्ट। यह पूरे इंटरनेट की exhaustive खोज या 100% सत्यता की गारंटी नहीं है।") {
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("किस विषय या दावे की जाँच करें?") })
                Text("खोज के स्रोत", color = mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Button(enabled = prompt.isNotBlank() && !researchBusy, onClick = {
                    researchBusy = true
                    researchResult = "छह सार्वजनिक शोध-स्रोतों में खोज, DOI रिकॉर्ड मिलान और उपलब्ध Open Access full text की जाँच जारी है… यह कुछ समय ले सकता है।"
                    val query = prompt.trim()
                    activity.lifecycleScope.launch {
                        researchResult = withContext(Dispatchers.IO) { askResearch(backend, query) }
                        researchBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = violet, contentColor = Color.White)) {
                    Text(if (researchBusy) "गहन शोध जारी…" else "QUANTUM AI Deep Research + Source Check")
                }
                if (researchResult.isNotBlank()) {
                    SelectionContainer { Text(researchResult, color = white, lineHeight = 20.sp) }
                    val researchLinks = Regex("https?://[^\\s)]+").findAll(researchResult)
                        .map { it.value.trimEnd('.', ',', ';', ')', ']') }
                        .distinct().filter(ExternalLinkPolicy::allows).take(12).toList()
                    if (researchLinks.isNotEmpty()) {
                        Text("स्रोत सीधे खोलें", color = mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        researchLinks.forEachIndexed { index, link ->
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                                    .onFailure { Toast.makeText(context, "स्रोत नहीं खुल पाया", Toast.LENGTH_SHORT).show() }
                            }) { Text("स्रोत ${index + 1} खोलें ↗", color = mint) }
                        }
                    }
                    TextButton(onClick = { copyText(researchResult) }) { Text("शोध रिपोर्ट कॉपी करें", color = mint) }
                }
                Button(enabled = prompt.isNotBlank(), onClick = {
                    val q = URLEncoder.encode(prompt.trim(), "UTF-8")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + q))) }
                        .onFailure { Toast.makeText(context, "ब्राउज़र नहीं खुल पाया", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text("Google पर खोजें") }
                OutlinedButton(enabled = prompt.isNotBlank(), onClick = {
                    val q = URLEncoder.encode(prompt.trim(), "UTF-8")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + q))) }
                        .onFailure { Toast.makeText(context, "ब्राउज़र नहीं खुल पाया", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Bing पर खोजें", color = white) }
                OutlinedButton(enabled = prompt.isNotBlank(), onClick = {
                    val q = URLEncoder.encode(prompt.trim(), "UTF-8")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://scholar.google.com/scholar?q=" + q))) }
                        .onFailure { Toast.makeText(context, "ब्राउज़र नहीं खुल पाया", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Google Scholar · शोध पत्र", color = white) }
                OutlinedButton(enabled = prompt.isNotBlank(), onClick = {
                    val q = URLEncoder.encode("fact check " + prompt.trim(), "UTF-8")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + q))) }
                        .onFailure { Toast.makeText(context, "ब्राउज़र नहीं खुल पाया", Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Fact Check · दावे की जाँच", color = white) }
                Text("ध्यान दें: ये खोज पेज खोलते हैं; QUANTUM AI अभी स्रोतों की स्वतंत्र सत्यता अपने-आप प्रमाणित नहीं करता।", color = muted, fontSize = 11.sp)
            }
            "Memory" -> SimplePage("ADVANCED PRIVATE MEMORY", "QUANTUM AI आपकी लिखी हुई यादें इसी फोन में सुरक्षित रखता है। Memory को AI चैट में भेजना अलग से आपकी अनुमति पर निर्भर है।") {
                OutlinedTextField(memoryInput, { memoryInput = it }, Modifier.fillMaxWidth(), label = { Text("व्यक्तिगत तथ्य, पसंद या प्रोजेक्ट नोट") }, minLines = 2)
                Button(enabled = memoryInput.isNotBlank(), onClick = { addMemory(memoryInput) }, colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text("Memory में सेव करें") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("AI चैट में Personal Context इस्तेमाल करें", color = white, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("चालू करने पर Saved Memory, Projects और हाल की चैट का सीमित संदर्भ चुने गए सवाल के साथ QUANTUM AI backend को भेजा जाएगा।", color = muted, fontSize = 10.sp)
                    }
                    Switch(checked = useMemoryInChat, onCheckedChange = {
                        useMemoryInChat = it
                        prefs.edit().putBoolean("use_memory_in_chat", it).apply()
                    })
                }
                Text("सेव की हुई Memory देखें, सुधारें या किसी नोट को हटाएँ:", color = white, fontSize = 11.sp)
                OutlinedTextField(
                    value = memoryDraft,
                    onValueChange = { memoryDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Saved Memory (हर नोट अलग पंक्ति में)") },
                    minLines = 4,
                    maxLines = 10
                )
                Button(
                    enabled = memoryDraft != memories,
                    onClick = {
                        memories = memoryDraft.trim()
                        memoryDraft = memories
                        prefs.edit().putString("memories", memories).commit()
                        Toast.makeText(context, "Memory अपडेट हो गई", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)
                ) { Text("Memory बदलाव सुरक्षित करें") }
                Text("गोपनीयता: Personal Context डिफ़ॉल्ट रूप से चैट में शामिल नहीं होता। चालू करने पर सेव किए नोट, प्रोजेक्ट और हाल की चैट का संदर्भ भेजा जा सकता है। ऐप डेटा हटाने पर ये मिट सकते हैं।", color = muted, fontSize = 10.sp)
                TextButton(onClick = {
                    memories = ""
                    memoryDraft = ""
                    useMemoryInChat = false
                    prefs.edit().remove("memories").putBoolean("use_memory_in_chat", false).commit()
                }) { Text("सभी Memory मिटाएँ और उपयोग बंद करें", color = muted) }
            }
            "Voice" -> SimplePage("VOICE ASSISTANT", "हिंदी में बोलें। Android speech service से offline recognition को प्राथमिकता दी जाती है; यह सुविधा आपके फोन में उपलब्ध भाषा-पैक और speech provider पर निर्भर है।") {
                Button(onClick = onListen, colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text("🎙 बोलकर पूछें") }
                SelectionContainer { Text("पहचाना गया: $speechText", color = white) }
                Button(enabled = speechText.isNotBlank(), onClick = { onSpeak(speechText) }, colors = ButtonDefaults.buttonColors(containerColor = graphite, contentColor = white)) { Text("आवाज़ में सुनें") }
            }
            "Files" -> SimplePage("QUANTUM AI FILE INTELLIGENCE", "Text PDF और JPG/PNG/WEBP फोटो का विश्लेषण करें। यदि PDF स्कैन की हुई है और उसमें selectable text नहीं है, तो फोन पर OCR से टेक्स्ट पढ़ने का प्रयास होगा।") {
                Button(onClick = onPickFile, colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text("फोन से फ़ाइल चुनें") }
                Text("चुनी गई फ़ाइल: $pickedFile", color = white)
                Text("प्रकार: $pickedMime", color = muted, fontSize = 12.sp)
                Button(enabled = pickedUri != null && !fileBusy, onClick = {
                    val uri = pickedUri
                    if (uri != null) {
                        fileBusy = true
                        fileAnalysis = "फ़ाइल का विश्लेषण हो रहा है…"
                        activity.lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                                        BoundedInputReader.read(it, 7 * 1024 * 1024, "फ़ाइल 7 MB से छोटी रखें।")
                                    } ?: error("फ़ाइल पढ़ी नहीं जा सकी")
                                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    val pdfPrompt = "इस PDF दस्तावेज़ का गहराई से हिंदी में विश्लेषण करें: पहले संक्षिप्त सारांश, फिर विषयवार मुख्य बिंदु, महत्वपूर्ण तिथियाँ/नाम/आँकड़े, दस्तावेज़ में दिए गए निष्कर्ष और अंत में संभावित अस्पष्टताएँ बताएँ। केवल दस्तावेज़ में मौजूद जानकारी को तथ्य कहें।"
                                    val firstResult = askBackend(
                                        backend,
                                        if (pickedMime == "application/pdf") pdfPrompt else "इस फ़ाइल का हिंदी में सारांश, मुख्य बिंदु और महत्वपूर्ण तथ्य बताइए।",
                                        pickedMime,
                                        encoded
                                    )
                                    val scannedPdf = pickedMime == "application/pdf" &&
                                        (firstResult.contains("स्कैन की हुई PDF") || firstResult.contains("पढ़ने योग्य टेक्स्ट नहीं मिला"))
                                    if (scannedPdf) {
                                        val ocrText = PdfOcrExtractor.extract(context, uri)
                                        askBackend(
                                            backend,
                                            pdfPrompt + "\n\nफोन पर OCR से निकाला गया PDF टेक्स्ट (पहले 80,000 अक्षर तक):\n" + ocrText
                                        )
                                    } else firstResult
                                }.getOrElse { "फ़ाइल विश्लेषण विफल: ${it.message}" }
                            }
                            fileAnalysis = result
                            fileBusy = false
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text(if (fileBusy) "विश्लेषण…" else "AI से विश्लेषण") }
                if (fileAnalysis.isNotBlank()) {
                    SelectionContainer { Text(fileAnalysis, color = white) }
                    TextButton(onClick = { copyText(fileAnalysis) }) { Text("कॉपी करें", color = white) }
                }
            }
            "Quantum" -> SimplePage("QUANTUM AI QUANTUM ENGINE · 10 + 64 QUBIT MODES", "10-Qubit dense state-vector simulator और 64-Qubit sparse state-vector simulator। 64-Qubit mode structured circuits को exact रूप से चलाता है, जब non-zero amplitudes 100,000 तक सीमित रहें। दोनों classical simulations हैं, physical quantum processors नहीं।") {
                QuantumCircuitLab()
                HorizontalDivider(color = line, modifier = Modifier.padding(vertical = 10.dp))
                Quantum64SparseLab()
                HorizontalDivider(color = line, modifier = Modifier.padding(vertical = 10.dp))
                Text("REAL QUANTUM HARDWARE · IBM QUANTUM", color = mint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("यह हिस्सा IBM के असली quantum compute service से जुड़ेगा। इसके लिए server पर IBM API key और Quantum instance CRN सुरक्षित रूप से configure होना जरूरी है। Build 162 backend health response में केवल configuration readiness बताता है—किसी key या secret को ऐप में नहीं दिखाता।", color = muted, fontSize = 11.sp)
                OutlinedTextField(
                    value = quantumBackendName,
                    onValueChange = { quantumBackendName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IBM backend नाम (जैसे ibm_fez)") },
                    singleLine = true
                )
                Button(
                    enabled = !quantumHardwareBusy,
                    onClick = {
                        quantumHardwareBusy = true
                        activity.lifecycleScope.launch {
                            quantumHardwareResult = withContext(Dispatchers.IO) { quantumApiRequest(backend, "/v1/quantum/backends", "GET") }
                            quantumHardwareBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = graphite, contentColor = white)
                ) { Text(if (quantumHardwareBusy) "IBM से संपर्क…" else "IBM के उपलब्ध QPU देखें") }
                Button(
                    enabled = quantumBackendName.isNotBlank() && !quantumHardwareBusy,
                    onClick = { confirmQuantumJob = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = violet, contentColor = Color.White)
                ) { Text("असली QPU पर 1-qubit demo भेजें") }
                OutlinedTextField(
                    value = quantumJobId,
                    onValueChange = { quantumJobId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Quantum Job ID") },
                    singleLine = true
                )
                OutlinedButton(
                    enabled = quantumJobId.isNotBlank() && !quantumHardwareBusy,
                    onClick = {
                        quantumHardwareBusy = true
                        activity.lifecycleScope.launch {
                            quantumHardwareResult = withContext(Dispatchers.IO) { quantumApiRequest(backend, "/v1/quantum/jobs/" + quantumJobId.trim(), "GET") }
                            quantumHardwareBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Job स्थिति जाँचें", color = white) }
                if (quantumHardwareResult.isNotBlank()) {
                    SelectionContainer { Text(quantumHardwareResult, color = white, lineHeight = 18.sp) }
                }
                if (confirmQuantumJob) {
                    AlertDialog(
                        onDismissRequest = { confirmQuantumJob = false },
                        title = { Text("वास्तविक Quantum Job भेजें?") },
                        text = { Text("यह request IBM Quantum service पर एक वास्तविक job जमा करेगी। आपके IBM plan की usage allocation या quota लागू हो सकती है। क्या आप इसे भेजना चाहते हैं?") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmQuantumJob = false
                                quantumHardwareBusy = true
                                activity.lifecycleScope.launch {
                                    quantumHardwareResult = withContext(Dispatchers.IO) {
                                        quantumApiRequest(backend, "/v1/quantum/demo", "POST", JSONObject().put("backend", quantumBackendName.trim()))
                                    }
                                    runCatching { quantumJobId = JSONObject(quantumHardwareResult).optString("job_id", quantumJobId) }
                                    quantumHardwareBusy = false
                                }
                            }) { Text("हाँ, job भेजें") }
                        },
                        dismissButton = { TextButton(onClick = { confirmQuantumJob = false }) { Text("रद्द करें") } }
                    )
                }
                Text("जब तक IBM credentials configure नहीं होते, यह connector सक्रिय hardware connection नहीं है।", color = muted, fontSize = 10.sp)
            }
            "Offline AI" -> SimplePage("OFFLINE AI CORE", "इंटरनेट बंद होने पर QUANTUM AI इस स्थानीय मॉडल से जवाब दे सकेगा। पहली बार मॉडल डाउनलोड करने के लिए इंटरनेट और लगभग 328 MiB खाली जगह चाहिए।") {
                Text("स्थानीय मॉडल: ${OfflineAiEngine.MODEL_NAME}", color = mint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (offlineEngine.isModelInstalled) "स्थिति: मॉडल फोन में इंस्टॉल है" else "स्थिति: मॉडल डाउनलोड होना बाकी है", color = white, fontSize = 12.sp)
                if (offlineBusy && offlineProgress > 0) {
                    LinearProgressIndicator(progress = { offlineProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("डाउनलोड: $offlineProgress%", color = mint, fontSize = 12.sp)
                }
                Button(
                    enabled = !offlineBusy && !offlineEngine.isModelInstalled,
                    onClick = {
                        offlineBusy = true
                        offlineProgress = 0
                        offlineStatus = "Qwen मॉडल डाउनलोड हो रहा है…"
                        activity.lifecycleScope.launch {
                            try {
                                offlineEngine.downloadModel { offlineProgress = it }
                                offlineStatus = "मॉडल डाउनलोड और आकार-जाँच सफल। अब स्थानीय परीक्षण करें।"
                            } catch (error: Exception) {
                                offlineStatus = "डाउनलोड विफल: ${error.message ?: "नेटवर्क त्रुटि"}"
                            } finally {
                                offlineBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)
                ) { Text(if (offlineBusy) "डाउनलोड जारी…" else if (offlineEngine.isModelInstalled) "मॉडल डाउनलोड हो चुका है" else "328 MiB मॉडल डाउनलोड करें") }
                Text(offlineStatus, color = muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = offlineTestPrompt,
                    onValueChange = { offlineTestPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("स्थानीय AI का परीक्षण") },
                    minLines = 2
                )
                Button(
                    enabled = offlineEngine.isModelInstalled && !offlineBusy && offlineTestPrompt.isNotBlank(),
                    onClick = {
                        offlineBusy = true
                        offlineTestAnswer = ""
                        activity.lifecycleScope.launch {
                            try {
                                offlineStatus = offlineEngine.initialize()
                                offlineTestAnswer = offlineEngine.answer(offlineTestPrompt)
                            } catch (error: Exception) {
                                offlineTestAnswer = "स्थानीय मॉडल त्रुटि: ${error.message}"
                            } finally {
                                offlineBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = violet, contentColor = Color.White)
                ) { Text(if (offlineBusy) "स्थानीय AI चल रहा है…" else "ऑफलाइन AI टेस्ट चलाएँ") }
                if (offlineTestAnswer.isNotBlank()) {
                    SelectionContainer { Text(offlineTestAnswer, color = white, lineHeight = 21.sp) }
                }
                Text("मॉडल लाइसेंस: Apache 2.0 · Qwen3-0.6B. लाइव इंटरनेट खोज और वर्तमान समाचार ऑफलाइन उपलब्ध नहीं होंगे।", color = muted, fontSize = 11.sp)
                Text("Creator by Bittu Pandit", color = mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            "Action" -> SimplePage("QUANTUM AI ACTION INTELLIGENCE", "यह मॉड्यूल आपके काम को चरणों में बाँटकर समीक्षा योग्य योजना बनाता है। अभी यह फोन/बाहरी ऐप पर स्वतः बदलाव नहीं करता; योजना सेव करने से पहले आपकी पुष्टि आवश्यक है।") {
                OutlinedTextField(
                    value = actionRequest,
                    onValueChange = { actionRequest = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("कौन-सा काम पूरा करना है?") },
                    minLines = 3
                )
                Button(
                    enabled = actionRequest.isNotBlank() && !actionBusy,
                    onClick = {
                        actionBusy = true
                        actionPlan = "QUANTUM AI सुरक्षित कार्य-योजना बना रहा है…"
                        val request = actionRequest.trim()
                        activity.lifecycleScope.launch {
                            actionPlan = withContext(Dispatchers.IO) {
                                askBackend(
                                    backend,
                                    "इस काम के लिए हिंदी में स्पष्ट, क्रमबद्ध और व्यावहारिक कार्य-योजना बनाइए: " + request +
                                        "\n\nआउटपुट में लक्ष्य, आवश्यक सामग्री/पूर्वशर्तें, क्रमांकित चरण, प्रत्येक चरण का अपेक्षित परिणाम, जोखिम/सुरक्षा जाँच, और अंतिम सत्यापन शामिल करें।" +
                                        " किसी बाहरी ऐप में कार्रवाई करने, संदेश भेजने, भुगतान करने, फ़ाइल मिटाने या सेटिंग बदलने का दावा न करें। केवल योजना दें; उपयोगकर्ता स्वयं समीक्षा करेगा।"
                                )
                            }
                            actionBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = violet, contentColor = Color.White)
                ) { Text(if (actionBusy) "योजना बन रही है…" else "कार्य-योजना तैयार करें") }
                if (actionPlan.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = graphite), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("समीक्षा के लिए योजना", color = mint, fontWeight = FontWeight.Bold)
                            SelectionContainer { Text(actionPlan, color = white, lineHeight = 20.sp) }
                            Button(
                                enabled = !actionBusy && !actionPlan.startsWith("QUANTUM AI सुरक्षित"),
                                onClick = { confirmSaveActionPlan = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)
                            ) { Text("मेरी पुष्टि से Project checkpoint में सेव करें") }
                        }
                    }
                }
                if (confirmSaveActionPlan) {
                    AlertDialog(
                        onDismissRequest = { confirmSaveActionPlan = false },
                        title = { Text("योजना सेव करने की पुष्टि") },
                        text = { Text("यह योजना आपके फोन के QUANTUM AI Projects में स्थानीय checkpoint के रूप में सेव होगी। कोई बाहरी कार्रवाई नहीं होगी। क्या सेव करें?") },
                        confirmButton = {
                            TextButton(onClick = {
                                addProject("ACTION PLAN · " + actionRequest.trim() + "\n" + actionPlan)
                                confirmSaveActionPlan = false
                                Toast.makeText(context, "कार्य-योजना स्थानीय Projects में सेव हुई", Toast.LENGTH_SHORT).show()
                            }) { Text("हाँ, सेव करें") }
                        },
                        dismissButton = { TextButton(onClick = { confirmSaveActionPlan = false }) { Text("रद्द करें") } }
                    )
                }
                Text("अगला स्तर: फाइल/कनेक्टेड ऐप पर वास्तविक कार्य, पहले preview और स्पष्ट अनुमति के साथ।", color = muted, fontSize = 11.sp)
                Text("Creator by Bittu Pandit", color = mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            "Projects" -> SimplePage("PROJECT WORKSPACE", "अपने प्रोजेक्ट के नोट्स सेव करें। ये इसी फोन में रहेंगे और ऐप दोबारा खोलने पर वापस मिलेंगे।") {
                OutlinedTextField(projectInput, { projectInput = it }, Modifier.fillMaxWidth().testTag("project_input"), label = { Text("नया प्रोजेक्ट या नोट लिखें") }, minLines = 3)
                Button(enabled = projectInput.isNotBlank(), onClick = { addProject(projectInput) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text("प्रोजेक्ट / नोट सेव करें") }
                Text("सेव की गई सामग्री", color = mint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                SelectionContainer { Text(projects.ifBlank { "अभी कोई प्रोजेक्ट नोट नहीं।" }, color = white, lineHeight = 21.sp) }
                Text("नोट फोन के निजी ऐप स्टोरेज में सेव हैं; ऐप डेटा हटाने या ऐप uninstall करने पर मिट सकते हैं।", color = muted, fontSize = 11.sp)
                TextButton(onClick = { projects = ""; prefs.edit().remove("projects").apply() }) { Text("सभी प्रोजेक्ट नोट मिटाएँ", color = muted) }
            }
            "Updates" -> AppUpdateScreen()
            "Settings" -> SimplePage("CONNECTION & SECURITY SETTINGS", "Groq API key Render के सुरक्षित Environment में रहती है। Private backend access token आवश्यक है।") {
                Text("Verified backend: $TRUSTED_BACKEND_URL", color = mint, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("App Lock", color = white, fontWeight = FontWeight.SemiBold)
                        Text(if (appLockEnabled) "Fingerprint / फोन PIN लॉक चालू है" else "ऐप सीधे खुलेगा; Memory और निजी डेटा का encryption चालू रहेगा", color = muted, fontSize = 11.sp)
                    }
                    Switch(checked = appLockEnabled, onCheckedChange = { enabled ->
                        val saved = appSecurityPrefs.edit().putBoolean("app_lock_enabled", enabled).commit()
                        if (saved) {
                            appLockEnabled = enabled
                            status = if (enabled) "App Lock चालू किया गया" else "App Lock बंद किया गया; डेटा encryption चालू है"
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        } else {
                            status = "App Lock सेटिंग सेव नहीं हो पाई"
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        }
                    })
                }
                Text("App Lock पहली बार में सुरक्षा के लिए चालू रहता है। इसे बदलने के लिए पहले ऐप अनलॉक करें।", color = muted, fontSize = 10.sp)
                OutlinedTextField(apiAccessToken, { apiAccessToken = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Private API Access Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("यह Render के APP_ACCESS_TOKEN से मेल खाने वाला 64-अक्षरों का hexadecimal token है—Groq की gsk_ API Key नहीं। Token encrypted phone storage में सेव होगा; इसे साझा न करें।", color = muted, fontSize = 10.sp)
                Button(enabled = apiAccessToken.matches(Regex("^[a-f0-9]{64}$")), onClick = {
                    val saved = prefs.edit().putString("api_access_token", apiAccessToken.trim()).commit()
                    if (saved) {
                        AppSecurity.accessToken = apiAccessToken.trim()
                        status = "Private API सुरक्षा सेटिंग सेव हुई"
                        Toast.makeText(context, "सुरक्षा सेटिंग सेव हुई", Toast.LENGTH_SHORT).show()
                    } else {
                        status = "सुरक्षा token सेव नहीं हो पाया।"
                        Toast.makeText(context, "सुरक्षा सेटिंग सेव नहीं हुई", Toast.LENGTH_LONG).show()
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = ink)) { Text("सुरक्षा token सेव करें") }
                OutlinedButton(enabled = !healthBusy, onClick = {
                    healthBusy = true
                    status = "सर्वर जाँचा जा रहा है…"
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { checkAstraBackend(backend) }
                        status = result
                        healthBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (healthBusy) "जाँच जारी है…" else "AI सर्वर कनेक्शन जाँचें", color = white) }
                Text("API endpoint: /v1/generate", color = muted, fontSize = 12.sp)
                Text("स्थिति: $status", color = white, fontSize = 12.sp)
            }
        }
        if (page != "Home") {
            Text("Creator by Bittu Pandit", color = mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(Color(0xFF2F2F2F)).padding(12.dp))
        }
        }
        if (showSplash) SplashScreen(Modifier.fillMaxSize())
    }
}

@Composable
private fun SplashScreen(modifier: Modifier = Modifier) {
    Box(modifier.background(ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(112.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(white, violet, mint))), contentAlignment = Alignment.Center) {
                Text("Q", color = ink, fontSize = 76.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(24.dp))
            Text("QUANTUM AI", color = white, fontSize = 31.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text("QUANTUM AI", color = muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
            Spacer(Modifier.height(22.dp))
            Text("Explore the unknown", color = mint, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(38.dp))
            CircularProgressIndicator(color = white, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(28.dp))
            Text("Creator by Bittu Pandit", color = mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CosmicNebulaBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val clouds = listOf(
            Triple(Offset(w * .18f, h * .25f), size.minDimension * .62f, Color(0xFF5526A8)),
            Triple(Offset(w * .86f, h * .42f), size.minDimension * .58f, Color(0xFF075D9B)),
            Triple(Offset(w * .48f, h * .82f), size.minDimension * .66f, Color(0xFF7A236E)),
            Triple(Offset(w * .06f, h * .88f), size.minDimension * .42f, Color(0xFF123F91))
        )
        clouds.forEach { (center, radius, tint) ->
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to tint.copy(alpha = .34f),
                    .34f to tint.copy(alpha = .17f),
                    .72f to tint.copy(alpha = .055f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
        drawLine(Color(0xFF8A68D8).copy(alpha = .10f), Offset(w * .02f, h * .58f), Offset(w * .98f, h * .20f), strokeWidth = size.minDimension * .11f)
        val stars = listOf(
            .04f to .08f, .18f to .16f, .31f to .06f, .47f to .13f, .68f to .07f, .86f to .18f, .95f to .09f,
            .09f to .32f, .25f to .27f, .42f to .36f, .59f to .29f, .78f to .34f, .91f to .43f,
            .03f to .57f, .16f to .49f, .36f to .58f, .54f to .51f, .72f to .61f, .89f to .55f,
            .11f to .76f, .29f to .69f, .48f to .81f, .66f to .73f, .84f to .84f, .97f to .72f,
            .22f to .93f, .41f to .96f, .61f to .91f, .78f to .96f, .94f to .89f
        )
        stars.forEachIndexed { index, point ->
            val center = Offset(w * point.first, h * point.second)
            val radius = if (index % 5 == 0) 2.0.dp.toPx() else 1.05.dp.toPx()
            drawCircle(if (index % 4 == 0) Color(0xFF9DF7F0).copy(alpha = .95f) else Color.White.copy(alpha = .82f), radius, center)
            if (index % 5 == 0) drawCircle(Color(0xFF9DF7F0).copy(alpha = .22f), radius * 3.6f, center)
        }
        val planet = Offset(w * .84f, h * .28f)
        val pr = size.minDimension * .105f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB9A5FF).copy(alpha = .78f), Color(0xFF5436A0).copy(alpha = .50f), Color.Transparent), planet, pr * 2.1f), pr * 2.1f, planet)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF6D58B8), Color(0xFF18234D), Color(0xFF070D20)), planet, pr), pr, planet)
        drawCircle(Color(0xFFB5A6FF).copy(alpha = .55f), pr * 1.12f, planet, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun HomeCosmicArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val earth = Offset(w * .72f, h * .84f)
        val er = h * .48f
        drawCircle(Color(0xFF438BFF).copy(alpha = .24f), er * 1.55f, earth)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF73D9FF), Color(0xFF1762C8), Color(0xFF071A49)), earth, er), er, earth)
        drawArc(Color(0xFF54E7A0).copy(alpha = .95f), 195f, 115f, false, Offset(earth.x-er, earth.y-er), androidx.compose.ui.geometry.Size(er*2, er*2), style = Stroke(width = er*.20f))
        drawArc(Color(0xFFB8A2FF).copy(alpha = .75f), 205f, 125f, false, Offset(earth.x-er*1.15f, earth.y-er*1.10f), androidx.compose.ui.geometry.Size(er*2.3f, er*2.2f), style = Stroke(width = 2.dp.toPx()))
        for (i in 0..45) {
            val x = (i * 43f % w)
            val y = (i * 29f % h)
            drawCircle(if (i%5==0) Color(0xFFB6F7FF) else Color.White.copy(alpha=.7f), if (i%5==0) 1.8.dp.toPx() else 1.dp.toPx(), Offset(x,y))
        }
        drawCircle(Color(0xFFB49CFF).copy(alpha=.55f), h*.10f, Offset(w*.82f,h*.20f))
        drawCircle(Color(0xFFB49CFF).copy(alpha=.8f), h*.075f, Offset(w*.82f,h*.20f))
    }
}

@Composable
private fun ColumnScope.SimplePage(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().background(ink).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(title, color = white, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(description, color = muted, fontSize = 13.sp)
        content()
        Spacer(Modifier.weight(1f))
        Text("Creator by Bittu Pandit", color = mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuantumArtwork(modifier: Modifier = Modifier, variant: Int = 0) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .31f
        val thin = 1.15.dp.toPx()
        when (variant) {
            1 -> {
                drawCircle(violet, radius = radius * 1.35f, center = center, style = Stroke(thin))
                drawCircle(mint, radius = radius * .9f, center = center, style = Stroke(thin))
                drawArc(white, -25f, 235f, false, Offset(center.x - radius * 1.4f, center.y - radius * .7f), androidx.compose.ui.geometry.Size(radius * 2.8f, radius * 1.4f), style = Stroke(thin * 2))
                drawCircle(white, radius = radius * .13f, center = Offset(center.x + radius * .8f, center.y - radius * .7f))
                drawCircle(violet, radius = radius * .09f, center = Offset(center.x - radius * .9f, center.y + radius * .4f))
            }
            2 -> {
                drawCircle(violet, radius = radius * 1.2f, center = center, style = Stroke(thin))
                for (i in 0..5) {
                    val x = center.x + (i - 2.5f) * radius * .32f
                    val y = center.y + if (i % 2 == 0) -radius * .48f else radius * .48f
                    drawLine(mint, center, Offset(x, y), thin, cap = StrokeCap.Round)
                    drawCircle(white, radius = radius * .13f, center = Offset(x, y))
                }
                drawOval(white, Offset(center.x - radius * .7f, center.y - radius * .9f), androidx.compose.ui.geometry.Size(radius * 1.4f, radius * 1.8f), style = Stroke(thin))
            }
            3 -> {
                val nodes = listOf(
                    Offset(center.x - radius, center.y - radius * .7f),
                    Offset(center.x + radius, center.y - radius * .7f),
                    Offset(center.x - radius, center.y + radius * .7f),
                    Offset(center.x + radius, center.y + radius * .7f),
                    Offset(center.x, center.y)
                )
                listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 0 to 2, 1 to 3).forEach { (a, b) ->
                    drawLine(violet, nodes[a], nodes[b], thin, cap = StrokeCap.Round)
                }
                nodes.forEachIndexed { index, point ->
                    drawCircle(if (index == 4) white else mint, radius = if (index == 4) radius * .22f else radius * .15f, center = point)
                }
            }
            else -> {
                drawCircle(white, radius = radius * .42f, center = center)
                drawCircle(mint, radius = radius * .72f, center = center, style = Stroke(width = thin))
                drawOval(Color(0xFFE8E8E8), topLeft = Offset(center.x - radius * 1.4f, center.y - radius * .55f), size = androidx.compose.ui.geometry.Size(radius * 2.8f, radius * 1.1f), style = Stroke(width = thin))
                drawOval(violet, topLeft = Offset(center.x - radius * 1.4f, center.y - radius * .55f), size = androidx.compose.ui.geometry.Size(radius * 2.8f, radius * 1.1f), style = Stroke(width = thin), alpha = .8f)
                drawCircle(white, radius = radius * .12f, center = Offset(center.x + radius * 1.15f, center.y - radius * .35f))
                drawCircle(mint, radius = radius * .09f, center = Offset(center.x - radius * .95f, center.y + radius * .7f))
                drawCircle(violet, radius = radius * 1.45f, center = center, style = Stroke(width = .7.dp.toPx()), alpha = .8f)
            }
        }
    }
}

private fun isTrustedBackend(baseUrl: String): Boolean = runCatching {
    val parsed = URL(baseUrl.trim().trimEnd('/'))
    parsed.protocol == "https" && parsed.host.equals("astraquantum-ai.onrender.com", ignoreCase = true) && parsed.port == -1
}.getOrDefault(false)

private fun checkAstraBackend(baseUrl: String): String {
    if (baseUrl.isBlank()) return "त्रुटि: Backend URL खाली है।"
    return try {
        val clean = baseUrl.trim().trimEnd('/')
        if (!isTrustedBackend(clean)) return "त्रुटि: केवल आधिकारिक QUANTUM AI backend स्वीकार है।"
        val connection = (URL("$clean/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        val code = connection.responseCode
        val body = BoundedInputReader.readText(
            if (code in 200..299) connection.inputStream else connection.errorStream,
            64 * 1024,
            "Backend health response is too large."
        )
        connection.disconnect()
        if (code !in 200..299) "सर्वर उपलब्ध नहीं · HTTP $code"
        else {
            val json = JSONObject(body)
            if (json.optBoolean("ok")) "Backend server reachable · AI API/model की अलग जाँच अभी नहीं हुई।"
            else "सर्वर मिला, पर health check सफल नहीं हुआ।"
        }
    } catch (e: Exception) {
        "कनेक्शन विफल: " + (e.message ?: "नेटवर्क त्रुटि")
    }
}

private fun quantumApiRequest(baseUrl: String, path: String, method: String, payload: JSONObject? = null): String {
    if (baseUrl.isBlank()) return "QUANTUM AI backend URL सेव करें।"
    return try {
        val clean = baseUrl.trim().trimEnd('/')
        if (!isTrustedBackend(clean)) return "सुरक्षा के लिए केवल आधिकारिक QUANTUM AI backend स्वीकार है।"
        val connection = (URL(clean + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 150000
            doOutput = method == "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + AppSecurity.accessToken)
        }
        if (method == "POST" && payload != null) {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = BoundedInputReader.readText(stream, 12 * 1024 * 1024, "Quantum response is too large.")
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
            "Quantum सेवा त्रुटि (HTTP " + code + "): " + message.ifBlank { body.take(250) }
        } else {
            runCatching { JSONObject(body).toString(2) }.getOrElse { body }
        }
    } catch (error: Exception) {
        "Quantum connection विफल: " + (error.message ?: "नेटवर्क त्रुटि")
    }
}

private fun askResearch(baseUrl: String, query: String): String {
    if (baseUrl.isBlank()) return "QUANTUM AI backend URL सेव करें।"
    return try {
        val clean = baseUrl.trim().trimEnd('/')
        if (!isTrustedBackend(clean)) return "सुरक्षा के लिए केवल आधिकारिक QUANTUM AI backend स्वीकार है।"
        val connection = (URL(clean + "/v1/research").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 150000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + AppSecurity.accessToken)
        }
        val payload = JSONObject().put("query", query)
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = BoundedInputReader.readText(stream, 12 * 1024 * 1024, "Research response is too large.")
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
            "Deep Research त्रुटि (HTTP $code): " + message.ifBlank { body.take(180) }
        } else {
            val json = JSONObject(body)
            val report = json.optString("text")
            val meta = json.optJSONObject("research_meta")
            val sources = json.optJSONArray("sources") ?: JSONArray()
            val sourceText = buildString {
                for (index in 0 until sources.length()) {
                    val item = sources.optJSONObject(index) ?: continue
                    append("\n[").append(item.optInt("id")).append("] ")
                    append(item.optString("title")).append(" (").append(item.optString("year")).append(")")
                    val doi = item.optString("doi")
                    if (doi.isNotBlank()) {
                        append("\nDOI: ").append(doi)
                        append(" · Crossref: ").append(item.optString("doi_verification", "NOT_REPORTED"))
                    }
                    append("\n").append(item.optString("url")).append("\n")
                }
            }
            buildString {
                append(report)
                if (meta != null) {
                    append("\n\nशोध कवरेज: ")
                    append(meta.optInt("unique_source_count")).append(" अलग स्रोत; ")
                    append(meta.optInt("open_full_texts_fetched")).append(" पूरे टेक्स्ट दस्तावेज़ प्राप्त।")
                    append("\nपूरे टेक्स्ट के प्रकार: ").append(meta.optJSONArray("full_text_types")?.let { arr -> if (arr.length() == 0) "कोई नहीं" else (0 until arr.length()).joinToString(", ") { arr.optString(it) } } ?: "जानकारी उपलब्ध नहीं")
                    append("\nस्रोत प्रकार: ").append(meta.optJSONArray("source_types")?.let { arr -> (0 until arr.length()).joinToString(", ") { arr.optString(it) } } ?: "उपलब्ध")
                    append("\nDOI सत्यापन: ").append(meta.optInt("doi_title_matches")).append(" शीर्षक-मिलान / ").append(meta.optInt("doi_checks_attempted")).append(" जाँच")
                    append("\nरिपोर्ट में उपयोग किए गए स्रोत: ").append(meta.optInt("synthesis_source_count"))
                    append("\nशोध चरण: ").append(meta.optJSONArray("research_pipeline")?.let { arr -> (0 until arr.length()).joinToString(" → ") { arr.optString(it) } } ?: "उपलब्ध नहीं")
                    meta.optJSONObject("request_budget")?.let { budget ->
                        append("\nGroq बजट: ").append(budget.optInt("estimated_input_bytes")).append("/")
                            .append(budget.optInt("max_input_bytes")).append(" input bytes · ")
                            .append(budget.optInt("max_output_tokens")).append(" output tokens")
                        if (budget.optBoolean("compacted_after_413")) append(" · 413 के बाद compact किया गया")
                    }
                    append("\nसीमा: ").append(meta.optString("coverage_note"))
                }
                if (sourceText.isNotBlank()) append("\n\nस्रोत सूची\n").append(sourceText)
            }
        }
    } catch (error: Exception) {
        "Deep Research कनेक्शन विफल: " + (error.message ?: "नेटवर्क त्रुटि")
    }
}

internal fun askBackend(baseUrl: String, prompt: String, mimeType: String? = null, base64: String? = null): String {
    if (baseUrl.isBlank()) return "Private API Access Token सेटिंग्स में सेव करें।"
    return try {
        val clean = baseUrl.trim().trimEnd('/')
        if (!isTrustedBackend(clean)) return "सुरक्षा के लिए केवल आधिकारिक QUANTUM AI backend स्वीकार है।"
        val connection = (URL("$clean/v1/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 65000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer " + AppSecurity.accessToken)
        }
        val payload = JSONObject().put("prompt", prompt)
        if (!base64.isNullOrBlank() && !mimeType.isNullOrBlank()) {
            payload.put("mimeType", mimeType).put("base64", base64)
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = BoundedInputReader.readText(stream, 2 * 1024 * 1024, "AI response is too large.")
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
            "Groq सेवा में त्रुटि (HTTP $code): ${message.ifBlank { body.take(180) }}"
        } else {
            val json = JSONObject(body)
            json.optString("text").ifBlank { json.optString("response").ifBlank { "सर्वर से उत्तर मिला, लेकिन टेक्स्ट नहीं मिला।" } }
        }
    } catch (e: Exception) {
        "QUANTUM AI सर्वर से कनेक्शन नहीं हो पाया: ${e.message ?: "नेटवर्क त्रुटि"}"
    }
}

@Composable
private fun ScienceLabScreen() {
    var reaction by remember { mutableStateOf("H2 + O2 -> H2O") }
    var reactionResult by remember { mutableStateOf("") }
    var chemicalFormula by remember { mutableStateOf("H2O") }
    var chemicalCompositionResult by remember { mutableStateOf("") }
    var linearA by remember { mutableStateOf("2") }
    var linearB by remember { mutableStateOf("-8") }
    var quadraticA by remember { mutableStateOf("1") }
    var quadraticB by remember { mutableStateOf("-3") }
    var quadraticC by remember { mutableStateOf("2") }
    var mathResult by remember { mutableStateOf("") }
    var forceMass by remember { mutableStateOf("5") }
    var forceAcceleration by remember { mutableStateOf("9.8") }
    var physicsResult by remember { mutableStateOf("") }
    var elementQuery by remember { mutableStateOf("") }
    var unitValue by remember { mutableStateOf("1") }
    var unitFrom by remember { mutableStateOf("km") }
    var unitTo by remember { mutableStateOf("m") }
    var unitResult by remember { mutableStateOf("") }
    var formulaQuery by remember { mutableStateOf("") }
    val allScienceFormulas = remember { ScienceFormulaCatalog.formulas + AdvancedScienceFormulaCatalog.formulas }
    val visibleScienceFormulas = remember(formulaQuery) {
        val q = formulaQuery.trim().lowercase()
        if (q.isBlank()) allScienceFormulas else allScienceFormulas.filter {
            listOf(it.discipline, it.topic, it.name, it.equation, it.assumptions).any { field -> field.lowercase().contains(q) }
        }
    }
    val elements = remember(elementQuery) {
        PeriodicTableCatalog.elements.filter {
            it.name.contains(elementQuery, ignoreCase = true) ||
                it.symbol.contains(elementQuery, ignoreCase = true) ||
                it.atomicNumber.toString() == elementQuery.trim()
        }.take(30)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("QUANTUM AI UNIVERSAL SCIENCE LAB", color = white, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text("सूत्र, गणना और जाँच योग्य परिणाम। यह शुरुआती वैज्ञानिक टूलकिट है, सभी विज्ञानों का पूर्ण डेटाबेस नहीं।", color = muted, fontSize = 12.sp)
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CHEMISTRY · REACTION BALANCER", color = mint, fontWeight = FontWeight.Bold)
                OutlinedTextField(reaction, { reaction = it }, Modifier.fillMaxWidth(), label = { Text("Reaction (उदा. H2 + O2 -> H2O)") })
                Button(onClick = {
                    reactionResult = runCatching { ChemicalEquationBalancer.balance(reaction).equation }
                        .getOrElse { "हल नहीं मिला: " + (it.message ?: "असमर्थित समीकरण") }
                }, modifier = Modifier.fillMaxWidth()) { Text("समीकरण संतुलित करें") }
                if (reactionResult.isNotBlank()) Text(reactionResult, color = white, fontWeight = FontWeight.SemiBold)
                Text("सीमा: सामान्य neutral formula, parentheses और 2–6 पदार्थ; hydrates, ionic charge और redox half-reactions अभी समर्थित नहीं।", color = muted, fontSize = 10.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CHEMISTRY · MOLAR MASS & COMPOSITION", color = mint, fontWeight = FontWeight.Bold)
                OutlinedTextField(chemicalFormula, { chemicalFormula = it }, Modifier.fillMaxWidth(), label = { Text("Chemical formula (उदा. H2O, Ca(OH)2)") })
                Button(onClick = {
                    chemicalCompositionResult = runCatching {
                        val result = ChemicalCompositionCalculator.analyze(chemicalFormula)
                        "Molar mass: ${result.molarMassGPerMol} g/mol\\nAtoms: " +
                            result.atomCounts.entries.joinToString { it.key + " = " + it.value } +
                            "\\nMass %: " + result.massPercentByElement.entries.joinToString { it.key + " = " + "%.2f".format(it.value) + "%" }
                    }.getOrElse { "गणना नहीं हुई: " + (it.message ?: "Formula जाँचें") }
                }, modifier = Modifier.fillMaxWidth()) { Text("Molar mass और composition निकालें") }
                if (chemicalCompositionResult.isNotBlank()) Text(chemicalCompositionResult, color = white, fontSize = 12.sp)
                Text("सीमा: neutral formula, element symbols और parentheses; isotope-specific mass वाले तत्वों के लिए अलग डेटा चाहिए।", color = muted, fontSize = 10.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MATHEMATICS · EQUATION SOLVER", color = mint, fontWeight = FontWeight.Bold)
                Text("Linear: ax + b = 0", color = white, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(linearA, { linearA = it }, Modifier.weight(1f), label = { Text("a") })
                    OutlinedTextField(linearB, { linearB = it }, Modifier.weight(1f), label = { Text("b") })
                }
                Text("Quadratic: ax² + bx + c = 0", color = white, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(quadraticA, { quadraticA = it }, Modifier.weight(1f), label = { Text("a") })
                    OutlinedTextField(quadraticB, { quadraticB = it }, Modifier.weight(1f), label = { Text("b") })
                    OutlinedTextField(quadraticC, { quadraticC = it }, Modifier.weight(1f), label = { Text("c") })
                }
                Button(onClick = {
                    mathResult = runCatching {
                        val linear = ElementaryMathSolver.solveLinear(linearA.toDouble(), linearB.toDouble())
                        val quad = ElementaryMathSolver.solveQuadratic(quadraticA.toDouble(), quadraticB.toDouble(), quadraticC.toDouble())
                        "Linear: x = " + linear.roots.joinToString() + "\nQuadratic: " + quad.classification +
                            "\nRoots: " + if (quad.roots.isEmpty()) "कोई वास्तविक root नहीं" else quad.roots.joinToString()
                    }.getOrElse { "इनपुट जाँचें: " + (it.message ?: "गणना नहीं हो सकी") }
                }, modifier = Modifier.fillMaxWidth()) { Text("गणना करें") }
                if (mathResult.isNotBlank()) Text(mathResult, color = white, fontSize = 12.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PHYSICS · NEWTON'S SECOND LAW", color = mint, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(forceMass, { forceMass = it }, Modifier.weight(1f), label = { Text("Mass (kg)") })
                    OutlinedTextField(forceAcceleration, { forceAcceleration = it }, Modifier.weight(1f), label = { Text("Acceleration (m/s²)") })
                }
                Button(onClick = {
                    physicsResult = runCatching {
                        val result = ScienceCalculator.netForce(forceMass.toDouble(), forceAcceleration.toDouble())
                        result.name + ": " + result.value + " " + result.unit + "\n" + result.working +
                            "\nमान्यता: " + result.assumptions
                    }.getOrElse { "इनपुट जाँचें: " + (it.message ?: "गणना नहीं हो सकी") }
                }, modifier = Modifier.fillMaxWidth()) { Text("बल की गणना करें") }
                if (physicsResult.isNotBlank()) Text(physicsResult, color = white, fontSize = 12.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PERIODIC TABLE · 118 ELEMENTS", color = mint, fontWeight = FontWeight.Bold)
                OutlinedTextField(elementQuery, { elementQuery = it }, Modifier.fillMaxWidth(), label = { Text("तत्व खोजें: नाम, Symbol या Atomic Number") })
                elements.forEach { element ->
                    Text(
                        element.atomicNumber.toString() + " · " + element.symbol + " · " + element.name +
                            " · " + (element.abridgedAtomicMass?.toString() ?: "मानक atomic mass नहीं") +
                            " · " + element.category,
                        color = white, fontSize = 11.sp
                    )
                }
                Text("पहले 30 matching परिणाम दिखाए जाते हैं।", color = muted, fontSize = 10.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SCIENTIFIC UNIT CONVERTER", color = mint, fontWeight = FontWeight.Bold)
                OutlinedTextField(unitValue, { unitValue = it }, Modifier.fillMaxWidth(), label = { Text("मान (Value)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(unitFrom, { unitFrom = it }, Modifier.weight(1f), label = { Text("From unit") })
                    OutlinedTextField(unitTo, { unitTo = it }, Modifier.weight(1f), label = { Text("To unit") })
                }
                Text("इकाइयाँ: m, km, cm, mm, mi, ft · kg, g, mg, lb · s, min, h, day · K, °C, °F · Pa, kPa, bar, atm · J, kJ, cal, kcal, Wh, kWh · L, mL, m³ · m/s, km/h, mph", color = muted, fontSize = 10.sp)
                Button(onClick = {
                    unitResult = runCatching {
                        val value = ScienceUnitConverter.convert(unitValue.toDouble(), unitFrom.trim(), unitTo.trim())
                        "$unitValue $unitFrom = $value $unitTo"
                    }.getOrElse { "रूपांतरण नहीं हुआ: " + (it.message ?: "इनपुट जाँचें") }
                }, modifier = Modifier.fillMaxWidth()) { Text("इकाई बदलें") }
                if (unitResult.isNotBlank()) SelectionContainer { Text(unitResult, color = white, fontWeight = FontWeight.SemiBold) }
                Text("अलग dimensions (जैसे kg से m) को converter अस्वीकार करता है।", color = muted, fontSize = 10.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("FORMULA LIBRARY · " + ScienceFormulaCatalog.VERSION + " + " + AdvancedScienceFormulaCatalog.VERSION, color = mint, fontWeight = FontWeight.Bold)
                OutlinedTextField(formulaQuery, { formulaQuery = it }, Modifier.fillMaxWidth(), label = { Text("सूत्र खोजें: Physics, Biology, Chemistry, Maths...") })
                Text(visibleScienceFormulas.size.toString() + " सूत्र उपलब्ध · मूल और विस्तारित curated catalogue", color = muted, fontSize = 10.sp)
                visibleScienceFormulas.forEach { formula ->
                    Text(formula.discipline + " · " + formula.topic + " · " + formula.name, color = white, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(formula.equation, color = mint, fontSize = 13.sp)
                    Text("चर/इकाइयाँ: " + formula.variables.joinToString { it.symbol + " = " + it.meaning + " [" + it.siUnit + "]" }, color = muted, fontSize = 10.sp)
                    Text("मान्यताएँ: " + formula.assumptions + "\nसीमा: " + formula.validity, color = muted, fontSize = 10.sp)
                    Text("स्रोत: " + formula.sourceName + " · " + formula.sourceUrl, color = muted, fontSize = 9.sp)
                    HorizontalDivider(color = line)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = charcoal)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("SCIENTIFIC CONSTANTS · " + ScientificConstantsCatalog.VERSION, color = mint, fontWeight = FontWeight.Bold)
                ScientificConstantsCatalog.constants.forEach { constant ->
                    Text(constant.name + " (" + constant.symbol + ") = " + constant.value + " " + constant.unit, color = white, fontSize = 11.sp)
                    Text("अनिश्चितता: " + constant.uncertainty + " · " + constant.source, color = muted, fontSize = 9.sp)
                }
            }
        }
        Text("Creator by Bittu Pandit", color = mint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
