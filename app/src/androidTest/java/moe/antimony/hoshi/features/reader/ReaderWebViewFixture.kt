package moe.antimony.hoshi.features.reader

internal fun readerFixtureBody(mode: ReaderViewMode, content: String): String = when (mode) {
    ReaderViewMode.Continuous ->
        """<body><section data-hoshi-chapter="0"><hoshi-html><hoshi-body>$content</hoshi-body></hoshi-html></section></body>"""
    ReaderViewMode.Paginated -> "<body>$content</body>"
}
