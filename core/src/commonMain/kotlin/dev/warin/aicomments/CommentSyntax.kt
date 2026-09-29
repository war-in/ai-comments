package dev.warin.aicomments

/**
 * How comments and strings look in a family of file types, as far as the [CommentLexer] needs to know.
 *
 * @param lineHashNeedsSpace a `#` only starts a comment at line start or after whitespace, as in YAML
 *   or shell, where `a#b` and `$#` are not comments.
 * @param jsx the file may contain JSX, where `{/* … */}` is a comment-only expression whose braces go with it.
 */
data class CommentSyntax(
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val singleLineStrings: List<Char> = emptyList(),
    val multiLineStrings: List<String> = emptyList(),
    val docCommentPrefix: String? = null,
    val lineHashNeedsSpace: Boolean = false,
    val jsx: Boolean = false,
)

/**
 * The file types AI comments are allowed in. This is the single source of truth: the Claude rule lists these,
 * the CLI strips only these, and the IDE warns about AI comments anywhere else.
 */
object CommentLanguages {
    private val cFamily = CommentSyntax(
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        singleLineStrings = listOf('\'', '"'),
        multiLineStrings = listOf("\"\"\"", "`"),
        docCommentPrefix = "/**",
    )
    private val cFamilyWithJsx = cFamily.copy(jsx = true)
    private val css = CommentSyntax(blockComments = listOf("/*" to "*/"), singleLineStrings = listOf('\'', '"'))
    private val markup = CommentSyntax(blockComments = listOf("<!--" to "-->"), singleLineStrings = listOf('"'))

    /** Markup with embedded scripts and styles, where both comment styles appear in one file. */
    private val markupWithScripts = CommentSyntax(
        lineComments = listOf("//"),
        blockComments = listOf("<!--" to "-->", "/*" to "*/"),
        singleLineStrings = listOf('"'),
        docCommentPrefix = "/**",
    )
    private val mdx = CommentSyntax(blockComments = listOf("<!--" to "-->", "/*" to "*/"), docCommentPrefix = "/**")
    private val hash = CommentSyntax(
        lineComments = listOf("#"),
        singleLineStrings = listOf('\'', '"'),
        multiLineStrings = listOf("\"\"\"", "'''"),
        lineHashNeedsSpace = true,
    )
    /** Formats without string syntax, where an apostrophe in a value must not hide a later comment. */
    private val hashPlain = CommentSyntax(lineComments = listOf("#"), lineHashNeedsSpace = true)
    private val markdown = CommentSyntax(blockComments = listOf("<!--" to "-->"))
    private val dashDash = CommentSyntax(lineComments = listOf("--"), singleLineStrings = listOf('\'', '"'))

    private val byExtension: Map<String, CommentSyntax> = buildMap {
        listOf(
            "mjs", "cjs", "ts", "mts", "cts", "json5", "jsonc",
            "kt", "kts", "java", "gradle", "groovy", "scala", "swift", "m", "mm", "h", "c", "cc", "cpp", "hpp",
            "go", "rs", "dart",
        ).forEach { put(it, cFamily) }
        // React Native projects put JSX in plain .js files too.
        listOf("js", "jsx", "tsx").forEach { put(it, cFamilyWithJsx) }
        listOf("css", "scss", "less").forEach { put(it, css) }
        put("md", markdown)
        listOf("xml", "svg", "xib", "storyboard", "plist").forEach { put(it, markup) }
        listOf("html", "htm", "vue", "svelte").forEach { put(it, markupWithScripts) }
        put("mdx", mdx)
        listOf("py", "rb", "rake", "sh", "bash", "zsh", "yml", "yaml", "toml").forEach { put(it, hash) }
        listOf("properties", "env").forEach { put(it, hashPlain) }
        listOf("sql", "lua").forEach { put(it, dashDash) }
    }

    private val byFileName: Map<String, CommentSyntax> =
        listOf("Dockerfile", "Makefile", "Podfile", "Gemfile", "Fastfile", "Appfile", "Brewfile").associateWith { hash } +
            listOf(".gitignore").associateWith { hashPlain }

    fun forPath(path: String): CommentSyntax? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        byFileName[name]?.let { return it }
        if (name.startsWith(".env.")) return hashPlain
        val extension = name.substringAfterLast('.', "")
        return if (extension.isEmpty()) null else byExtension[extension.lowercase()]
    }

    fun isSupported(path: String) = forPath(path) != null

    /** Human-readable list for the Claude rule, e.g. "`.c`, `.cc`, …, `Dockerfile`". */
    fun describeSupported(): String =
        (byExtension.keys.sorted().map { "`.$it`" } + byFileName.keys.sorted().map { "`$it`" } + "`.env.*`").joinToString(", ")
}
