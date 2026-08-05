package dev.shreyaspatil.debroid.cli.update

/**
 * Represents a parsed semantic version (major.minor.patch).
 * Strictly enforces stable releases and compares versions safely.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val isStable: Boolean = true
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    fun isNewerThan(other: SemanticVersion): Boolean {
        if (!isStable || !other.isStable) return false
        return this > other
    }

    companion object {
        private val stableVersionRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(versionString: String): SemanticVersion? {
            val match = stableVersionRegex.matchEntire(versionString.trim()) ?: return null
            val (maj, min, pat) = match.destructured
            return SemanticVersion(
                major = maj.toInt(),
                minor = min.toInt(),
                patch = pat.toInt(),
                isStable = true
            )
        }
    }
}
