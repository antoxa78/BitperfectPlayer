package com.example.bitperfectplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var smbFile: SmbFile? = null
    private var randomAccessFile: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri

        try {
            smbFile = SmbFile(uri.toString())
            randomAccessFile = SmbRandomAccessFile(smbFile!!, "r").also { raf ->
                if (dataSpec.position > 0) raf.seek(dataSpec.position)
            }

            val fileLength = smbFile?.length() ?: 0L
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                fileLength > 0                             -> fileLength - dataSpec.position
                else                                       -> C.LENGTH_UNSET.toLong()
            }
        } catch (e: Exception) {
            throw IOException(e)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val isUnbounded = bytesRemaining == C.LENGTH_UNSET.toLong()
        val bytesToRead = if (isUnbounded) length else minOf(bytesRemaining, length.toLong()).toInt()

        val bytesRead = try {
            randomAccessFile?.read(buffer, offset, bytesToRead) ?: -1
        } catch (e: IOException) {
            throw IOException(e)
        }

        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        if (!isUnbounded) bytesRemaining -= bytesRead

        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            randomAccessFile?.close()
        } catch (e: IOException) {
            // Re-throw so ExoPlayer can handle it; finally ensures state is always cleaned up
            throw IOException(e)
        } finally {
            randomAccessFile = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
