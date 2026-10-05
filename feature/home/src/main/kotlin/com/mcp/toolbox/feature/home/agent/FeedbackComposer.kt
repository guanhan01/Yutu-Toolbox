package com.mcp.toolbox.feature.home.agent

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 「反馈」。
 *
 * ## 为什么不做成「发到云端」
 *
 * 本应用没有服务端（会话、记忆、Key 全部只在本机），凭空塞一个上报通道
 * 既违背它的隐私定位，也要额外处理网络与鉴权。所以反馈走**用户可控**的两条路：
 *
 * 1. **提交事实**：把设备信息、版本、以及模型刚说错的原文整理成一段文本，
 *    让用户过目后自己决定发给谁（这里默认指向项目的 issue 页）。
 * 2. **邮件**：不想公开贴 issue 的用户可以走邮件。
 *
 * 关键点是**内容由用户过目**：不静默上传，也不偷偷附带会话全文。
 */
object FeedbackComposer {

    /** 项目 issue 页；与 `UpdateChecker.PROJECT_URL` 同源。 */
    private const val NEW_ISSUE_URL = "https://github.com/guanhan01/Yutu-Toolbox/issues/new"

    /**
     * 组装反馈正文。
     *
     * 只带**排查必需**的最小信息：版本、机型、Android 版本、当前模型，
     * 以及用户可见的那段错误回答（截断）。不带会话 id、不带其它消息。
     */
    fun compose(
        context: Context,
        model: String,
        answerExcerpt: String,
        comment: String = "",
    ): String = buildString {
        appendLine("## 问题描述")
        appendLine(comment.ifBlank { "（请在这里补充你遇到的问题）" })
        appendLine()
        appendLine("## 环境")
        appendLine("- 应用版本：${appVersion(context)}")
        appendLine("- 机型：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("- Android：${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        if (model.isNotBlank()) appendLine("- 模型：$model")
        if (answerExcerpt.isNotBlank()) {
            appendLine()
            appendLine("## 相关回答（截断）")
            appendLine("```")
            appendLine(answerExcerpt.take(1200))
            appendLine("```")
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 打开「新建 issue」页，正文预填。
     *
     * 用浏览器而不是内置 WebView：GitHub 的登录态在浏览器里，内置页面会要求重新登录。
     * `body` 通过 query 参数预填（GitHub 支持 `?body=`）。
     */
    fun openIssue(context: Context, title: String, body: String): Boolean = runCatching {
        val url = Uri.parse(NEW_ISSUE_URL)
            .buildUpon()
            .appendQueryParameter("title", title.take(120))
            .appendQueryParameter("body", body)
            .build()
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** 用邮件反馈（不想公开贴 issue 时）。 */
    fun openMail(context: Context, subject: String, body: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }.getOrDefault(false)
}
