package dev.shreyaspatil.debroid.cli.update

import dev.shreyaspatil.debroid.cli.VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Handles network requests to the GitHub Releases API.
 */
class GitHubReleaseClient(
    private val httpClient: HttpClient = defaultHttpClient()
) {

    private val json = Json { ignoreUnknownKeys = true }

    data class ReleaseInfo(val tagName: String, val downloadUrl: String)

    fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_API))
                .header("User-Agent", "debroid-cli/$VERSION")
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != HTTP_OK) return null

            val root = json.parseToJsonElement(response.body()).jsonObject
            val rawTag = root["tag_name"]?.jsonPrimitive?.content ?: return null
            val downloadUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$rawTag/debroid"

            ReleaseInfo(tagName = rawTag, downloadUrl = downloadUrl)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val REPO_OWNER = "PatilShreyas"
        private const val REPO_NAME = "debroid"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        private const val HTTP_OK = 200
        private const val TIMEOUT_SECONDS = 10L

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
        }
    }
}
