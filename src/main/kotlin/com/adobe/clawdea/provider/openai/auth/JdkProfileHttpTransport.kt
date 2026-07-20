/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.auth

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Production [ProfileHttpTransport] backed by [java.net.http.HttpClient]. Executes a single
 * credential-flow step request and returns its status + body. Used by the credential renewal path.
 */
class JdkProfileHttpTransport(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : ProfileHttpTransport {
    override fun execute(request: ProfileHttpRequest): ProfileHttpResponse {
        val builder = HttpRequest.newBuilder()
            .uri(request.uri)
            .timeout(Duration.ofSeconds(30))

        request.headers.forEach { (name, value) -> builder.header(name, value) }

        val bodyPublisher = if (request.body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(request.body)
        }
        builder.method(request.method.uppercase(), bodyPublisher)

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return ProfileHttpResponse(status = response.statusCode(), body = response.body() ?: "")
    }
}
