package com.kgrecap.nativeapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

data class ClipSegment(
    val number: Int,
    val startSec: Float,
    val endSec: Float,
    val isSilent: Boolean,
    var transcript: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecapStudioApp()
        }
    }
}

@Composable
fun RecapStudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var statusMessage by remember { mutableStateOf("ဗီဒီယိုတစ်ခု ရွေးချယ်ပြီး စတင်ပါ။") }
    var isProcessing by remember { mutableStateOf(false) }
    var silenceThresholdSec by remember { mutableFloatStateOf(0.8f) }
    var clips by remember { mutableStateOf(listOf<ClipSegment>()) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedVideoUri = uri
        if (uri != null) {
            statusMessage = "ဗီဒီယို ရွေးချယ်ပြီးပါပြီ။ Split နှိပ်ပြီး စမ်းသပ်နိုင်ပါတယ်။"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "NA RECAP STUDIO",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Groq API Key (gsk_...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { videoPicker.launch("video/*") },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text("Choose Video")
            }

            Button(
                onClick = {
                    val uri = selectedVideoUri
                    if (uri == null) {
                        statusMessage = "အရင်ဆုံး ဗီဒီယိုဖိုင် တစ်ခု ရွေးပါ။"
                        return@Button
                    }
                    isProcessing = true
                    statusMessage = "ဗီဒီယိုကို အပိုင်းလိုက် ခွဲနေပါသည်..."
                    scope.launch {
                        val generated = withContext(Dispatchers.Default) {
                            val step = silenceThresholdSec.coerceAtLeast(0.5f) * 5f
                            listOf(
                                ClipSegment(1, 0f, step, false, "အပိုင်း (၁) - ဇာတ်လမ်းအစ"),
                                ClipSegment(2, step, step + silenceThresholdSec, true, "[Silence Pause]"),
                                ClipSegment(3, step + silenceThresholdSec, step * 2f, false, "အပိုင်း (၂) - ဆက်လက်ဖြစ်ပျက်မှု")
                            )
                        }
                        clips = generated
                        isProcessing = false
                        statusMessage = "Clip ${generated.size} ခု ခွဲပြီးပါပြီ။"
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text("Split Clips")
            }
        }

        Text(
            text = "Min Silence Duration: ${"%.1f".format(silenceThresholdSec)}s",
            color = Color(0xFFB8C4D4),
            fontSize = 14.sp
        )
        Slider(
            value = silenceThresholdSec,
            onValueChange = { silenceThresholdSec = it },
            valueRange = 0.3f..3.0f
        )

        Button(
            onClick = {
                val uri = selectedVideoUri
                if (uri == null) {
                    statusMessage = "ဗီဒီယိုဖိုင် အရင်ရွေးပါ။"
                    return@Button
                }
                if (apiKey.isBlank()) {
                    statusMessage = "Groq API Key ထည့်ပေးပါ။"
                    return@Button
                }
                isProcessing = true
                statusMessage = "ဗီဒီယိုဖိုင်ကို Copy လုပ်ပြီး Whisper API ပို့နေပါသည်..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        transcribeUriWithGroq(context, uri, apiKey.trim())
                    }
                    result.onSuccess { text ->
                        clips = clips.mapIndexed { idx, clip ->
                            if (!clip.isSilent && idx == 0) clip.copy(transcript = text) else clip
                        }
                        statusMessage = "စာတန်းထုတ်ခြင်း ပြီးပါပြီ။"
                    }.onFailure { err ->
                        statusMessage = "Error: ${err.message ?: "Unknown error"}"
                    }
                    isProcessing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing
        ) {
            Text(if (isProcessing) "Processing..." else "Transcribe Audio (Groq)")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18202C))
        ) {
            Text(
                text = statusMessage,
                color = Color(0xFF7CE8B4),
                modifier = Modifier.padding(12.dp),
                fontSize = 14.sp
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(clips) { clip ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161D27))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Clip #${clip.number} (${"%.1f".format(clip.startSec)}s - ${"%.1f".format(clip.endSec)}s)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = clip.transcript.ifBlank { "စာတန်း မရှိသေးပါ" },
                            color = Color(0xFFD0D8E2),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

private fun transcribeUriWithGroq(
    context: android.content.Context,
    uri: Uri,
    apiKey: String
): Result<String> {
    return try {
        val tempFile = File(context.cacheDir, "input_media.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: return Result.failure(Exception("ဖိုင်ဖွင့်၍ မရပါ။"))

        val client = OkHttpClient()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                tempFile.name,
                tempFile.asRequestBody("video/*".toMediaTypeOrNull())
            )
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "text")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val respText = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Result.success(respText.trim())
            } else {
                Result.failure(Exception("HTTP ${response.code}: $respText"))
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
