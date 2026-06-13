package com.example.bitperfectplayer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

@OptIn(UnstableApi::class)
class SmbDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource()
}
