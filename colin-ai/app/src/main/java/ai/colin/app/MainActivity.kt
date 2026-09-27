package ai.colin.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

private val ColinDark = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFFFFB86B),
    background = Color(0xFF0E1116),
    surface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFF1C2230),
    surfaceContainer = Color(0xFF161B24),
    surfaceContainerHigh = Color(0xFF1C2230),
    onSurface = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFFA6B0C3),
    primaryContainer = Color(0xFF2A3A6E),
    onPrimaryContainer = Color(0xFFE3E9FF),
)

private enum class Screen { CHAT, TRAINING, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this) { }
        handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = ColinDark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm, shared, speak = ::speak)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
    }

    private fun speak(text: String) {
        val t = tts ?: return
        if (t.isSpeaking) { t.stop(); return }
        val plain = text.replace(Regex("""[*_`#>]"""), "").replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        t.language = Locale.getDefault()
        t.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "colin")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun App(vm: ChatViewModel, shared: MutableState<String?>, speak: (String) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(if (vm.settings.apiKey.isBlank()) Screen.SETTINGS else Screen.CHAT) }
    val context = LocalContext.current
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }
    BackHandler(screen != Screen.CHAT) { screen = Screen.CHAT }
    when (screen) {
        Screen.CHAT -> ChatScreen(vm, shared, speak, onOpen = { screen = it })
        Screen.TRAINING -> TrainingScreen(vm) { screen = Screen.CHAT }
        Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.CHAT }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel, shared: MutableState<String?>, speak: (String) -> Unit, onOpen: (Screen) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(shared.value) { shared.value?.let { input = it; shared.value = null } }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            input = if (input.isBlank()) it else "$input $it"
        }
    }
    val context = LocalContext.current

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Text("Colin AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
                NavigationDrawerItem(
                    label = { Text("New chat") }, icon = { Icon(Icons.Default.Add, null) }, selected = false,
                    onClick = { vm.newChat(); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Training & memory (${vm.memories.size})") }, icon = { Icon(Icons.Default.Psychology, null) }, selected = false,
                    onClick = { onOpen(Screen.TRAINING); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Settings") }, icon = { Icon(Icons.Default.Settings, null) }, selected = false,
                    onClick = { onOpen(Screen.SETTINGS); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
                LazyColumn {
                    items(vm.conversations, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = c.id == vm.current.id,
                            onClick = { vm.open(c); scope.launch { drawer.close() } },
                            badge = { IconButton(onClick = { vm.delete(c) }) { Icon(Icons.Default.DeleteOutline, "Delete", Modifier.size(18.dp)) } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Colin AI", fontWeight = FontWeight.Bold)
                            Text(vm.settings.brain.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                    actions = { IconButton(onClick = vm::newChat) { Icon(Icons.Default.EditNote, "New chat") } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    busy = vm.busy,
                    onSend = { vm.send(input); input = "" },
                    onStop = vm::stop,
                    onMic = {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Colin AI")
                        runCatching { voice.launch(i) }.onFailure {
                            Toast.makeText(context, "No speech recognizer on this device", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            val messages = vm.current.messages
            if (messages.isEmpty()) {
                Welcome(Modifier.padding(pad), vm.settings.userName) { vm.send(it) }
            } else {
                val list = rememberLazyListState()
                LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
                    list.scrollToItem(maxOf(0, messages.size - 1), Int.MAX_VALUE)
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(messages) { i, m ->
                        val last = i == messages.lastIndex
                        MessageRow(
                            m = m,
                            status = if (last && vm.busy) vm.status else null,
                            showThinking = vm.settings.showThinking,
                            onSpeak = speak,
                            onRetry = if (last && m.role == Role.ASSISTANT && !vm.busy) vm::retry else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, name: String, onPick: (String) -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp)) }
        Spacer(Modifier.height(12.dp))
        Text("Hey ${name.ifBlank { "there" }}, I'm Colin AI.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Ask me anything. I can search the web and I remember what you teach me.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        listOf(
            "What's happening in the news today?",
            "Help me plan my week",
            "Explain how gold prices are set, simply",
            "Remember that I like short answers",
        ).forEach {
            OutlinedCard(onClick = { onPick(it) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(it, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessage, status: String?, showThinking: Boolean, onSpeak: (String) -> Unit, onRetry: (() -> Unit)?) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    if (m.role == Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) { MarkdownText(m.text, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (showThinking && m.thinking.isNotBlank()) {
            var open by remember { mutableStateOf(false) }
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(if (open) " Hide reasoning" else " Show reasoning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(open) {
                Text(
                    m.thinking.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
        }
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("  $status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (m.text.isNotEmpty()) {
            MarkdownText(
                m.text,
                if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (m.sources.isNotEmpty() && status == null) {
            Text("Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            m.sources.take(5).forEach { s ->
                Text(
                    "↗ " + s.title.ifBlank { s.url },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { runCatching { uri.openUri(s.url) } }.padding(vertical = 2.dp),
                )
            }
        }
        if (m.text.isNotEmpty() && status == null) {
            Row {
                IconButton(onClick = {
                    scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText("Colin AI", m.text))) }
                }, Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(18.dp)) }
                IconButton(onClick = { onSpeak(m.text) }, Modifier.size(36.dp)) { Icon(Icons.Default.VolumeUp, "Read aloud", Modifier.size(18.dp)) }
                if (onRetry != null) IconButton(onClick = onRetry, Modifier.size(36.dp)) { Icon(Icons.Default.Refresh, "Retry", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onStop: () -> Unit, onMic: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message Colin AI") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                trailingIcon = { IconButton(onClick = onMic) { Icon(Icons.Default.Mic, "Voice input") } },
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = if (busy) onStop else onSend,
                enabled = busy || value.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                if (busy) Icon(Icons.Default.Stop, "Stop") else Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun TrainingScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var fact by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf(vm.settings.instructions) }
    var confirmClear by remember { mutableStateOf(false) }
    SubScreen("Training & memory", onBack) {
        Text(
            "This is how you train Colin AI. Custom instructions shape how it behaves; memories are facts it knows about you in every chat. It also learns on its own when you tell it things about yourself.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        Text("Custom instructions", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = instructions,
            onValueChange = { instructions = it },
            placeholder = { Text("e.g. Always answer in German. Keep it short. I'm into precious metals and investing.") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        Button(onClick = { vm.updateSettings(vm.settings.copy(instructions = instructions)); vm.toast = "Instructions saved" }) { Text("Save instructions") }

        HorizontalDivider()
        Text("Memories (${vm.memories.size})", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(fact, { fact = it }, placeholder = { Text("Teach Colin AI a fact") }, modifier = Modifier.weight(1f), singleLine = true)
            IconButton(onClick = { vm.addMemory(fact); fact = "" }, enabled = fact.isNotBlank()) { Icon(Icons.Default.Add, "Add") }
        }
        if (vm.memories.isEmpty()) {
            Text("Nothing yet. Try telling Colin AI \"remember that my birthday is 3 May\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.memories.forEachIndexed { i, mem ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mem, Modifier.weight(1f).padding(vertical = 10.dp))
                    IconButton(onClick = { vm.removeMemory(i) }) { Icon(Icons.Default.Close, "Forget") }
                }
            }
        }
        if (vm.memories.isNotEmpty()) {
            TextButton(onClick = { confirmClear = true }) { Text("Forget everything", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget all memories?") },
            text = { Text("Colin AI will lose everything it has learned about you. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.clearMemories(); confirmClear = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var s by remember { mutableStateOf(vm.settings) }
    var showKey by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    fun save(n: Settings) { s = n; vm.updateSettings(n) }
    SubScreen("Settings", onBack) {
        Text("Anthropic API key", style = MaterialTheme.typography.titleMedium)
        Text(
            "Colin AI thinks using Anthropic's Claude models, so it needs your own API key. It's stored encrypted on this phone only.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = s.apiKey,
            onValueChange = { save(s.copy(apiKey = it.trim())) },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { uri.openUri("https://console.anthropic.com/settings/keys") }) { Text("Get an API key →") }

        Text("Your name", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(s.userName, { save(s.copy(userName = it)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Brain", style = MaterialTheme.typography.titleMedium)
        Brain.entries.forEach { b ->
            Row(Modifier.fillMaxWidth().clickable { save(s.copy(brain = b)) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.brain == b, onClick = { save(s.copy(brain = b)) })
                Column {
                    Text(b.label)
                    Text(b.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text("Effort", style = MaterialTheme.typography.titleMedium)
        Text("How hard Colin AI thinks before answering. Higher is smarter but slower and costs more.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScrollSafe()) {
            Effort.entries.forEach { e ->
                FilterChip(selected = s.effort == e, onClick = { save(s.copy(effort = e)) }, label = { Text(e.label, fontSize = 13.sp) })
            }
        }

        ToggleRow("Web search", "Look things up live for current info", s.webSearch) { save(s.copy(webSearch = it)) }
        ToggleRow("Learn automatically", "Save facts you share to memory", s.autoMemory) { save(s.copy(autoMemory = it)) }
        ToggleRow("Show reasoning", "Let you peek at how it thought", s.showThinking) { save(s.copy(showThinking = it)) }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, enabled = s.apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Start chatting") }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
