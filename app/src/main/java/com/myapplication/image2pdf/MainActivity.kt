package com.myapplication.image2pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.myapplication.image2pdf.ui.theme.Image2PDFTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Image2PDFTheme {
                Image2PdfApp()
            }
        }
    }
}

data class EditableImage(
    val id: Long = System.nanoTime(),
    val uri: Uri,
    val name: String,
    val rotation: Int = 0,
    val scale: Float = 1f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val text: String = "",
    val watermark: String = "",
    val overlayImageUri: Uri? = null
)

data class HistoryEntry(
    val filename: String,
    val exportedAt: String,
    val location: String,
    val sizeBytes: Long
)

data class ExportResult(
    val file: File,
    val historyEntry: HistoryEntry
)

@Composable
fun Image2PdfApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<EditableImage>() }
    val history = remember { mutableStateListOf<HistoryEntry>() }
    var selectedImageId by remember { mutableStateOf<Long?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var isExporting by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var overlayTargetId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        history.clear()
        history.addAll(loadHistory(context))
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val imported = uris.map { uri ->
            context.persistReadPermission(uri)
            EditableImage(uri = uri, name = context.displayName(uri))
        }
        images.addAll(imported)
        if (selectedImageId == null) selectedImageId = imported.firstOrNull()?.id
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            val image = EditableImage(uri = uri, name = "Camera ${timestampForFile()}.jpg")
            images.add(image)
            selectedImageId = image.id
        }
        pendingCameraUri = null
    }

    val overlayPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = overlayTargetId
        if (uri != null && targetId != null) {
            context.persistReadPermission(uri)
            images.update(targetId) { it.copy(overlayImageUri = uri) }
        }
        overlayTargetId = null
    }

    fun export(combineIntoOnePdf: Boolean) {
        if (images.isEmpty()) {
            Toast.makeText(context, "Add at least one image first.", Toast.LENGTH_SHORT).show()
            return
        }
        isExporting = true
        coroutineScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (combineIntoOnePdf) {
                        listOf(exportImagesToSinglePdf(context, images.toList()))
                    } else {
                        exportImagesToSeparatePdfs(context, images.toList())
                    }
                }
            }
            isExporting = false
            result.onSuccess { exported ->
                history.addAll(0, exported.map { it.historyEntry })
                saveHistory(context, history.toList())
                saveMessage = exported.joinToString(separator = "\n\n") {
                    "${it.file.name}\n${it.file.absolutePath}\n${formatFileSize(it.file.length())}"
                }
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Convert") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("History") }
                )
            }

            if (selectedTab == 0) {
                ConvertScreen(
                    images = images,
                    selectedImageId = selectedImageId,
                    isExporting = isExporting,
                    onSelectImage = { selectedImageId = it },
                    onPickImages = { documentPicker.launch(arrayOf("image/*")) },
                    onTakePicture = {
                        val uri = context.createCameraImageUri()
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    onRemoveImage = { image ->
                        val index = images.indexOfFirst { it.id == image.id }
                        images.removeAll { it.id == image.id }
                        selectedImageId = images.getOrNull(index)?.id ?: images.lastOrNull()?.id
                    },
                    onMoveImage = { from, to ->
                        val image = images.removeAt(from)
                        images.add(to, image)
                    },
                    onImageChange = { id, transform -> images.update(id, transform) },
                    onPickOverlay = { id ->
                        overlayTargetId = id
                        overlayPicker.launch(arrayOf("image/*"))
                    },
                    onExportSingle = { export(false) },
                    onExportCombined = { export(true) }
                )
            } else {
                HistoryScreen(
                    history = history,
                    onDelete = { entry ->
                        history.remove(entry)
                        saveHistory(context, history.toList())
                    },
                    onClearAll = {
                        history.clear()
                        saveHistory(context, emptyList())
                    }
                )
            }
        }
    }

    saveMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { saveMessage = null },
            title = { Text("PDF saved") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { saveMessage = null }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Image2PDF",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Capture, edit, order, and export images as PDF files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConvertScreen(
    images: List<EditableImage>,
    selectedImageId: Long?,
    isExporting: Boolean,
    onSelectImage: (Long) -> Unit,
    onPickImages: () -> Unit,
    onTakePicture: () -> Unit,
    onRemoveImage: (EditableImage) -> Unit,
    onMoveImage: (Int, Int) -> Unit,
    onImageChange: (Long, (EditableImage) -> EditableImage) -> Unit,
    onPickOverlay: (Long) -> Unit,
    onExportSingle: () -> Unit,
    onExportCombined: () -> Unit
) {
    val selectedImage = images.firstOrNull { it.id == selectedImageId } ?: images.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTakePicture, modifier = Modifier.weight(1f)) {
                    Text("Take picture")
                }
                OutlinedButton(onClick = onPickImages, modifier = Modifier.weight(1f)) {
                    Text("Select images")
                }
            }
        }

        if (images.isEmpty()) {
            item {
                EmptyState()
            }
        } else {
            item {
                Text(
                    text = "Images",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            itemsIndexed(images, key = { _, image -> image.id }) { index, image ->
                ImageListItem(
                    image = image,
                    index = index,
                    total = images.size,
                    selected = image.id == selectedImage?.id,
                    onSelect = { onSelectImage(image.id) },
                    onMoveUp = { if (index > 0) onMoveImage(index, index - 1) },
                    onMoveDown = { if (index < images.lastIndex) onMoveImage(index, index + 1) },
                    onRemove = { onRemoveImage(image) }
                )
            }

            selectedImage?.let { image ->
                item {
                    EditorPanel(
                        image = image,
                        onChange = { transform -> onImageChange(image.id, transform) },
                        onPickOverlay = { onPickOverlay(image.id) }
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onExportCombined,
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isExporting) "Exporting..." else "One PDF")
                    }
                    OutlinedButton(
                        onClick = onExportSingle,
                        enabled = !isExporting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("PDF per image")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Start with a photo or image file.", fontWeight = FontWeight.SemiBold)
            Text(
                "You can add several images, reorder them, edit each one, and export a single PDF or one PDF per image.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImageListItem(
    image: EditableImage,
    index: Int,
    total: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ImagePreview(
                uri = image.uri,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .rotate(image.rotation.toFloat())
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${index + 1}. ${image.name}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (image.overlayImageUri != null) "Overlay image added" else "Ready to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onMoveUp, enabled = index > 0) { Text("Up") }
                    OutlinedButton(onClick = onMoveDown, enabled = index < total - 1) { Text("Down") }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun EditorPanel(
    image: EditableImage,
    onChange: ((EditableImage) -> EditableImage) -> Unit,
    onPickOverlay: () -> Unit
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Edit image", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = {
                        onChange {
                            it.copy(
                                rotation = 0,
                                scale = 1f,
                                cropLeft = 0f,
                                cropTop = 0f,
                                cropRight = 0f,
                                cropBottom = 0f,
                                contrast = 1f,
                                saturation = 1f,
                                sharpness = 0f,
                                text = "",
                                watermark = "",
                                overlayImageUri = null
                            )
                        }
                    },
                    enabled = image.hasEdits()
                ) {
                    Text("Reset")
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                EditedImagePreview(
                    image = image,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = image.scale,
                            scaleY = image.scale
                        )
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onChange { it.copy(rotation = normalizeRotation(it.rotation - 90)) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Rotate left")
                }
                OutlinedButton(
                    onClick = { onChange { it.copy(rotation = normalizeRotation(it.rotation + 90)) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Rotate right")
                }
            }

            EditSlider("Scale", image.scale, 0.5f..2f) { value ->
                onChange { it.copy(scale = value) }
            }
            EditSlider("Contrast", image.contrast, 0.5f..1.8f) { value ->
                onChange { it.copy(contrast = value) }
            }
            EditSlider("Saturation", image.saturation, 0f..2f) { value ->
                onChange { it.copy(saturation = value) }
            }
            EditSlider("Sharpness", image.sharpness, 0f..1f) { value ->
                onChange { it.copy(sharpness = value) }
            }

            HorizontalDivider()
            Text("Crop", fontWeight = FontWeight.SemiBold)
            CropGridEditor(
                image = image,
                onCropChange = { cropLeft, cropTop, cropRight, cropBottom ->
                    onChange {
                        it.copy(
                            cropLeft = cropLeft,
                            cropTop = cropTop,
                            cropRight = cropRight,
                            cropBottom = cropBottom
                        )
                    }
                }
            )

            HorizontalDivider()
            OutlinedTextField(
                value = image.text,
                onValueChange = { value -> onChange { it.copy(text = value) } },
                label = { Text("Text on image") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = image.watermark,
                onValueChange = { value -> onChange { it.copy(watermark = value) } },
                label = { Text("Watermark") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickOverlay, modifier = Modifier.weight(1f)) {
                    Text(if (image.overlayImageUri == null) "Add image overlay" else "Change overlay")
                }
                if (image.overlayImageUri != null) {
                    TextButton(
                        onClick = { onChange { it.copy(overlayImageUri = null) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear overlay")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(String.format(Locale.US, "%.2f", value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun CropGridEditor(
    image: EditableImage,
    onCropChange: (Float, Float, Float, Float) -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, image.uri) {
        value = withContext(Dispatchers.IO) { context.decodeBitmap(image.uri, maxDimension = 900) }
    }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableStateOf<CropCorner?>(null) }
    var localCrop by remember(image.id) {
        mutableStateOf(
            CropFractions(
                left = image.cropLeft,
                top = image.cropTop,
                right = image.cropRight,
                bottom = image.cropBottom
            )
        )
    }

    LaunchedEffect(image.id, image.cropLeft, image.cropTop, image.cropRight, image.cropBottom) {
        if (activeCorner == null) {
            localCrop = CropFractions(
                left = image.cropLeft,
                top = image.cropTop,
                right = image.cropRight,
                bottom = image.cropBottom
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { containerSize = it }
    ) {
        if (bitmap == null) {
            Spacer(modifier = Modifier.fillMaxSize())
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            ComposeCanvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(image.id, bitmap, containerSize) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val bounds = fittedImageBounds(
                                    containerWidth = size.width.toFloat(),
                                    containerHeight = size.height.toFloat(),
                                    imageWidth = bitmap!!.width.toFloat(),
                                    imageHeight = bitmap!!.height.toFloat()
                                )
                                activeCorner = nearestCropCorner(offset, cropRectFor(localCrop, bounds))
                            },
                            onDragEnd = {
                                activeCorner = null
                                onCropChange(localCrop.left, localCrop.top, localCrop.right, localCrop.bottom)
                            },
                            onDragCancel = {
                                activeCorner = null
                                onCropChange(localCrop.left, localCrop.top, localCrop.right, localCrop.bottom)
                            },
                            onDrag = { change, _ ->
                                val corner = activeCorner ?: return@detectDragGestures
                                val bounds = fittedImageBounds(
                                    containerWidth = size.width.toFloat(),
                                    containerHeight = size.height.toFloat(),
                                    imageWidth = bitmap!!.width.toFloat(),
                                    imageHeight = bitmap!!.height.toFloat()
                                )
                                localCrop = updateCropFromDrag(localCrop, bounds, change.position, corner)
                            }
                        )
                    }
            ) {
                val bounds = fittedImageBounds(
                    containerWidth = size.width,
                    containerHeight = size.height,
                    imageWidth = bitmap!!.width.toFloat(),
                    imageHeight = bitmap!!.height.toFloat()
                )
                val cropRect = cropRectFor(localCrop, bounds)
                val shade = ComposeColor.Black.copy(alpha = 0.48f)
                drawRect(shade, topLeft = Offset(bounds.left, bounds.top), size = Size(bounds.width(), cropRect.top - bounds.top))
                drawRect(shade, topLeft = Offset(bounds.left, cropRect.bottom), size = Size(bounds.width(), bounds.bottom - cropRect.bottom))
                drawRect(shade, topLeft = Offset(bounds.left, cropRect.top), size = Size(cropRect.left - bounds.left, cropRect.height()))
                drawRect(shade, topLeft = Offset(cropRect.right, cropRect.top), size = Size(bounds.right - cropRect.right, cropRect.height()))

                val lineColor = ComposeColor.White
                drawRect(
                    color = lineColor,
                    topLeft = Offset(cropRect.left, cropRect.top),
                    size = Size(cropRect.width(), cropRect.height()),
                    style = Stroke(width = 3f)
                )
                val verticalStep = cropRect.width() / 3f
                val horizontalStep = cropRect.height() / 3f
                for (index in 1..2) {
                    val x = cropRect.left + verticalStep * index
                    val y = cropRect.top + horizontalStep * index
                    drawLine(lineColor.copy(alpha = 0.85f), Offset(x, cropRect.top), Offset(x, cropRect.bottom), strokeWidth = 1.5f)
                    drawLine(lineColor.copy(alpha = 0.85f), Offset(cropRect.left, y), Offset(cropRect.right, y), strokeWidth = 1.5f)
                }
                cropRect.cornerOffsets().forEach { handle ->
                    drawCircle(ComposeColor.Black.copy(alpha = 0.55f), radius = 17f, center = handle)
                    drawCircle(lineColor, radius = 10f, center = handle)
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    history: List<HistoryEntry>,
    onDelete: (HistoryEntry) -> Unit,
    onClearAll: () -> Unit
) {
    if (history.isEmpty()) {
        EmptyHistory()
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${history.size} exported ${if (history.size == 1) "PDF" else "PDFs"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onClearAll) {
                    Text("Clear all")
                }
            }
        }
        items(history) { entry ->
            Card(shape = RoundedCornerShape(8.dp)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.filename,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onDelete(entry) }) {
                            Text("Delete")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(entry.exportedAt) })
                        AssistChip(onClick = {}, label = { Text(formatFileSize(entry.sizeBytes)) })
                    }
                    Text(
                        entry.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "Exported PDFs will appear here with filename, date and time, location, and file size.",
            modifier = Modifier.padding(18.dp)
        )
    }
}

@Composable
private fun ImagePreview(
    uri: Uri,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { context.decodeBitmap(uri, maxDimension = 700) }
    }

    if (bitmap == null) {
        Spacer(modifier = modifier)
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}

@Composable
private fun EditedImagePreview(
    image: EditableImage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, image) {
        value = withContext(Dispatchers.IO) {
            context.createEditedBitmap(image, maxDimension = 900)
        }
    }

    if (bitmap == null) {
        Spacer(modifier = modifier)
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}

private fun exportImagesToSinglePdf(context: Context, images: List<EditableImage>): ExportResult {
    val output = createOutputPdfFile(context, "image2pdf_${timestampForFile()}.pdf")
    writePdf(context, images, output)
    return ExportResult(output, output.toHistoryEntry())
}

private fun exportImagesToSeparatePdfs(context: Context, images: List<EditableImage>): List<ExportResult> {
    val stamp = timestampForFile()
    return images.mapIndexed { index, image ->
        val filename = "${safeBaseName(image.name)}_${stamp}_${index + 1}.pdf"
        val output = createOutputPdfFile(context, filename)
        writePdf(context, listOf(image), output)
        ExportResult(output, output.toHistoryEntry())
    }
}

private fun writePdf(context: Context, images: List<EditableImage>, output: File) {
    val pdf = PdfDocument()
    try {
        images.forEachIndexed { index, editableImage ->
            val bitmap = context.createEditedBitmap(editableImage, maxDimension = 2400) ?: return@forEachIndexed
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
            val page = pdf.startPage(pageInfo)
            drawBitmapOnPdfPage(page.canvas, bitmap, editableImage.scale)
            pdf.finishPage(page)
            bitmap.recycle()
        }
        FileOutputStream(output).use { pdf.writeTo(it) }
    } finally {
        pdf.close()
    }
}

private fun drawBitmapOnPdfPage(canvas: Canvas, bitmap: Bitmap, scale: Float) {
    canvas.drawColor(Color.WHITE)
    val margin = 32f
    val availableWidth = canvas.width - margin * 2
    val availableHeight = canvas.height - margin * 2
    val fittedScale = min(availableWidth / bitmap.width, availableHeight / bitmap.height) * scale
    val drawWidth = bitmap.width * fittedScale
    val drawHeight = bitmap.height * fittedScale
    val left = (canvas.width - drawWidth) / 2f
    val top = (canvas.height - drawHeight) / 2f
    canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), Paint(Paint.ANTI_ALIAS_FLAG))
}

private fun Context.createEditedBitmap(image: EditableImage, maxDimension: Int): Bitmap? {
    val source = decodeBitmap(image.uri, maxDimension = maxDimension) ?: return null
    val cropped = cropBitmap(source, image)
    if (cropped !== source) source.recycle()
    val rotated = rotateBitmap(cropped, image.rotation)
    if (rotated !== cropped) cropped.recycle()
    val filtered = applyColorEdits(rotated, image.contrast, image.saturation)
    if (filtered !== rotated) rotated.recycle()
    val sharpened = if (image.sharpness > 0.01f) sharpenBitmap(filtered, image.sharpness) else filtered
    if (sharpened !== filtered) filtered.recycle()
    return drawOverlays(this, sharpened, image)
}

private fun Context.decodeBitmap(uri: Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val largest = max(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (largest / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun cropBitmap(source: Bitmap, image: EditableImage): Bitmap {
    val left = (source.width * image.cropLeft).toInt().coerceIn(0, source.width - 1)
    val top = (source.height * image.cropTop).toInt().coerceIn(0, source.height - 1)
    val right = (source.width * (1f - image.cropRight)).toInt().coerceIn(left + 1, source.width)
    val bottom = (source.height * (1f - image.cropBottom)).toInt().coerceIn(top + 1, source.height)
    if (left == 0 && top == 0 && right == source.width && bottom == source.height) return source
    return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}

private fun rotateBitmap(source: Bitmap, rotation: Int): Bitmap {
    if (rotation == 0) return source
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun applyColorEdits(source: Bitmap, contrast: Float, saturation: Float): Bitmap {
    if (contrast == 1f && saturation == 1f) return source
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
    val translate = (-0.5f * contrast + 0.5f) * 255f
    val contrastMatrix = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    saturationMatrix.postConcat(contrastMatrix)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(saturationMatrix)
    }
    Canvas(output).drawBitmap(source, 0f, 0f, paint)
    return output
}

private fun sharpenBitmap(source: Bitmap, amount: Float): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val result = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val center = 1f + 4f * amount
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val i = y * width + x
            val current = pixels[i]
            val left = pixels[i - 1]
            val right = pixels[i + 1]
            val top = pixels[i - width]
            val bottom = pixels[i + width]
            val r = clamp(Color.red(current) * center - amount * (Color.red(left) + Color.red(right) + Color.red(top) + Color.red(bottom)))
            val g = clamp(Color.green(current) * center - amount * (Color.green(left) + Color.green(right) + Color.green(top) + Color.green(bottom)))
            val b = clamp(Color.blue(current) * center - amount * (Color.blue(left) + Color.blue(right) + Color.blue(top) + Color.blue(bottom)))
            result[i] = Color.argb(Color.alpha(current), r, g, b)
        }
    }
    for (x in 0 until width) {
        result[x] = pixels[x]
        result[(height - 1) * width + x] = pixels[(height - 1) * width + x]
    }
    for (y in 0 until height) {
        result[y * width] = pixels[y * width]
        result[y * width + width - 1] = pixels[y * width + width - 1]
    }
    return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
}

private fun drawOverlays(context: Context, source: Bitmap, image: EditableImage): Bitmap {
    if (image.text.isBlank() && image.watermark.isBlank() && image.overlayImageUri == null) return source
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)

    if (image.overlayImageUri != null) {
        context.decodeBitmap(image.overlayImageUri, maxDimension = min(source.width, source.height) / 2)?.let { overlay ->
            val overlayWidth = source.width * 0.32f
            val overlayHeight = overlay.height * (overlayWidth / overlay.width)
            val left = source.width - overlayWidth - source.width * 0.05f
            val top = source.height - overlayHeight - source.height * 0.05f
            canvas.drawBitmap(overlay, null, RectF(left, top, left + overlayWidth, top + overlayHeight), Paint(Paint.ANTI_ALIAS_FLAG))
            overlay.recycle()
        }
    }

    if (image.text.isNotBlank()) {
        val textSize = max(28f, source.width * 0.045f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(6f, 2f, 2f, Color.argb(180, 0, 0, 0))
            this.textSize = textSize
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val x = source.width * 0.06f
        val y = source.height - source.height * 0.07f
        canvas.drawText(image.text, x, y, paint)
    }

    if (image.watermark.isNotBlank()) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            textSize = max(42f, source.width * 0.09f)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 1f, 1f, Color.argb(80, 0, 0, 0))
        }
        canvas.save()
        canvas.rotate(-28f, source.width / 2f, source.height / 2f)
        canvas.drawText(image.watermark, source.width / 2f, source.height / 2f, paint)
        canvas.restore()
    }

    if (output !== source) source.recycle()
    return output
}

private fun MutableList<EditableImage>.update(id: Long, transform: (EditableImage) -> EditableImage) {
    val index = indexOfFirst { it.id == id }
    if (index >= 0) this[index] = transform(this[index])
}

private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

private enum class CropCorner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight
}

private data class CropFractions(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

private fun fittedImageBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float
): RectF {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return RectF(0f, 0f, containerWidth, containerHeight)
    }
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val fittedWidth = imageWidth * scale
    val fittedHeight = imageHeight * scale
    val left = (containerWidth - fittedWidth) / 2f
    val top = (containerHeight - fittedHeight) / 2f
    return RectF(left, top, left + fittedWidth, top + fittedHeight)
}

private fun cropRectFor(image: EditableImage, bounds: RectF): RectF {
    return cropRectFor(
        CropFractions(
            left = image.cropLeft,
            top = image.cropTop,
            right = image.cropRight,
            bottom = image.cropBottom
        ),
        bounds
    )
}

private fun cropRectFor(crop: CropFractions, bounds: RectF): RectF {
    val left = bounds.left + bounds.width() * crop.left
    val top = bounds.top + bounds.height() * crop.top
    val right = bounds.right - bounds.width() * crop.right
    val bottom = bounds.bottom - bounds.height() * crop.bottom
    return RectF(left, top, right, bottom)
}

private fun nearestCropCorner(offset: Offset, cropRect: RectF): CropCorner {
    val corners = listOf(
        CropCorner.TopLeft to Offset(cropRect.left, cropRect.top),
        CropCorner.TopRight to Offset(cropRect.right, cropRect.top),
        CropCorner.BottomLeft to Offset(cropRect.left, cropRect.bottom),
        CropCorner.BottomRight to Offset(cropRect.right, cropRect.bottom)
    )
    return corners.minBy { (_, point) ->
        val dx = offset.x - point.x
        val dy = offset.y - point.y
        dx * dx + dy * dy
    }.first
}

private fun updateCropFromDrag(
    crop: CropFractions,
    bounds: RectF,
    position: Offset,
    corner: CropCorner
): CropFractions {
    val minimumSize = 0.08f
    val draggedX = ((position.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
    val draggedY = ((position.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
    var left = crop.left
    var top = crop.top
    var rightEdge = 1f - crop.right
    var bottomEdge = 1f - crop.bottom

    when (corner) {
        CropCorner.TopLeft -> {
            left = min(draggedX, rightEdge - minimumSize)
            top = min(draggedY, bottomEdge - minimumSize)
        }
        CropCorner.TopRight -> {
            rightEdge = max(draggedX, left + minimumSize)
            top = min(draggedY, bottomEdge - minimumSize)
        }
        CropCorner.BottomLeft -> {
            left = min(draggedX, rightEdge - minimumSize)
            bottomEdge = max(draggedY, top + minimumSize)
        }
        CropCorner.BottomRight -> {
            rightEdge = max(draggedX, left + minimumSize)
            bottomEdge = max(draggedY, top + minimumSize)
        }
    }

    left = left.coerceIn(0f, 1f - minimumSize)
    top = top.coerceIn(0f, 1f - minimumSize)
    rightEdge = rightEdge.coerceIn(left + minimumSize, 1f)
    bottomEdge = bottomEdge.coerceIn(top + minimumSize, 1f)

    return CropFractions(
        left = left,
        top = top,
        right = 1f - rightEdge,
        bottom = 1f - bottomEdge
    )
}

private fun RectF.cornerOffsets(): List<Offset> = listOf(
    Offset(left, top),
    Offset(right, top),
    Offset(left, bottom),
    Offset(right, bottom)
)

private fun EditableImage.hasEdits(): Boolean =
    rotation != 0 ||
        scale != 1f ||
        cropLeft != 0f ||
        cropTop != 0f ||
        cropRight != 0f ||
        cropBottom != 0f ||
        contrast != 1f ||
        saturation != 1f ||
        sharpness != 0f ||
        text.isNotBlank() ||
        watermark.isNotBlank() ||
        overlayImageUri != null

private fun Context.createCameraImageUri(): Uri {
    val directory = File(cacheDir, "camera").apply { mkdirs() }
    val file = File(directory, "camera_${timestampForFile()}.jpg")
    return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
}

private fun createOutputPdfFile(context: Context, filename: String): File {
    val directory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
        "Image2PDF"
    ).apply { mkdirs() }
    return File(directory, filename)
}

private fun File.toHistoryEntry(): HistoryEntry = HistoryEntry(
    filename = name,
    exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    location = absolutePath,
    sizeBytes = length()
)

private fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Image ${timestampForFile()}"
}

private fun Context.persistReadPermission(uri: Uri) {
    runCatching {
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun loadHistory(context: Context): List<HistoryEntry> {
    val raw = context.getSharedPreferences("image2pdf", Context.MODE_PRIVATE).getString("history", "[]")
    val array = JSONArray(raw)
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        HistoryEntry(
            filename = item.getString("filename"),
            exportedAt = item.getString("exportedAt"),
            location = item.getString("location"),
            sizeBytes = item.getLong("sizeBytes")
        )
    }
}

private fun saveHistory(context: Context, history: List<HistoryEntry>) {
    val array = JSONArray()
    history.take(100).forEach { entry ->
        array.put(
            JSONObject()
                .put("filename", entry.filename)
                .put("exportedAt", entry.exportedAt)
                .put("location", entry.location)
                .put("sizeBytes", entry.sizeBytes)
        )
    }
    context.getSharedPreferences("image2pdf", Context.MODE_PRIVATE)
        .edit()
        .putString("history", array.toString())
        .apply()
}

private fun safeBaseName(name: String): String {
    val base = name.substringBeforeLast('.').ifBlank { "image" }
    return base.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
}

private fun timestampForFile(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

private fun formatFileSize(sizeBytes: Long): String {
    val kb = sizeBytes / 1024f
    if (kb < 1024f) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024f)
}

private fun clamp(value: Float): Int = value.toInt().coerceIn(0, 255)

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    Image2PDFTheme {
        Header()
    }
}
