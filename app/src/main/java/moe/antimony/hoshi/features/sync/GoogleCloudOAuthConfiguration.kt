package moe.antimony.hoshi.features.sync

internal object GoogleCloudOAuthConfiguration {
    val instructions: List<String> = listOf(
        "Open Google Cloud Console and enable the Google Drive API for the project used by Hoshi.",
        "Go to APIs & Services -> Credentials -> Create Credentials -> OAuth client ID -> Android.",
        "Paste the package name and SHA-1 from this screen into the Android OAuth client.",
        "Save the client, wait a few minutes for Google Play services to refresh it, then return here and reconnect.",
    )
}
