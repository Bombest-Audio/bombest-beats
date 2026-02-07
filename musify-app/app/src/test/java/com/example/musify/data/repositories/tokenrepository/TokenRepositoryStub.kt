package com.bombest.musify.data.repositories.tokenrepository

import com.bombest.musify.data.remote.token.BearerToken
import java.time.LocalDateTime

class TokenRepositoryStub : TokenRepository {
    override suspend fun getValidBearerToken() = BearerToken(
        "",
        LocalDateTime.now(),
        60
    )
}