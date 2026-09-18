package com.mcp.toolbox.feature.capture

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * MITM 叶子证书与系统证书区适配。
 * - 复用 [CertificateAuthority] 已有的本地 CA（证书 + 私钥文件），本模块不重新造 CA；
 * - 按 SNI 现签 host 证书（复用同一把 RSA 2048 叶子私钥，避免每个域名都生成密钥）；
 * - 提供系统证书区所需的 OpenSSL subject_hash 文件名，便于放进 /system/etc/security/cacerts 或 Magisk / KernelSU 模块目录；
 * - 探测系统信任区里已安装的第三方 MITM CA（小黄鸟 / mitmproxy / Fiddler 等）。
 */
object MitmCa {
    private const val SUBJECT_NAME = "MCP Toolbox Capture CA"
    private const val ORGANIZATION = "MCP Toolbox"
    private const val LEAF_KEY_FILE = "capture-ca-leaf.pk8"
    private const val LEAF_ALIAS = "mcp-toolbox-mitm"
    private const val LEAF_DAYS = 365L
    private const val MAX_CACHE = 128

    private val cache =
        object : LinkedHashMap<String, Pair<X509Certificate, PrivateKey>>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<X509Certificate, PrivateKey>>
            ) = size > MAX_CACHE
        }

    private val knownMitm =
        listOf(
            "HttpCanary",
            "小黄鸟",
            "mitmproxy",
            "Fiddler",
            "Charles",
            "PortSwigger",
            "Burp",
            "Packet Capture",
            "Proxyman",
            "Reqable",
            "HTTP Toolkit",
            "MCP Toolbox Capture",
        )

    /**
     * 最近一次签发失败的原因。
     *
     * 界面上原先只显示笼统的「无法生成解密证书」，无从定位到底是 CA、私钥还是
     * DER 编码出错；这里把每一步的异常记下来，供记录详情展示。
     */
    @Volatile
    var lastLeafError: String? = null
        private set

    /** 取（或生成）host 的叶子证书与私钥；失败返回 null，调用方应回退为直连。 */
    fun leaf(context: Context, host: String): Pair<X509Certificate, PrivateKey>? {
        synchronized(cache) {
            cache[host]?.let {
                return it
            }
        }
        val ca = CertificateAuthority.ensure(context).getOrElse {
            lastLeafError = "CA 不可用：${it::class.simpleName}: ${it.message}"
            return null
        }
        val caKey = CertificateAuthority.loadPrivateKey(ca.keyFile)
        if (caKey == null) {
            lastLeafError = "CA 私钥读取失败（${ca.keyFile.name}）"
            return null
        }
        val leafKey = leafKeyPair(context)
        if (leafKey == null) {
            lastLeafError = "叶子私钥准备失败"
            return null
        }
        val signResult = runCatching { sign(host, leafKey.public, caKey) }
        val certificate = signResult.getOrElse {
            lastLeafError = "签名失败：${it::class.simpleName}: ${it.message}"
            return null
        }
        lastLeafError = null
        val pair = certificate to leafKey.private
        synchronized(cache) { cache[host] = pair }
        return pair
    }

    fun cachedCount(): Int = synchronized(cache) { cache.size }

    /** 系统证书目录要求的文件名：OpenSSL `-subject_hash`（MD5 前 4 字节小端）。 */
    /**
     * 当前 CA 证书的 subject DER（取自证书本体）。
     *
     * 系统证书目录的文件名必须是证书 subject DER 的 MD5 前 4 字节小端十六进制（等价于 `openssl x509 -subject_hash_old`），Android
     * 按这个哈希反查证书；手写 ASN.1 的编码顺序 与证书里的规范化编码可能不同，因此以证书本体为准。
     */
    @Volatile private var caSubjectDerBytes: ByteArray? = null

    /** 载入 CA 时调用，让 [systemFileName] 用证书本体的 subject DER 计算文件名。 */
    fun rememberCa(certificate: X509Certificate) {
        caSubjectDerBytes = certificate.subjectX500Principal.encoded
    }

    fun systemFileName(): String {
        val md5 = MessageDigest.getInstance("MD5").digest(caSubjectDerBytes ?: caSubjectDer())
        var value = 0L
        for (i in 0 until 4) value = value or ((md5[i].toLong() and 0xFF) shl (8 * i))
        return String.format(Locale.US, "%08x.0", value)
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") {
            String.format(Locale.US, "%02X", it)
        }

    /** 系统信任区里已存在的第三方 MITM CA（装了就说明别的抓包工具导过证书）。 */
    fun installedMitmCas(): List<String> =
        runCatching {
                val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val found = mutableListOf<String>()
                store.aliases().toList().forEach { alias ->
                    val certificate =
                        runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull()
                            ?: return@forEach
                    val subject = certificate.subjectX500Principal.name
                    if (knownMitm.any { subject.contains(it, ignoreCase = true) }) {
                        found += "$subject\n    指纹 ${fingerprint(certificate).take(47)}…"
                    }
                }
                found
            }
            .getOrDefault(emptyList())

    /** 供用户装到系统的证书正文（PEM）。 */
    fun systemCertPem(context: Context): String? =
        CertificateAuthority.ensure(context).getOrNull()?.pem

    /** 证书链 + 私钥的 KeyManager；一个 SSLContext 只服务一个 host。 */
    fun keyManager(host: String, pair: Pair<X509Certificate, PrivateKey>) =
        object : X509ExtendedKeyManager() {
            private val chain: Array<X509Certificate> = arrayOf(pair.first)

            override fun getClientAliases(
                keyType: String?,
                issuers: Array<out Principal>?
            ): Array<String>? = null

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = null

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<out Principal>?
            ): Array<String> = arrayOf(LEAF_ALIAS)

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String = LEAF_ALIAS

            override fun chooseEngineServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?,
            ): String = LEAF_ALIAS

            override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

            override fun getPrivateKey(alias: String?): PrivateKey = pair.second

            @Suppress("unused") fun hostOf(): String = host
        }

    /** Settings 里“安装 CA 证书”落地的目录；写进去就是用户信任区，目标 App 重启进程后即信任。 */
    const val USER_CA_DIR = "/data/misc/user/0/cacerts-added"

    /** 系统（APEX）证书区，Android 14+ 起真正的系统信任目录。 */
    const val SYSTEM_CA_DIR = "/apex/com.android.conscrypt/cacerts"

    /** 把 CA 写进用户证书区（只有 root 可写该目录；文件名必须用 Android 认的旧式 subject_hash）。 */
    fun userStoreInstallCommand(pem: String): String {
        val hash = systemFileName()
        return buildString {
            append("set -e\n")
            append("D=").append(USER_CA_DIR).append("\n")
            append("mkdir -p \"\$D\"\n")
            append("cat > \"\$D/").append(hash).append("\" <<'MCPCA'\n")
            append(pem.trim()).append("\n")
            append("MCPCA\n")
            append("chmod 644 \"\$D/").append(hash).append("\"\n")
            append("chown system:system \"\$D/").append(hash).append("\" 2>/dev/null || true\n")
            append("echo 已写入用户证书区\n")
        }
    }

    fun userStoreRemoveCommand(): String =
        "rm -f " + USER_CA_DIR + "/" + systemFileName() + " && echo 已移除用户证书区的这把 CA"

    /** 系统证书区的暂存目录：各 namespace 共用同一份文件，避免成倍占用内存。 */
    const val SYSTEM_STAGE_DIR = "/data/local/tmp/mcp-system-ca"

    private const val APEX_CA_DIR = "/apex/com.android.conscrypt/cacerts"

    /** 准备暂存目录：APEX 里的原始证书 + 这把 CA，并对齐 SELinux 标签。 */
    private fun stageScript(pem: String): String {
        val hash = systemFileName()
        return buildString {
            append("D=").append(APEX_CA_DIR).append("; S=").append(SYSTEM_STAGE_DIR).append("\n")
            append("[ -d \"\$D\" ] || D=/system/etc/security/cacerts\n")
            append("rm -rf \"\$S\" && mkdir -p \"\$S\"\n")
            append("cp -f \"\$D\"/* \"\$S\"/ 2>/dev/null || true\n")
            append("cat > \"\$S/").append(hash).append("\" <<'MCPCA'\n")
            append(pem.trim()).append("\n")
            append("MCPCA\n")
            append("chmod 644 \"\$S\"/* 2>/dev/null || true\n")
            append("L=\$(ls -Zd \"\$D\" 2>/dev/null | awk '{print \$1}')\n")
            append("[ -n \"\$L\" ] && chcon \"\$L\" \"\$S\" \"\$S\"/* 2>/dev/null\n")
            append("echo \"暂存证书 \$(ls \"\$S\" | wc -l) 张\"\n")
        }
    }

    /** 在 init 的 mount namespace 里用 tmpfs 覆盖 APEX 证书目录（以及旧的 /system/etc/security/cacerts）并同步暂存内容。 */
    private fun initNamespaceScript(): String = buildString {
        append("nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh -c '")
        append("D=")
            .append(APEX_CA_DIR)
            .append("; L=/system/etc/security/cacerts; S=")
            .append(SYSTEM_STAGE_DIR)
            .append("; ")
        append("mountpoint -q \$D || mount -t tmpfs tmpfs \$D; ")
        append("cp -f \$S/* \$D/ 2>/dev/null; chmod 644 \$D/* 2>/dev/null; ")
        append("if [ -d \$L ]; then mountpoint -q \$L || mount -t tmpfs tmpfs \$L; ")
        append("cp -f \$S/* \$L/ 2>/dev/null; chmod 644 \$L/* 2>/dev/null; fi; ")
        append("true' 2>&1 | tail -2\n")
    }

    /**
     * 把 CA 铺进正在运行的 App 进程的 mount namespace。已经启动的 App 有独立 namespace， 不会继承 init 的 tmpfs，必须逐个铺；之后启动的
     * App 由 zygote 继承，天然可见。APEX 目录与旧的 /system/etc/security/cacerts 都会覆盖。
     */
    private fun coverScript(packageNames: List<String>): String = buildString {
        val hash = systemFileName()
        append("D=")
            .append(APEX_CA_DIR)
            .append("; L=/system/etc/security/cacerts; S=")
            .append(SYSTEM_STAGE_DIR)
            .append("; HASH=")
            .append(hash)
            .append("\n")
        append("PKGS=\"").append(packageNames.joinToString(" ")).append("\"\n")
        append("OK=0; SKIP=0; RESTART=0\n")
        append("for F in /proc/[0-9]*/status; do\n")
        append("U=\"\"\n")
        append(
            "while read -r K U0 X Y Z; do [ \"\$K\" = \"Uid:\" ] && { U=\"\$U0\"; break; }; done 2>/dev/null < \"\$F\"\n")
        append("[ -n \"\$U\" ] || continue\n")
        append("[ \"\$U\" -ge 10000 ] 2>/dev/null || continue\n")
        append("P=\"\${F%/status}\"\n")
        append("if [ -n \"\$PKGS\" ]; then\n")
        append("C=\$(tr '\\0' '\\n' < \"\$P/cmdline\" 2>/dev/null | head -1); C=\${C%%:*}\n")
        append("[ -n \"\$C\" ] || continue\n")
        append("case \" \$PKGS \" in *\" \$C \"*) ;; *) continue ;; esac\n")
        append("fi\n")
        append(
            "if [ -e \"\$P/root\$D/\$HASH\" ] && [ -e \"\$P/root\$L/\$HASH\" ]; then SKIP=\$((SKIP+1)); continue; fi\n")
        append("if nsenter -t \$P -m -- /system/bin/sh -c 'D=")
            .append(APEX_CA_DIR)
            .append("; L=/system/etc/security/cacerts; S=")
            .append(SYSTEM_STAGE_DIR)
        append(
            "; mountpoint -q \$D || mount -t tmpfs tmpfs \$D; cp -f \$S/* \$D/ 2>/dev/null; chmod 644 \$D/* 2>/dev/null; if [ -d \$L ]; then mountpoint -q \$L || mount -t tmpfs tmpfs \$L; cp -f \$S/* \$L/ 2>/dev/null; chmod 644 \$L/* 2>/dev/null; fi; true' 2>/dev/null; then\n")
        append("[ -e \"\$P/root\$D/\$HASH\" ] && OK=\$((OK+1)) || RESTART=\$((RESTART+1))\n")
        append("else RESTART=\$((RESTART+1)); fi\n")
        append("done\n")
        append("echo \"App 进程：新覆盖 \$OK，已生效 \$SKIP，需重启 \$RESTART\"\n")
    }

    /** 装到系统证书区：init namespace + 所有正在运行的 App 进程，重启设备即还原。 */
    fun systemStoreInstallCommand(pem: String): String =
        stageScript(pem) +
            initNamespaceScript() +
            coverScript(emptyList()) +
            "echo 系统信任区已就绪；显示\"需重启\"的 App 请强制停止后重开\n"

    /**
     * 导出给 Magisk / KernelSU 模块的 service.sh：开机等 APEX 就绪后， 复用与 App 相同的「暂存 + 覆盖 APEX + 补铺已运行 App」流程。
     */
    fun moduleServiceScript(pem: String): String = buildString {
        append("#!/system/bin/sh\n")
        append("# 由 MCP Toolbox 生成：把抓包 CA 铺进系统信任区（Android 14+ 覆盖 Conscrypt APEX）\n")
        append("i=0\n")
        append("while [ \$i -lt 60 ] && [ ! -d ")
            .append(APEX_CA_DIR)
            .append(" ]; do sleep 2; i=\$((i+1)); done\n")
        append("sleep 3\n")
        append(systemStoreInstallCommand(pem))
    }

    /** 只针对指定包名补铺（用于跳过解密名单里的 App 重新尝试）。 */
    fun systemStoreCoverCommand(packages: List<String>): String =
        coverScript(packages) + "echo 已为这些 App 补铺证书\n"

    /** 只读检测：init 侧是否已铺入、有多少 App 进程已生效。 全部走 shell 内建（不再每个 PID fork 一个 stat），上百个进程也是毫秒级。 */
    fun systemStoreStatusCommand(): String {
        val hash = systemFileName()
        return buildString {
            append("D=").append(APEX_CA_DIR).append("; HASH=").append(hash).append("\n")
            append("if nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh -c '[ -f ")
                .append(APEX_CA_DIR)
                .append("/")
                .append(hash)
            append(
                " ]' 2>/dev/null; then echo \"系统区(init命名空间) 有\"; else echo \"系统区(init命名空间) 无\"; fi\n")
            append("OK=0; MISS=0\n")
            append("for F in /proc/[0-9]*/status; do\n")
            append("U=\"\"\n")
            append(
                "while read -r K U0 X Y Z; do [ \"\$K\" = \"Uid:\" ] && { U=\"\$U0\"; break; }; done 2>/dev/null < \"\$F\"\n")
            append("[ -n \"\$U\" ] || continue\n")
            append("[ \"\$U\" -ge 10000 ] 2>/dev/null || continue\n")
            append("P=\"\${F%/status}\"\n")
            append(
                "if [ -e \"\$P/root\$D/\$HASH\" ]; then OK=\$((OK+1)); else MISS=\$((MISS+1)); fi\n")
            append("done\n")
            append("echo \"已生效App进程 \$OK\"; echo \"待重开App进程 \$MISS\"\n")
        }
    }

    /** 还原：卸载所有 namespace 里的覆盖（APEX 与旧证书目录），并删除暂存目录。 */
    fun systemStoreRemoveCommand(): String = buildString {
        append("nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh -c '")
        append("umount ")
            .append(APEX_CA_DIR)
            .append(
                " 2>/dev/null; umount /system/etc/security/cacerts 2>/dev/null; true' 2>&1 | tail -2\n")
        append("N=0\n")
        append("for F in /proc/[0-9]*/status; do\n")
        append("U=\"\"\n")
        append(
            "while read -r K U0 X Y Z; do [ \"\$K\" = \"Uid:\" ] && { U=\"\$U0\"; break; }; done 2>/dev/null < \"\$F\"\n")
        append("[ -n \"\$U\" ] || continue\n")
        append("[ \"\$U\" -ge 10000 ] 2>/dev/null || continue\n")
        append("P=\"\${F%/status}\"\n")
        append("nsenter -t \$P -m -- /system/bin/sh -c 'D=")
            .append(APEX_CA_DIR)
            .append(
                "; L=/system/etc/security/cacerts; mountpoint -q \$D && umount \$D; mountpoint -q \$L && umount \$L; true' 2>/dev/null && N=\$((N+1))\n")
        append("done\n")
        append("rm -rf ").append(SYSTEM_STAGE_DIR).append("\n")
        append("echo \"已还原系统信任区（扫描 \$N 个 App 进程），相关 App 需重启\"\n")
    }

    /** 探测这把 CA 在各证书区的安装情况（证书页展示用，输出「标签=结论」）。 */
    fun trustProbeCommand(): String {
        val hash = systemFileName()
        return buildString {
            append("P=").append(USER_CA_DIR).append("\n")
            append("A=").append(SYSTEM_CA_DIR).append("\n")
            append("L=/system/etc/security/cacerts\n")
            append("if [ -f \"\$P/").append(hash).append("\" ]; then echo 用户证书区=已安装;")
            append(" else echo 用户证书区=未安装; fi\n")
            append("if [ -f \"\$A/").append(hash).append("\" ]; then echo 系统证书区\\(APEX\\)=已安装;")
            append(" else echo 系统证书区\\(APEX\\)=未安装; fi\n")
            append("if [ -L \"\$L\" ]; then echo 传统\\ \$L=不适用（本机已并入 APEX）;\n")
            append("elif [ -d \"\$L\" ]; then if [ -f \"\$L/").append(hash).append("\" ]; then")
            append(" echo \"传统 \$L=已安装\"; else echo \"传统 \$L=未安装（Android 14+ 非必需）\"; fi;\n")
            append("else echo 传统\\ \$L=不适用（本机无此目录）; fi\n")
            append("echo 用户区证书数=\$(ls ").append(USER_CA_DIR).append(" 2>/dev/null | wc -l)\n")
        }
    }

    /** 叶子密钥对：首次生成 RSA 2048 并落盘，之后复用。 */
    private fun leafKeyPair(context: Context): KeyPair? =
        runCatching {
                val dir = File(context.filesDir, "capture-ca")
                dir.mkdirs()
                val file = File(dir, LEAF_KEY_FILE)
                if (file.exists()) {
                    val key =
                        KeyFactory.getInstance("RSA")
                            .generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
                    val crt = key as? RSAPrivateCrtKey ?: return@runCatching null
                    val pub =
                        KeyFactory.getInstance("RSA")
                            .generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))
                    KeyPair(pub, key)
                } else {
                    val generator = KeyPairGenerator.getInstance("RSA")
                    generator.initialize(2048, SecureRandom())
                    val pair = generator.generateKeyPair()
                    file.writeBytes(pair.private.encoded)
                    pair
                }
            }
            .getOrNull()

    /** DER Name（RDNSequence），CertificateAuthority.name 是私有的，这里用公开的 der/utf8 组一份。 */
    private fun nameOf(vararg entries: Pair<ByteArray, String>): ByteArray =
        der(0x30, entries.map { der(0x30, listOf(it.first, utf8(it.second))) })

    private fun caSubjectDer(): ByteArray =
        nameOf(
            oidCommonName to SUBJECT_NAME,
            oidOrganization to ORGANIZATION,
        )

    /** 用 CA 私钥给 host 签一张叶子证书。 */
    private fun sign(host: String, publicKey: PublicKey, caKey: PrivateKey): X509Certificate {
        val subject =
            if (isIpLiteral(host)) {
                nameOf(oidCommonName to host)
            } else {
                nameOf(oidCommonName to host, oidOrganization to ORGANIZATION)
            }
        val tbs =
            der(
                0x30,
                listOf(
                    der(0xA0, integer(BigInteger.valueOf(2L))),
                    integer(BigInteger(MAX_CACHE, SecureRandom()).abs().add(BigInteger.ONE)),
                    algorithmIdentifier(),
                    caSubjectDer(),
                    validity(
                        System.currentTimeMillis() - 86_400_000L,
                        System.currentTimeMillis() + 31_536_000_000L,
                    ),
                    subject,
                    publicKey.encoded,
                    der(0xA3, leafExtensions(host)),
                ),
            )
        val signature =
            Signature.getInstance("SHA256withRSA").let { sig ->
                sig.initSign(caKey)
                sig.update(tbs)
                sig.sign()
            }
        val bytes =
            der(
                0x30,
                listOf(tbs, algorithmIdentifier(), bitString(signature)),
            )
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    private fun leafExtensions(host: String): ByteArray {
        val basic =
            der(
                0x30,
                listOf(
                    oidBasicConstraints,
                    der(0x01, byteArrayOf(0xFF.toByte())),
                    octetString(der(0x30, emptyList())),
                ),
            )
        val usage =
            der(
                0x30,
                listOf(
                    oidKeyUsage,
                    der(0x01, byteArrayOf(0xFF.toByte())),
                    octetString(der(0x03, byteArrayOf(0x05, 0xA0.toByte()))),
                ),
            )
        val eku =
            der(
                0x30,
                listOf(
                    oidExtKeyUsage,
                    octetString(der(0x30, listOf(oidServerAuth))),
                ),
            )
        val sanBytes =
            if (isIpLiteral(host)) {
                der(0x87, ipBytesOf(host))
            } else {
                der(0x82, host.toByteArray())
            }
        val san =
            der(
                0x30,
                listOf(
                    oidSubjectAltName,
                    octetString(der(0x30, listOf(sanBytes))),
                ),
            )
        return der(0x30, listOf(basic, usage, eku, san))
    }

    private fun isIpLiteral(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull() in 0..255
        }
    }

    private fun ipBytesOf(host: String): ByteArray =
        ByteArray(4) { host.split('.')[it].toInt().toByte() }

    private val oidSha256Rsa = oid(42, 134, 72, 134, 247, 13, 1, 1, 11)
    private val oidCommonName = oid(85, 4, 3)
    private val oidOrganization = oid(85, 4, 10)
    private val oidBasicConstraints = oid(85, 29, 19)
    private val oidKeyUsage = oid(85, 29, 15)
    private val oidExtKeyUsage = oid(85, 29, 37)
    private val oidSubjectAltName = oid(85, 29, 17)
    private val oidServerAuth = oid(43, 6, 1, 5, 5, 7, 3, 1)

    private fun algorithmIdentifier(): ByteArray = der(0x30, listOf(oidSha256Rsa, nullValue()))

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> {
                out.write(0x81)
                out.write(length)
            }
            length < 0x10000 -> {
                out.write(0x82)
                out.write(length shr 8)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write(length shr 16)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun der(tag: Int, parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return der(tag, out.toByteArray())
    }

    private fun integer(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun bitString(bytes: ByteArray): ByteArray = der(0x03, ByteArray(1) + bytes)

    private fun octetString(bytes: ByteArray): ByteArray = der(0x04, bytes)

    private fun oid(vararg value: Int): ByteArray =
        der(0x06, ByteArray(value.size) { value[it].toByte() })

    private fun utf8(value: String): ByteArray = der(0x0C, value.toByteArray())

    private fun nullValue(): ByteArray = der(0x05, ByteArray(0))

    private fun validity(notBefore: Long, notAfter: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return der(
            0x30,
            listOf(
                der(0x17, format.format(Date(notBefore)).toByteArray()),
                der(0x17, format.format(Date(notAfter)).toByteArray()),
            ),
        )
    }
}
