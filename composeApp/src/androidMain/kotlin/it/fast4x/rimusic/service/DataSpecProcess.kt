package it.fast4x.rimusic.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import it.fast4x.innertube.Innertube
import it.fast4x.innertube.models.PlayerResponse
import it.fast4x.innertube.models.bodies.PlayerBody
import it.fast4x.innertube.requests.player
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.enums.AudioQualityFormat
import it.fast4x.rimusic.models.Format
import it.fast4x.rimusic.utils.enableYouTubeLoginKey
import it.fast4x.rimusic.utils.preferences
import it.fast4x.rimusic.appContext
import me.knighthat.invidious.Invidious
import me.knighthat.invidious.request.player
import me.knighthat.piped.Piped
import me.knighthat.piped.request.player
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private suspend fun getPipedFormatUrl(
    videoId: String,
    audioQualityFormat: AudioQualityFormat
): Uri {
    val format = Piped.player( videoId )?.fold(
        {
            when (audioQualityFormat) {
                AudioQualityFormat.Auto -> it?.autoMaxQualityFormat
                AudioQualityFormat.High -> it?.highestQualityFormat
                AudioQualityFormat.Medium -> it?.mediumQualityFormat
                AudioQualityFormat.Low -> it?.lowestQualityFormat
            }.also {
                //println("PlayerService MyDownloadHelper DataSpecProcess getPipedFormatUrl before upsert format $it")
                Database.asyncTransaction {
                    if ( songExist(videoId) > 0 )
                        upsert(
                            Format(
                                songId = videoId,
                                itag = it?.itag?.toInt(),
                                mimeType = it?.mimeType,
                                contentLength = it?.contentLength?.toLong(),
                                bitrate = it?.bitrate?.toLong()
                            )
                        )
                }
                //println("PlayerService MyDownloadHelper DataSpecProcess getPipedFormatUrl after upsert format $it")
            }
        },
        {
            println("PlayerService MyDownloadHelper DataSpecProcess Error: ${it.stackTraceToString()}")
            throw it
        }
    )

    // Return parsed URL to play song or throw error if none of the responses is valid
    return Uri.parse( format?.url ) ?: throw NoSuchElementException( "Could not find any playable format from Piped ($videoId)" )
}

private suspend fun getInvidiousFormatUrl(
    videoId: String,
    audioQualityFormat: AudioQualityFormat
): Uri {
    val format = Invidious.player( videoId )?.fold(
        {
            when( audioQualityFormat ){
                AudioQualityFormat.Auto -> it?.autoMaxQualityFormat
                AudioQualityFormat.High -> it?.highestQualityFormat
                AudioQualityFormat.Medium -> it?.mediumQualityFormat
                AudioQualityFormat.Low -> it?.lowestQualityFormat
            }.also {
                //println("PlayerService MyDownloadHelper DataSpecProcess getInvidiousFormatUrl before upsert format $it")
                Database.asyncTransaction {
                    if ( songExist(videoId) > 0 )
                        upsert(
                            Format(
                                songId = videoId,
                                itag = it?.itag?.toInt(),
                                mimeType = it?.mimeType,
                                bitrate = it?.bitrate?.toLong()
                            )
                        )
                }
                //println("PlayerService MyDownloadHelper DataSpecProcess getInvidiousFormatUrl after upsert format $it")
            }
        },
        {
            println("PlayerService MyDownloadHelper DataSpecProcess Error: ${it.stackTraceToString()}")
            throw it
        }
    )

    // Return parsed URL to play song or throw error if none of the responses is valid
    return Uri.parse( format?.url ) ?: throw NoSuchElementException( "Could not find any playable format from Piped ($videoId)" )
}

@OptIn(UnstableApi::class)
internal suspend fun PlayerService.dataSpecProcess(
    dataSpec: DataSpec,
    context: Context,
    connectionMetered: Boolean
): DataSpec {
    val songUri = dataSpec.uri.toString()
    val videoId = songUri.substringAfter("watch?v=")
    val chunkLength = 512 * 1024L

    if( dataSpec.isLocal ||
        cache.isCached(videoId, dataSpec.position, chunkLength) ||
        downloadCache.isCached(videoId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)
    ) {
        println("PlayerService DataSpecProcess Playing song ${videoId} from cached or local file")
        return dataSpec.withUri(Uri.parse(dataSpec.uri.toString()))
    }

    try {

        val format = getInnerTubeFormatUrl(videoId, audioQualityFormat, connectionMetered)

        println("PlayerService DataSpecProcess Playing song ${videoId} from format $format from url=${format?.url}")
        return dataSpec.withUri(Uri.parse(format?.url))

    } catch ( e: LoginRequiredException ) {
        try {
            // Switch to Piped
            val formatUrl = getPipedFormatUrl( videoId, audioQualityFormat )

            println("PlayerService DataSpecProcess Playing song $videoId from url $formatUrl")
            return dataSpec.withUri( formatUrl )

        } catch ( e: NoSuchElementException ) {
            // Switch to Invidious
            val formatUrl = getInvidiousFormatUrl( videoId, audioQualityFormat )

            println("PlayerService DataSpecProcess Playing song $videoId from url $formatUrl")
            return dataSpec.withUri( formatUrl )
        }

    } catch ( e: Exception ) {
        // Rethrow exception if it's not handled
        throw e
    }
}

@OptIn(UnstableApi::class)
internal suspend fun MyDownloadHelper.dataSpecProcess(
    dataSpec: DataSpec,
    context: Context,
    connectionMetered: Boolean = false
): DataSpec {
    val songUri = dataSpec.uri.toString()
    val videoId = songUri.substringAfter("watch?v=")

    if( dataSpec.isLocal ||
        downloadCache.isCached(videoId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)
    ) {
        println("MyDownloadHelper DataSpecProcess Playing song ${videoId} from cached or local file")
        return dataSpec.withUri(Uri.parse(dataSpec.uri.toString()))
    }

    val format = getInnerTubeFormatUrl(videoId, audioQualityFormat, connectionMetered)

    println("MyDownloadHelper DataSpecProcess Playing song $videoId from format $format from url=${format?.url}")
    return dataSpec.withUri(Uri.parse(format?.url))

}

@OptIn(UnstableApi::class)
suspend fun getInnerTubeFormatUrl(
    videoId: String,
    audioQualityFormat: AudioQualityFormat,
    connectionMetered: Boolean,
    ): PlayerResponse.StreamingData.AdaptiveFormat? {
    //println("PlayerService MyDownloadHelper DataSpecProcess getMediaFormat Playing song $videoId from format $audioQualityFormat")
    return Innertube.player(
        body = PlayerBody(videoId = videoId),
        withLogin = false // TODO manage login appContext().preferences.getBoolean(enableYouTubeLoginKey, false),
        //pipedSession = getPipedSession().toApiSession()
    ).fold(
        { playerResponse ->

            when(playerResponse.playabilityStatus?.status) {
                "OK" -> {
                    when (audioQualityFormat) {
                        AudioQualityFormat.Auto -> if (!connectionMetered) playerResponse.streamingData?.autoMaxQualityFormat
                        else playerResponse.streamingData?.lowestQualityFormat
                        AudioQualityFormat.High -> playerResponse.streamingData?.highestQualityFormat
                        AudioQualityFormat.Medium -> playerResponse.streamingData?.mediumQualityFormat
                        AudioQualityFormat.Low -> playerResponse.streamingData?.lowestQualityFormat
                    }.let {
                        // Specify range to avoid YouTube's throttling
                        it?.copy(url = "${it.url}&range=0-${it.contentLength ?: 10000000}")
                    }.also {
                        //println("PlayerService MyDownloadHelper DataSpecProcess getMediaFormat before upsert format $it")
                        Database.asyncTransaction {
                            if ( songExist(videoId) > 0 )
                                upsert(
                                    Format(
                                        songId = videoId,
                                        itag = it?.itag?.toInt(),
                                        mimeType = it?.mimeType,
                                        contentLength = it?.contentLength,
                                        bitrate = it?.bitrate?.toLong(),
                                        lastModified = it?.lastModified,
                                        loudnessDb = playerResponse.playerConfig?.audioConfig?.loudnessDb,
                                    )
                                )
                        }
                        //println("PlayerService MyDownloadHelper DataSpecProcess getMediaFormat after upsert format $it")
                    }
                }
                "LOGIN_REQUIRED" -> throw LoginRequiredException()
                "UNPLAYABLE" -> throw UnplayableException()
                else -> throw UnknownException()
            }
        },
        { throwable ->
            when (throwable) {
                is ConnectException, is UnknownHostException -> {
                    throw NoInternetException()
                }

                is SocketTimeoutException -> {
                    throw TimeoutException()
                }

                else -> {
                    println("PlayerService MyDownloadHelper DataSpecProcess Error: ${throwable.stackTraceToString()}")
                    throw throwable
                }
            }

        }
    )
}