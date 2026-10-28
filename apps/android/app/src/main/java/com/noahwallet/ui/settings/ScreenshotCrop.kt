package com.noahwallet.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr
import com.noahwallet.ui.WalletAlertDialog
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal data class ScreenshotCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    data class Pixels(val x: Int, val y: Int, val width: Int, val height: Int)
    fun pixels(width: Int, height: Int): Pixels {
        require(width > 0 && height > 0)
        val x = (left * width).roundToInt().coerceIn(0, width - 1)
        val y = (top * height).roundToInt().coerceIn(0, height - 1)
        return Pixels(x, y, ((right * width).roundToInt() - x).coerceIn(1, width - x),
            ((bottom * height).roundToInt() - y).coerceIn(1, height - y))
    }
}

internal fun decodeScreenshot(bytes: ByteArray): Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Could not read the selected screenshot." }
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Could not read the selected screenshot." }
}

internal fun cropScreenshot(image: Bitmap, crop: ScreenshotCrop): ByteArray {
    val bounds = crop.pixels(image.width, image.height)
    val cropped = Bitmap.createBitmap(image, bounds.x, bounds.y, bounds.width, bounds.height)
    return try {
        ByteArrayOutputStream().use { output ->
            check(cropped.compress(Bitmap.CompressFormat.JPEG, 80, output))
            output.toByteArray().also { FeedbackDraft.imageType(it) }
        }
    } finally { if (cropped !== image) cropped.recycle() }
}

@Composable
internal fun ScreenshotCropDialog(image: Bitmap, onCancel: () -> Unit, onCrop: (ScreenshotCrop) -> Unit) {
    var crop by rememberSaveable(image, stateSaver = listSaver(
        save = { listOf(it.left, it.top, it.right, it.bottom) },
        restore = { ScreenshotCrop(it[0], it[1], it[2], it[3]) },
    )) { mutableStateOf(ScreenshotCrop()) }
    val bitmap = remember(image) { image.asImageBitmap() }
    val left = tr("Left"); val right = tr("Right"); val top = tr("Top"); val bottom = tr("Bottom")
    WalletAlertDialog(onDismissRequest = onCancel, title = { Text(tr("Crop Screenshot")) }, text = {
        Column {
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                val scale = minOf(size.width / image.width, size.height / image.height)
                val width = image.width * scale; val height = image.height * scale
                val origin = Offset((size.width - width) / 2, (size.height - height) / 2)
                drawImage(bitmap, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = IntSize(width.roundToInt(), height.roundToInt()))
                val tint = Color.Black.copy(alpha = 0.65f)
                drawRect(tint, origin, Size(width, height * crop.top))
                drawRect(tint, origin + Offset(0f, height * crop.bottom), Size(width, height * (1 - crop.bottom)))
                drawRect(tint, origin + Offset(0f, height * crop.top), Size(width * crop.left, height * (crop.bottom - crop.top)))
                drawRect(tint, origin + Offset(width * crop.right, height * crop.top), Size(width * (1 - crop.right), height * (crop.bottom - crop.top)))
                drawRect(Color.White, origin + Offset(width * crop.left, height * crop.top),
                    Size(width * (crop.right - crop.left), height * (crop.bottom - crop.top)), style = Stroke(2.dp.toPx()))
            }
            Text(left)
            Slider(crop.left, { crop = crop.copy(left = it) }, modifier = Modifier.semantics { contentDescription = left },
                valueRange = 0f..(crop.right - 0.01f))
            Text(right)
            Slider(crop.right, { crop = crop.copy(right = it) }, modifier = Modifier.semantics { contentDescription = right },
                valueRange = (crop.left + 0.01f)..1f)
            Text(top)
            Slider(crop.top, { crop = crop.copy(top = it) }, modifier = Modifier.semantics { contentDescription = top },
                valueRange = 0f..(crop.bottom - 0.01f))
            Text(bottom)
            Slider(crop.bottom, { crop = crop.copy(bottom = it) }, modifier = Modifier.semantics { contentDescription = bottom },
                valueRange = (crop.top + 0.01f)..1f)
        }
    }, dismissButton = { TextButton(onCancel) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({ onCrop(crop) }) { Text(tr("Done")) } })
}
