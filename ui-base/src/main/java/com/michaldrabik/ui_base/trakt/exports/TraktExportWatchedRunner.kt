package com.michaldrabik.ui_base.trakt.exports

import com.michaldrabik.common.extensions.dateIsoStringFromMillis
import com.michaldrabik.common.extensions.nowUtcMillis
import com.michaldrabik.common.extensions.toMillis
import com.michaldrabik.data_local.LocalDataSource
import com.michaldrabik.data_local.database.model.Episode
import com.michaldrabik.data_local.database.model.Movie
import com.michaldrabik.data_local.database.model.Show
import com.michaldrabik.data_remote.RemoteDataSource
import com.michaldrabik.data_remote.trakt.model.SyncExportItem
import com.michaldrabik.data_remote.trakt.model.SyncExportRequest
import com.michaldrabik.data_remote.trakt.model.SyncItem
import com.michaldrabik.repository.UserTraktManager
import com.michaldrabik.repository.settings.SettingsRepository
import com.michaldrabik.ui_base.trakt.TraktSyncRunner
import com.michaldrabik.ui_base.utilities.extensions.rethrowCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktExportWatchedRunner @Inject constructor(
  private val remoteSource: RemoteDataSource,
  private val localSource: LocalDataSource,
  private val settingsRepository: SettingsRepository,
  userTraktManager: UserTraktManager,
) : TraktSyncRunner(userTraktManager) {

  override suspend fun run(): Int {
    Timber.d("Initialized.")

    checkAuthorization()
    resetRetries()
    runExport()

    Timber.d("Finished with success.")
    return 0
  }

  private suspend fun runExport() {
    try {
      exportWatched()
    } catch (error: Throwable) {
      rethrowCancellation(error)
      if (retryCount.getAndIncrement() < MAX_EXPORT_RETRY_COUNT) {
        Timber.w("exportWatched failed. Will retry in $RETRY_DELAY_MS ms... $error")
        delay(RETRY_DELAY_MS)
        runExport()
      } else {
        throw error
      }
    }
  }

  private suspend fun exportWatched() {
    Timber.d("Exporting watched...")

    val remoteShows = remoteSource.trakt.fetchSyncWatchedShows()
      .filter { it.show != null }

    val localMyShows = localSource.myShows.getAll()
    val localEpisodes = batchEpisodes(localMyShows.map { it.idTrakt })
      .filter { !hasEpisodeBeenWatched(remoteShows, it) }

    val movies = mutableListOf<SyncExportItem>()
    if (settingsRepository.isMoviesEnabled) {
      val remoteMovies = remoteSource.trakt.fetchSyncWatchedMovies()
        .filter { it.movie != null }
      val localMyMoviesIds = localSource.myMovies.getAllTraktIds()
      val localMyMovies = batchMovies(localMyMoviesIds)
        .filter { movie -> remoteMovies.none { it.movie?.ids?.trakt == movie.idTrakt } }

      localMyMovies.mapTo(movies) {
        SyncExportItem.create(it.idTrakt, dateIsoStringFromMillis(it.updatedAt))
      }
    }

    val episodes = localEpisodes.map { ep ->
      val episodeTimestamp = ep.lastWatchedAt?.toMillis() ?: 0
      val showTimestamp = localMyShows.find { it.idTrakt == ep.idShowTrakt }?.updatedAt ?: 0
      val timestamp = when {
        episodeTimestamp > 0 -> episodeTimestamp
        showTimestamp > 0 -> showTimestamp
        else -> nowUtcMillis()
      }
      SyncExportItem.create(ep.idTrakt, dateIsoStringFromMillis(timestamp))
    }

    Timber.d("Exporting ${episodes.size} episodes & ${movies.size} movies...")
    if (episodes.isNotEmpty() || movies.isNotEmpty()) {
      val request = SyncExportRequest(episodes = episodes, movies = movies)
      postExportWatched(request)
    } else {
      Timber.d("Nothing to export. Skipping...")
    }

    delay(TRAKT_LIMIT_DELAY_MS)
    exportHidden()
  }

  private suspend fun postExportWatched(
    request: SyncExportRequest,
  ) {
    val episodes = request.episodes.toList()
    val movies = request.movies.toList()

    if (episodes.isEmpty() && movies.isEmpty()) {
      Timber.d("All batches exported.")
      return
    }

    val batchRequest = request.copy(
      episodes = episodes.take(1000),
      movies = movies.take(500)
    )

    Timber.d("Exporting batch ${batchRequest.episodes.size} episodes & ${batchRequest.movies.size} movies...")
    remoteSource.trakt.postSyncWatched(batchRequest)

    delay(TRAKT_LIMIT_DELAY_MS)
    postExportWatched(
      request.copy(
        episodes = request.episodes.filter { it !in batchRequest.episodes },
        movies = request.movies.filter { it !in batchRequest.movies }
      )
    )
  }

  private suspend fun exportHidden() = coroutineScope {
    Timber.d("Exporting hidden items...")

    val remoteShowsAsync = async { remoteSource.trakt.fetchHiddenShows() }
    val remoteMoviesAsync = async { remoteSource.trakt.fetchHiddenMovies() }
    val (remoteShows, remoteMovies) = awaitAll(remoteShowsAsync, remoteMoviesAsync)

    val showsAsync = async { localSource.archiveShows.getAll() }
    val moviesAsync = async { localSource.archiveMovies.getAll() }
    val (localShows, localMovies) = awaitAll(showsAsync, moviesAsync)

    val remoteShowsIds = remoteShows.mapNotNull { it.show?.ids?.trakt }
    val remoteMoviesIds = remoteMovies.mapNotNull { it.movie?.ids?.trakt }

    val showsItems = localShows
      .filter { (it as Show).idTrakt !in remoteShowsIds }
      .map {
        (it as Show).let { show ->
          SyncExportItem.create(
            traktId = show.idTrakt,
            hiddenAt = dateIsoStringFromMillis(show.updatedAt)
          )
        }
      }
    val moviesItems = localMovies
      .filter { (it as Movie).idTrakt !in remoteMoviesIds }
      .map {
        (it as Movie).let { movie ->
          SyncExportItem.create(
            traktId = movie.idTrakt,
            hiddenAt = dateIsoStringFromMillis(movie.updatedAt)
          )
        }
      }

    Timber.d("Exporting ${showsItems.size} hidden shows...")
    if (showsItems.isNotEmpty()) {
      showsItems.chunked(500).forEach { chunk ->
        remoteSource.trakt.postHiddenShows(shows = chunk)
        delay(TRAKT_LIMIT_DELAY_MS)
      }
      delay(TRAKT_LIMIT_DELAY_MS)
    } else {
      Timber.d("Nothing to export. Skipping...")
    }

    Timber.d("Exporting ${moviesItems.size} hidden movies...")
    if (moviesItems.isNotEmpty()) {
      moviesItems.chunked(500).forEach { chunk ->
        remoteSource.trakt.postHiddenMovies(movies = chunk)
        delay(TRAKT_LIMIT_DELAY_MS)
      }
      delay(TRAKT_LIMIT_DELAY_MS)
    } else {
      Timber.d("Nothing to export. Skipping...")
    }
  }

  private suspend fun batchEpisodes(
    showsIds: List<Long>,
    allEpisodes: MutableList<Episode> = mutableListOf(),
  ): List<Episode> {
    val batch = showsIds.take(250)
    if (batch.isEmpty()) return allEpisodes

    val episodes = localSource.episodes.getAllWatchedForShows(batch)
    allEpisodes.addAll(episodes)

    return batchEpisodes(showsIds.filter { it !in batch }, allEpisodes)
  }

  private suspend fun batchMovies(
    moviesIds: List<Long>,
    result: MutableList<Movie> = mutableListOf(),
  ): List<Movie> {
    val batch = moviesIds.take(500)
    if (batch.isEmpty()) return result

    val movies = localSource.myMovies.getAll(batch)
    result.addAll(movies)

    return batchMovies(moviesIds.filter { it !in batch }, result)
  }

  private fun hasEpisodeBeenWatched(remoteWatched: List<SyncItem>, episodeDb: Episode): Boolean {
    val find = remoteWatched
      .find { it.show?.ids?.trakt == episodeDb.idShowTrakt }
      ?.seasons?.find { it.number == episodeDb.seasonNumber }
      ?.episodes?.find { it.number == episodeDb.episodeNumber }
    return find != null
  }
}
