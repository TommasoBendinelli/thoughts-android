// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.content.Context
import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.math.min

fun computeHash(bytes: ByteArray): String? {
    return runCatching {
        val digester = MessageDigest.getInstance("MD5")
        digester.update(bytes, 0, min(PDF.HASH_SIZE, bytes.size))
        String.format("%032x", BigInteger(1, digester.digest()))
    }.getOrNull()
}

fun computeHash(file: File): String? {
    return runCatching {
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(PDF.HASH_SIZE)
            var totalRead = 0
            while (totalRead < buffer.size) {
                val amountRead = stream.read(buffer, totalRead, buffer.size - totalRead)
                if (amountRead == -1) break
                totalRead += amountRead
            }
            if (totalRead == 0) return@use null
            val digester = MessageDigest.getInstance("MD5")
            digester.update(buffer, 0, totalRead)
            String.format("%032x", BigInteger(1, digester.digest()))
        }
    }.getOrNull()
}

fun computeSha256(file: File): String? {
    return runCatching {
        FileInputStream(file).use { stream ->
            val digester = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val amountRead = stream.read(buffer)
                if (amountRead == -1) break
                digester.update(buffer, 0, amountRead)
            }
            digester.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }.getOrNull()
}

suspend fun computeSha256(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let {
                return@runCatching computeSha256(it)
            }
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@runCatching null
            stream.use {
                val digester = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val amountRead = it.read(buffer)
                    if (amountRead == -1) break
                    digester.update(buffer, 0, amountRead)
                }
                digester.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
        }.onFailure { error ->
            Log.e("FileHash", "computeSha256 failed!", error)
        }.getOrNull()
    }
}

suspend fun computeHash(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return try {
        val digester = MessageDigest.getInstance("MD5")
        withContext(Dispatchers.IO) {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let { return@withContext computeHash(it) }
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            inputStream.use { stream ->
                val buffer = ByteArray(PDF.HASH_SIZE)
                var totalRead = 0
                while (totalRead < buffer.size) {
                    val amountRead = stream.read(buffer, totalRead, buffer.size - totalRead)
                    if (amountRead == -1) break
                    totalRead += amountRead
                }
                if (totalRead == 0) return@withContext null
                digester.update(buffer, 0, totalRead)
            }
        }
        String.format("%032x", BigInteger(1, digester.digest()))
    } catch (e: NoSuchAlgorithmException) {
        Log.e("FileHash", "NoSuchAlgorithmException: computeHash failed!", e)
        null
    } catch (e: IOException) {
        Log.e("FileHash", "IOException: computeHash failed!", e)
        null
    } catch (e: SecurityException) {
        Log.e("FileHash", "SecurityException: computeHash failed!", e)
        null
    } catch (e: Throwable) {
        Log.e("FileHash", "computeHash failed!", e)
        null
    }
}
