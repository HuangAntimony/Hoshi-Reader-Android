package moe.antimony.hoshi.features.sync

internal object GoogleCloudOAuthConfiguration {
    const val ttuSetupUrl: String = "https://github.com/ttu-ttu/ebook-reader?tab=readme-ov-file#storage-sources"

    const val introduction: String =
        "A Google Cloud project is required for syncing with Google Drive. Use the same project for Hoshi and ッツ so both apps can access the same Drive sync data."

    val instructions: List<String> = listOf(
        "If you have not configured ッツ or iOS sync before, follow the ッツ Google Cloud setup first and enable the Google Drive API.",
        "In the same Google Cloud project, go to APIs & Services -> Credentials -> Create Credentials -> OAuth client ID, then select Android as the application type.",
        "Use the same project as ッツ/iOS sync, but create a separate Android OAuth client ID for this app.",
        "Paste the package name and SHA-1 from this screen into the Android OAuth client.",
        "Save the client, wait a few minutes for Google Play services to refresh it, then return here and press Connect Google Drive.",
    )
}
