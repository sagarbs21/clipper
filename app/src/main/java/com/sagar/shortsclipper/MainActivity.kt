package com.sagar.shortsclipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sagar.shortsclipper.model.CropMode
import com.sagar.shortsclipper.model.OutputQuality
import com.sagar.shortsclipper.util.formatMs
import com.sagar.shortsclipper.util.parseTimeToMs

// Tablets (e.g. Xiaomi Pad 6, 11" 2880x1800) are wide; cap content width so the
// form stays readable and centered instead of stretching edge to edge.
private val CONTENT_MAX_WIDTH = 680.dp

class MainActivity : ComponentActivity() {

    private val vm: ClipViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ClipperScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipperScreen(vm: ClipViewModel) {
    // Keep the screen awake during export (also helps on MIUI/HyperOS power management).
    val view = LocalView.current
    LaunchedEffect(vm.exporting) {
        view.keepScreenOn = vm.exporting
    }

    // System file picker for offline videos on the device.
    val pickVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.loadLocalVideo(it) }
    }

    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Shorts Clipper") })
        }
    ) { innerPadding ->
        // Outer column applies window/scaffold insets and centers the constrained content.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scroll)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Use a YouTube link or a video on this device, set clip times, and export vertical 9:16 Shorts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = vm.url,
                    onValueChange = { vm.url = it },
                    label = { Text("YouTube URL") },
                    singleLine = true,
                    enabled = !vm.loading && !vm.exporting,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { vm.fetch() },
                    enabled = !vm.loading && !vm.exporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (vm.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (vm.loading) "Loading..." else "Fetch Video")
                }

                Text(
                    text = "— or —",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                OutlinedButton(
                    onClick = { pickVideo.launch(arrayOf("video/*")) },
                    enabled = !vm.loading && !vm.exporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose a video on this device")
                }

                // ---------- Settings ----------
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Settings", fontWeight = FontWeight.SemiBold)

                        Text(
                            "Output quality",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutputQuality.values().forEach { q ->
                                FilterChip(
                                    selected = vm.quality == q,
                                    onClick = { vm.updateQuality(q) },
                                    label = { Text(q.label) },
                                    enabled = !vm.exporting
                                )
                            }
                        }

                        OutlinedTextField(
                            value = vm.apiKey,
                            onValueChange = { vm.updateApiKey(it) },
                            label = { Text("Gemini API key (for AI suggestions)") },
                            singleLine = true,
                            enabled = !vm.suggesting && !vm.exporting,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Free key from aistudio.google.com/apikey. Used only for AI suggestions; manual clipping stays fully on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val meta = vm.meta
                if (meta != null) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(meta.title, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = "${meta.uploader}  •  ${formatMs(meta.durationSec * 1000)}  •  ${meta.resolution}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ---------- AI suggestions ----------
                    Button(
                        onClick = { vm.suggestClips() },
                        enabled = !vm.suggesting && !vm.exporting && vm.apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (vm.suggesting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (vm.suggesting) "Analyzing..." else "✨ Suggest clips with AI")
                    }
                    if (vm.apiKey.isBlank()) {
                        Text(
                            "Add a Gemini API key in Settings to enable AI suggestions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    vm.contentType?.let { type ->
                        Text(
                            "Detected content type: $type",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text("Crop mode", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CropMode.values().forEach { mode ->
                            FilterChip(
                                selected = vm.cropMode == mode,
                                onClick = { vm.cropMode = mode },
                                label = { Text(mode.label) },
                                enabled = !vm.exporting
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Clips",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.addClip() }, enabled = !vm.exporting) {
                            Text("+ Add clip")
                        }
                    }

                    vm.clips.forEachIndexed { idx, clip ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Clip ${idx + 1}",
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { vm.removeClip(clip.id) },
                                        enabled = !vm.exporting
                                    ) { Text("Remove") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = clip.start,
                                        onValueChange = { vm.updateClip(clip.id, start = it) },
                                        label = { Text("Start") },
                                        singleLine = true,
                                        enabled = !vm.exporting,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = clip.end,
                                        onValueChange = { vm.updateClip(clip.id, end = it) },
                                        label = { Text("End") },
                                        singleLine = true,
                                        enabled = !vm.exporting,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                OutlinedTextField(
                                    value = clip.name,
                                    onValueChange = { vm.updateClip(clip.id, name = it) },
                                    label = { Text("Output name") },
                                    singleLine = true,
                                    enabled = !vm.exporting,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                val startMs = parseTimeToMs(clip.start)
                                val endMs = parseTimeToMs(clip.end)
                                if (startMs != null && endMs != null && endMs - startMs > 180_000) {
                                    Text(
                                        "Longer than 3 min — may not qualify as a YouTube Short.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Times accept seconds (90), mm:ss (1:30), or h:mm:ss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { vm.export() },
                        enabled = !vm.exporting && vm.clips.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (vm.exporting) "Exporting..." else "Export Clips")
                    }

                    if (vm.exporting) {
                        LinearProgressIndicator(
                            progress = vm.progress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (vm.status.isNotEmpty()) {
                    Text(vm.status, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.size(24.dp))
            }
        }
    }
}
