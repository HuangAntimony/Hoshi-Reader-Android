package moe.antimony.hoshi

/** Build-time provenance shared by About and diagnostic reports. */
internal class BuildCommit private constructor(val sha: String) {
    val shortSha: String get() = sha.take(7)
    val snapshotUrl: String get() = "https://github.com/HuangAntimony/Hoshi-Reader-Android/tree/$sha"

    companion object {
        val current: BuildCommit? get() = fromSha(BuildConfig.BUILD_COMMIT_SHA)

        fun fromSha(sha: String?): BuildCommit? =
            sha?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }?.let(::BuildCommit)
    }
}
