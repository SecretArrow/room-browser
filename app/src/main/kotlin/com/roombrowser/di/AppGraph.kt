package com.roombrowser.di

import android.content.Context
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.filters.FilterListLoader
import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.IpConflictDetector
import com.roombrowser.domain.profile.ProfileManager

/**
 * Simple manual dependency graph (no framework needed for this scope).
 * Works in BOTH the main process and the ':browser' process.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val appState: AppStateRepository by lazy { AppStateRepository(database.appStateDao()) }

    val profileRepo: ProfileRepositoryImpl by lazy { ProfileRepositoryImpl(database) }

    val browserRepo: BrowserRepository by lazy { BrowserRepository(database) }

    val profileManager: ProfileManager by lazy { ProfileManager(profileRepo) }

    val filterEngine: FilterEngine by lazy { FilterListLoader.load(appContext) }

    val ipConflictDetector: IpConflictDetector by lazy { IpConflictDetector() }
}
