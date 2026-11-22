package com.mochyy.mochymusic.di

import com.mochyy.mochymusic.importer.LibrarySync
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import dagger.hilt.android.EntryPointAccessors

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibrarySyncEntryPoint {
    fun librarySync(): LibrarySync
}

fun Context.librarySync(): LibrarySync = EntryPointAccessors.fromApplication(
    applicationContext,
    LibrarySyncEntryPoint::class.java,
).librarySync()
