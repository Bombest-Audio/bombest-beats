package com.bombest.musify.data.repositories.tokenrepository

import com.bombest.musify.data.remote.token.BearerToken
import com.bombest.musify.data.utils.FetchedResource
import com.bombest.musify.domain.MusifyErrorType
import com.bombest.musify.domain.getAssociatedMusifyErrorType
import com.fasterxml.jackson.core.JacksonException
import retrofit2.HttpException
import java.io.IOException

/**
 * A utility function used to run the [block] with a token retrieved
 * from the [TokenRepository] instance. It returns an instance of
 * [FetchedResource.Success] if the block did not throw an exception.
 * If the block throws either - a [HttpException] or an [IOException],
 * then [FetchedResource.Failure] containing the corresponding exception
 * will be returned. Any other exception thrown by the [block]
 * **will not be caught**.
 */
suspend fun <R> TokenRepository.runCatchingWithToken(block: suspend (BearerToken) -> R): FetchedResource<R, MusifyErrorType> =
    try {
        FetchedResource.Success(block(getValidBearerToken()))
    } catch (httpException: HttpException) {
        FetchedResource.Failure(httpException.getAssociatedMusifyErrorType())
    } catch (ioException: IOException) {
        FetchedResource.Failure(
            if (ioException is JacksonException) MusifyErrorType.DESERIALIZATION_ERROR
            else MusifyErrorType.NETWORK_CONNECTION_FAILURE
        )
    }