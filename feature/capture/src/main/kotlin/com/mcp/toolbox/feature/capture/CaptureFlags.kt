package com.mcp.toolbox.feature.capture

/**
 * 运行时开关：界面里的「HTTPS 解密」直接改这里，抓包引擎在下一个 SYN 上立即生效。
 */
object CaptureFlags {
    @Volatile
    var httpsDecrypt: Boolean = false

    /**
     * 明确不信任用户 CA（或做了证书固定）的应用：本次会话内不再尝试解密，
     * 避免每一条 HTTPS 都被打断。由 MitmProxy 在客户端握手失败时写入。
     */
    private val skipPackages = java.util.Collections.synchronizedSet(HashSet<String>())
    private val skipUids = java.util.Collections.synchronizedSet(HashSet<Int>())

    fun skipDecrypt(packageName: String?, uid: Int): Boolean =
        (packageName != null && skipPackages.contains(packageName)) || (uid > 0 && skipUids.contains(uid))

    fun markDecryptFailed(packageName: String?, uid: Int) {
        if (!packageName.isNullOrEmpty()) skipPackages.add(packageName)
        if (uid > 0) skipUids.add(uid)
    }

    fun skippedPackages(): List<String> = skipPackages.toList().sorted()

    fun clearSkipped() {
        skipPackages.clear()
        skipUids.clear()
    }
}
