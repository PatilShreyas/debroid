package dev.shreyaspatil.debroid.cli.update

import dev.shreyaspatil.debroid.cli.VERSION
import dev.shreyaspatil.debroid.cli.models.CliUpdateResult
import java.util.concurrent.Executors

/**
 * High-level orchestrator facade for Debroid CLI self auto-updates.
 */
class AutoUpdateManager(
    private val releaseClient: GitHubReleaseClient = GitHubReleaseClient(),
    private val binaryUpdater: BinaryUpdater = BinaryUpdater(),
    private val updateCache: UpdateCache = UpdateCache(),
    private val skillExtractor: SkillExtractor = SkillExtractor()
) {

    private val backgroundExecutor by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Non-blocking, silent background check and update to stable releases.
     * Throttled to once every 24 hours.
     */
    fun checkAndPerformSilentAutoUpdateAsync(): Boolean {
        val isUpdated = syncSkillsIfUpdated()

        try {
            if (!updateCache.shouldCheckForUpdate()) return isUpdated

            updateCache.recordCheckTimestamp()

            backgroundExecutor.submit {
                try {
                    val releaseInfo = releaseClient.fetchLatestRelease() ?: return@submit
                    val latestVersion = SemanticVersion.parse(releaseInfo.tagName) ?: return@submit
                    val currentVersion = SemanticVersion.parse(VERSION)

                    // Only update if target version is a stable release newer than current
                    if (currentVersion == null || latestVersion.isNewerThan(currentVersion)) {
                        val targetBinary = binaryUpdater.resolveCurrentBinaryLocation() ?: return@submit
                        binaryUpdater.downloadAndReplaceBinary(releaseInfo.downloadUrl, targetBinary)
                    }
                } catch (_: Throwable) {
                    // Silent catch - background auto-update failure must never impact normal CLI commands
                }
            }
        } catch (_: Throwable) {
            // Ignore filesystem errors for cache check
        }
        return isUpdated
    }

    /**
     * Performs an on-demand update check or in-place binary upgrade.
     */
    fun checkOrUpdate(checkOnly: Boolean): CliUpdateResult {
        val releaseInfo = releaseClient.fetchLatestRelease()
            ?: return createFailureResult("No published stable release found on GitHub or unable to reach GitHub API.")

        val latestVersion = SemanticVersion.parse(releaseInfo.tagName)
            ?: return createFailureResult("Latest tag (${releaseInfo.tagName}) is not a recognized stable release.")

        val currentVersion = SemanticVersion.parse(VERSION)
        val cleanLatestTag = releaseInfo.tagName.removePrefix("v")
        val isNewer = currentVersion == null || latestVersion.isNewerThan(currentVersion)

        if (!isNewer) {
            return CliUpdateResult(
                currentVersion = VERSION,
                latestVersion = cleanLatestTag,
                updateAvailable = false,
                updated = false,
                message = "Debroid is already up to date (v$VERSION)."
            )
        }

        if (checkOnly) {
            return CliUpdateResult(
                currentVersion = VERSION,
                latestVersion = cleanLatestTag,
                updateAvailable = true,
                updated = false,
                message = "New stable Debroid version available: v$cleanLatestTag. Run 'debroid update' to upgrade."
            )
        }

        return performBinaryUpgrade(releaseInfo.downloadUrl, cleanLatestTag)
    }

    private fun createFailureResult(message: String) = CliUpdateResult(
        currentVersion = VERSION,
        latestVersion = VERSION,
        updateAvailable = false,
        updated = false,
        message = message
    )

    private fun performBinaryUpgrade(downloadUrl: String, cleanLatestTag: String): CliUpdateResult {
        val targetBinary = binaryUpdater.resolveCurrentBinaryLocation()
            ?: return CliUpdateResult(
                currentVersion = VERSION,
                latestVersion = cleanLatestTag,
                updateAvailable = true,
                updated = false,
                message = "Could not locate debroid binary file path on system."
            )

        val success = binaryUpdater.downloadAndReplaceBinary(downloadUrl, targetBinary)
        return CliUpdateResult(
            currentVersion = VERSION,
            latestVersion = cleanLatestTag,
            updateAvailable = true,
            updated = success,
            message = if (success) {
                "Successfully updated Debroid from v$VERSION to v$cleanLatestTag."
            } else {
                "Failed to replace binary file at ${targetBinary.absolutePath}."
            }
        )
    }

    /**
     * Synchronously checks if the CLI version has changed since the last run.
     * If so, extracts the new skills to the master symlink location.
     *
     * @return true if the CLI was just updated and skills were refreshed, false otherwise.
     */
    fun syncSkillsIfUpdated(): Boolean {
        try {
            val lastRunVersion = updateCache.readLastRunVersion()

            if (lastRunVersion != null && lastRunVersion != VERSION) {
                skillExtractor.extractSkillsToMaster()
                updateCache.recordLastRunVersion(VERSION)
                return true
            } else if (lastRunVersion == null) {
                skillExtractor.extractSkillsToMaster()
                updateCache.recordLastRunVersion(VERSION)
            }
        } catch (_: Throwable) {
            // Ignore filesystem errors during skill extraction
        }
        return false
    }

    companion object {
        val DEFAULT = AutoUpdateManager()
    }
}
