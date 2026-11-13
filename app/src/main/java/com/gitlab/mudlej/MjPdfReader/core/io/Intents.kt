// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class SharedFile(
    val fileName: String,
    val mimeType: String,
    val uri: Uri,
)

fun plainTextShareIntent(chooserTitle: String, text: String): Intent {
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TEXT, text)
    return Intent.createChooser(intent, chooserTitle)
}
fun fileShareIntent(
    chooserTitle: String,
    fileName: String,
    fileUri: Uri,
    text: String? = null,
): Intent {
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "application/pdf"
    intent.putExtra(Intent.EXTRA_STREAM, fileUri)
    text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
    val clipItem = if (text == null) {
        ClipData.Item(fileUri)
    } else {
        ClipData.Item(text, null, fileUri)
    }
    intent.clipData = ClipData(fileName, arrayOf("application/pdf"), clipItem)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(intent, chooserTitle)
}
fun filesShareIntent(chooserTitle: String, files: List<SharedFile>): Intent {
    require(files.isNotEmpty()) { "At least one shared file is required" }
    val uris = ArrayList(files.map(SharedFile::uri))
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
    intent.type = if (files.map(SharedFile::mimeType).distinct().size == 1) {
        files.first().mimeType
    } else {
        "*/*"
    }
    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    val first = files.first()
    intent.clipData = ClipData(
        first.fileName,
        files.map(SharedFile::mimeType).distinct().toTypedArray(),
        ClipData.Item(first.uri),
    ).apply {
        files.drop(1).forEach { addItem(ClipData.Item(it.uri)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(intent, chooserTitle)
}
fun imageShareIntent(chooserTitle: String, fileName: String, fileUri: Uri): Intent {
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "image/*"
    intent.putExtra(Intent.EXTRA_STREAM, fileUri)
    intent.clipData = ClipData(fileName, arrayOf("image/*"), ClipData.Item(fileUri))
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(intent, chooserTitle)
}
fun navIntent(context: Context, activity: Class<*>) = Intent(context, activity)

fun linkIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

fun browserLinkIntent(url: String): Intent =
    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).setData(Uri.parse(url))

fun Context.openChatGptLink(url: String): Boolean {
    val appIntent = linkIntent(url).setPackage(CHATGPT_PACKAGE_NAME)
    if (isChatGptEnabled() && appIntent.resolveActivity(packageManager) != null) {
        try {
            startActivity(appIntent)
            return true
        } catch (_: ActivityNotFoundException) {
        }
    }
    return try {
        startActivity(browserLinkIntent(url))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun Context.isChatGptEnabled(): Boolean = try {
    packageManager.getApplicationEnabledSetting(CHATGPT_PACKAGE_NAME) !in setOf(
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
    )
} catch (_: IllegalArgumentException) {
    false
}

fun emailIntent(emailAddress: String, subject: String, text: String): Intent {
    val email = Intent(Intent.ACTION_SENDTO)
    email.data = Uri.parse("mailto:$emailAddress")
    email.putExtra(Intent.EXTRA_SUBJECT, subject)
    email.putExtra(Intent.EXTRA_TEXT, text)
    return email
}

fun restartApplication(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launchIntent)
    Runtime.getRuntime().exit(0)
}

private const val CHATGPT_PACKAGE_NAME = "com.openai.chatgpt"
