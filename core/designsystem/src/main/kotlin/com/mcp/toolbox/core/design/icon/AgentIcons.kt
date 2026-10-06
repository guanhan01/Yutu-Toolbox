package com.mcp.toolbox.core.design.icon

import androidx.annotation.DrawableRes
import com.mcp.toolbox.core.design.R

/**
 * AI Agent 单色图标集（364 枚）的编译期索引。
 *
 * 矢量资源本体在 `core/designsystem/src/main/res/drawable/ic_*.xml`（24dp 网格、
 * 2.0dp 主描边 / 1.6dp 细节描边、墨色 #1F2333）。单色绘制意味着它们可以被 tint
 * 成任意语义色，所以这里的每个常量只表达「画什么」，不表达「什么颜色」——
 * 颜色一律由调用点从主题取（见 [com.mcp.toolbox.core.design.theme.MiuixColors]）。
 *
 * 为什么不用 `ImageVector` + `Icons.*`：矢量 XML 由 AAPT2 编译并参与资源合并，
 * 不需要在 Dex 里再放一份路径数据；364 枚图标的 Kotlin 字面量会让 Dex 明显变大。
 *
 * ⚠️ 不要直接写 `R.drawable.ic_xxx`：跨模块引用时若开了非传递 R 类，只有本模块的
 * `R` 可见。统一走这里，调用点就与 R 类隔离。
 */
object AgentIcons {
    @get:DrawableRes
    val ic_ai_sparkle: Int get() = R.drawable.ic_ai_sparkle
    @get:DrawableRes
    val ic_ai_assistant: Int get() = R.drawable.ic_ai_assistant
    @get:DrawableRes
    val ic_bot: Int get() = R.drawable.ic_bot
    @get:DrawableRes
    val ic_ai_chip: Int get() = R.drawable.ic_ai_chip
    @get:DrawableRes
    val ic_model: Int get() = R.drawable.ic_model
    @get:DrawableRes
    val ic_neural: Int get() = R.drawable.ic_neural
    @get:DrawableRes
    val ic_brain: Int get() = R.drawable.ic_brain
    @get:DrawableRes
    val ic_prompt: Int get() = R.drawable.ic_prompt
    @get:DrawableRes
    val ic_token: Int get() = R.drawable.ic_token
    @get:DrawableRes
    val ic_embedding: Int get() = R.drawable.ic_embedding
    @get:DrawableRes
    val ic_machine_learning: Int get() = R.drawable.ic_machine_learning
    @get:DrawableRes
    val ic_ai_generate: Int get() = R.drawable.ic_ai_generate
    @get:DrawableRes
    val ic_ai_search: Int get() = R.drawable.ic_ai_search
    @get:DrawableRes
    val ic_smart_reply: Int get() = R.drawable.ic_smart_reply
    @get:DrawableRes
    val ic_automation: Int get() = R.drawable.ic_automation
    @get:DrawableRes
    val ic_ai_network: Int get() = R.drawable.ic_ai_network
    @get:DrawableRes
    val ic_ai_vision: Int get() = R.drawable.ic_ai_vision
    @get:DrawableRes
    val ic_ai_summary: Int get() = R.drawable.ic_ai_summary
    @get:DrawableRes
    val ic_ai_code: Int get() = R.drawable.ic_ai_code
    @get:DrawableRes
    val ic_workflow: Int get() = R.drawable.ic_workflow
    @get:DrawableRes
    val ic_pipeline: Int get() = R.drawable.ic_pipeline
    @get:DrawableRes
    val ic_plugin: Int get() = R.drawable.ic_plugin
    @get:DrawableRes
    val ic_api: Int get() = R.drawable.ic_api
    @get:DrawableRes
    val ic_webhook: Int get() = R.drawable.ic_webhook
    @get:DrawableRes
    val ic_integration: Int get() = R.drawable.ic_integration
    @get:DrawableRes
    val ic_stepper: Int get() = R.drawable.ic_stepper
    @get:DrawableRes
    val ic_data_flow: Int get() = R.drawable.ic_data_flow
    @get:DrawableRes
    val ic_orchestration: Int get() = R.drawable.ic_orchestration
    @get:DrawableRes
    val ic_task_queue: Int get() = R.drawable.ic_task_queue
    @get:DrawableRes
    val ic_handoff: Int get() = R.drawable.ic_handoff
    @get:DrawableRes
    val ic_function_call: Int get() = R.drawable.ic_function_call
    @get:DrawableRes
    val ic_rag: Int get() = R.drawable.ic_rag
    @get:DrawableRes
    val ic_knowledge_graph: Int get() = R.drawable.ic_knowledge_graph
    @get:DrawableRes
    val ic_vector_search: Int get() = R.drawable.ic_vector_search
    @get:DrawableRes
    val ic_context_window: Int get() = R.drawable.ic_context_window
    @get:DrawableRes
    val ic_system_prompt: Int get() = R.drawable.ic_system_prompt
    @get:DrawableRes
    val ic_chain_of_thought: Int get() = R.drawable.ic_chain_of_thought
    @get:DrawableRes
    val ic_self_reflection: Int get() = R.drawable.ic_self_reflection
    @get:DrawableRes
    val ic_few_shot: Int get() = R.drawable.ic_few_shot
    @get:DrawableRes
    val ic_guardrail: Int get() = R.drawable.ic_guardrail
    @get:DrawableRes
    val ic_moderation: Int get() = R.drawable.ic_moderation
    @get:DrawableRes
    val ic_eval_dataset: Int get() = R.drawable.ic_eval_dataset
    @get:DrawableRes
    val ic_benchmark_run: Int get() = R.drawable.ic_benchmark_run
    @get:DrawableRes
    val ic_trace: Int get() = R.drawable.ic_trace
    @get:DrawableRes
    val ic_span: Int get() = R.drawable.ic_span
    @get:DrawableRes
    val ic_model_router: Int get() = R.drawable.ic_model_router
    @get:DrawableRes
    val ic_fallback_model: Int get() = R.drawable.ic_fallback_model
    @get:DrawableRes
    val ic_ab_test: Int get() = R.drawable.ic_ab_test
    @get:DrawableRes
    val ic_agent_memory: Int get() = R.drawable.ic_agent_memory
    @get:DrawableRes
    val ic_long_term_memory: Int get() = R.drawable.ic_long_term_memory
    @get:DrawableRes
    val ic_short_term_memory: Int get() = R.drawable.ic_short_term_memory
    @get:DrawableRes
    val ic_summarize: Int get() = R.drawable.ic_summarize
    @get:DrawableRes
    val ic_rewrite: Int get() = R.drawable.ic_rewrite
    @get:DrawableRes
    val ic_extract: Int get() = R.drawable.ic_extract
    @get:DrawableRes
    val ic_classify: Int get() = R.drawable.ic_classify
    @get:DrawableRes
    val ic_rank: Int get() = R.drawable.ic_rank
    @get:DrawableRes
    val ic_grounding: Int get() = R.drawable.ic_grounding
    @get:DrawableRes
    val ic_citation: Int get() = R.drawable.ic_citation
    @get:DrawableRes
    val ic_hallucination_guard: Int get() = R.drawable.ic_hallucination_guard
    @get:DrawableRes
    val ic_cost_control: Int get() = R.drawable.ic_cost_control
    @get:DrawableRes
    val ic_rate_limit: Int get() = R.drawable.ic_rate_limit
    @get:DrawableRes
    val ic_quota: Int get() = R.drawable.ic_quota
    @get:DrawableRes
    val ic_agent_handoff_human: Int get() = R.drawable.ic_agent_handoff_human
    @get:DrawableRes
    val ic_multi_agent: Int get() = R.drawable.ic_multi_agent
    @get:DrawableRes
    val ic_agent_role: Int get() = R.drawable.ic_agent_role
    @get:DrawableRes
    val ic_agent_goal: Int get() = R.drawable.ic_agent_goal
    @get:DrawableRes
    val ic_agent_plan: Int get() = R.drawable.ic_agent_plan
    @get:DrawableRes
    val ic_agent_reflect: Int get() = R.drawable.ic_agent_reflect
    @get:DrawableRes
    val ic_agent_stop: Int get() = R.drawable.ic_agent_stop
    @get:DrawableRes
    val ic_search: Int get() = R.drawable.ic_search
    @get:DrawableRes
    val ic_web_search: Int get() = R.drawable.ic_web_search
    @get:DrawableRes
    val ic_globe: Int get() = R.drawable.ic_globe
    @get:DrawableRes
    val ic_news: Int get() = R.drawable.ic_news
    @get:DrawableRes
    val ic_book: Int get() = R.drawable.ic_book
    @get:DrawableRes
    val ic_translate: Int get() = R.drawable.ic_translate
    @get:DrawableRes
    val ic_calculator: Int get() = R.drawable.ic_calculator
    @get:DrawableRes
    val ic_unit_converter: Int get() = R.drawable.ic_unit_converter
    @get:DrawableRes
    val ic_currency: Int get() = R.drawable.ic_currency
    @get:DrawableRes
    val ic_weather: Int get() = R.drawable.ic_weather
    @get:DrawableRes
    val ic_stock_market: Int get() = R.drawable.ic_stock_market
    @get:DrawableRes
    val ic_qr_scanner: Int get() = R.drawable.ic_qr_scanner
    @get:DrawableRes
    val ic_link: Int get() = R.drawable.ic_link
    @get:DrawableRes
    val ic_code: Int get() = R.drawable.ic_code
    @get:DrawableRes
    val ic_code_block: Int get() = R.drawable.ic_code_block
    @get:DrawableRes
    val ic_terminal: Int get() = R.drawable.ic_terminal
    @get:DrawableRes
    val ic_console: Int get() = R.drawable.ic_console
    @get:DrawableRes
    val ic_database: Int get() = R.drawable.ic_database
    @get:DrawableRes
    val ic_docker: Int get() = R.drawable.ic_docker
    @get:DrawableRes
    val ic_server: Int get() = R.drawable.ic_server
    @get:DrawableRes
    val ic_git_branch: Int get() = R.drawable.ic_git_branch
    @get:DrawableRes
    val ic_git_commit: Int get() = R.drawable.ic_git_commit
    @get:DrawableRes
    val ic_git_merge: Int get() = R.drawable.ic_git_merge
    @get:DrawableRes
    val ic_bug: Int get() = R.drawable.ic_bug
    @get:DrawableRes
    val ic_linter: Int get() = R.drawable.ic_linter
    @get:DrawableRes
    val ic_package: Int get() = R.drawable.ic_package
    @get:DrawableRes
    val ic_http_request: Int get() = R.drawable.ic_http_request
    @get:DrawableRes
    val ic_deploy: Int get() = R.drawable.ic_deploy
    @get:DrawableRes
    val ic_regex: Int get() = R.drawable.ic_regex
    @get:DrawableRes
    val ic_archive: Int get() = R.drawable.ic_archive
    @get:DrawableRes
    val ic_file_code: Int get() = R.drawable.ic_file_code
    @get:DrawableRes
    val ic_file: Int get() = R.drawable.ic_file
    @get:DrawableRes
    val ic_file_text: Int get() = R.drawable.ic_file_text
    @get:DrawableRes
    val ic_file_pdf: Int get() = R.drawable.ic_file_pdf
    @get:DrawableRes
    val ic_file_image: Int get() = R.drawable.ic_file_image
    @get:DrawableRes
    val ic_file_zip: Int get() = R.drawable.ic_file_zip
    @get:DrawableRes
    val ic_file_audio: Int get() = R.drawable.ic_file_audio
    @get:DrawableRes
    val ic_file_video: Int get() = R.drawable.ic_file_video
    @get:DrawableRes
    val ic_file_sheet: Int get() = R.drawable.ic_file_sheet
    @get:DrawableRes
    val ic_folder: Int get() = R.drawable.ic_folder
    @get:DrawableRes
    val ic_folder_add: Int get() = R.drawable.ic_folder_add
    @get:DrawableRes
    val ic_upload_cloud: Int get() = R.drawable.ic_upload_cloud
    @get:DrawableRes
    val ic_download_cloud: Int get() = R.drawable.ic_download_cloud
    @get:DrawableRes
    val ic_attach: Int get() = R.drawable.ic_attach
    @get:DrawableRes
    val ic_table: Int get() = R.drawable.ic_table
    @get:DrawableRes
    val ic_spreadsheet: Int get() = R.drawable.ic_spreadsheet
    @get:DrawableRes
    val ic_export: Int get() = R.drawable.ic_export
    @get:DrawableRes
    val ic_import: Int get() = R.drawable.ic_import
    @get:DrawableRes
    val ic_browser: Int get() = R.drawable.ic_browser
    @get:DrawableRes
    val ic_screenshot: Int get() = R.drawable.ic_screenshot
    @get:DrawableRes
    val ic_scrape: Int get() = R.drawable.ic_scrape
    @get:DrawableRes
    val ic_rss: Int get() = R.drawable.ic_rss
    @get:DrawableRes
    val ic_barcode: Int get() = R.drawable.ic_barcode
    @get:DrawableRes
    val ic_map_pin: Int get() = R.drawable.ic_map_pin
    @get:DrawableRes
    val ic_navigation: Int get() = R.drawable.ic_navigation
    @get:DrawableRes
    val ic_compass: Int get() = R.drawable.ic_compass
    @get:DrawableRes
    val ic_calendar: Int get() = R.drawable.ic_calendar
    @get:DrawableRes
    val ic_email: Int get() = R.drawable.ic_email
    @get:DrawableRes
    val ic_notes: Int get() = R.drawable.ic_notes
    @get:DrawableRes
    val ic_todo: Int get() = R.drawable.ic_todo
    @get:DrawableRes
    val ic_reminder: Int get() = R.drawable.ic_reminder
    @get:DrawableRes
    val ic_contact: Int get() = R.drawable.ic_contact
    @get:DrawableRes
    val ic_meeting: Int get() = R.drawable.ic_meeting
    @get:DrawableRes
    val ic_presentation: Int get() = R.drawable.ic_presentation
    @get:DrawableRes
    val ic_kanban: Int get() = R.drawable.ic_kanban
    @get:DrawableRes
    val ic_booking: Int get() = R.drawable.ic_booking
    @get:DrawableRes
    val ic_timer: Int get() = R.drawable.ic_timer
    @get:DrawableRes
    val ic_clock: Int get() = R.drawable.ic_clock
    
    /** 手绘补充的沙漏（原图标集里没有）；等待类工具用。 */
    @get:DrawableRes
    val ic_hourglass: Int get() = R.drawable.ic_hourglass
    @get:DrawableRes
    val ic_scholar: Int get() = R.drawable.ic_scholar
    @get:DrawableRes
    val ic_receipt: Int get() = R.drawable.ic_receipt
    @get:DrawableRes
    val ic_signature: Int get() = R.drawable.ic_signature
    @get:DrawableRes
    val ic_image: Int get() = R.drawable.ic_image
    @get:DrawableRes
    val ic_image_gen: Int get() = R.drawable.ic_image_gen
    @get:DrawableRes
    val ic_ocr: Int get() = R.drawable.ic_ocr
    @get:DrawableRes
    val ic_camera: Int get() = R.drawable.ic_camera
    @get:DrawableRes
    val ic_qr_code: Int get() = R.drawable.ic_qr_code
    @get:DrawableRes
    val ic_text_to_speech: Int get() = R.drawable.ic_text_to_speech
    @get:DrawableRes
    val ic_music: Int get() = R.drawable.ic_music
    @get:DrawableRes
    val ic_video: Int get() = R.drawable.ic_video
    @get:DrawableRes
    val ic_play_circle: Int get() = R.drawable.ic_play_circle
    @get:DrawableRes
    val ic_palette: Int get() = R.drawable.ic_palette
    @get:DrawableRes
    val ic_svc_chat_app: Int get() = R.drawable.ic_svc_chat_app
    @get:DrawableRes
    val ic_svc_email_app: Int get() = R.drawable.ic_svc_email_app
    @get:DrawableRes
    val ic_svc_cloud_drive: Int get() = R.drawable.ic_svc_cloud_drive
    @get:DrawableRes
    val ic_svc_design_tool: Int get() = R.drawable.ic_svc_design_tool
    @get:DrawableRes
    val ic_svc_project_board: Int get() = R.drawable.ic_svc_project_board
    @get:DrawableRes
    val ic_svc_ticket: Int get() = R.drawable.ic_svc_ticket
    @get:DrawableRes
    val ic_svc_crm: Int get() = R.drawable.ic_svc_crm
    @get:DrawableRes
    val ic_svc_payment: Int get() = R.drawable.ic_svc_payment
    @get:DrawableRes
    val ic_svc_analytics: Int get() = R.drawable.ic_svc_analytics
    @get:DrawableRes
    val ic_svc_automation: Int get() = R.drawable.ic_svc_automation
    @get:DrawableRes
    val ic_svc_support: Int get() = R.drawable.ic_svc_support
    @get:DrawableRes
    val ic_svc_calendar_app: Int get() = R.drawable.ic_svc_calendar_app
    @get:DrawableRes
    val ic_svc_note_app: Int get() = R.drawable.ic_svc_note_app
    @get:DrawableRes
    val ic_svc_code_repo: Int get() = R.drawable.ic_svc_code_repo
    @get:DrawableRes
    val ic_svc_pipeline_app: Int get() = R.drawable.ic_svc_pipeline_app
    @get:DrawableRes
    val ic_svc_api_gateway: Int get() = R.drawable.ic_svc_api_gateway
    @get:DrawableRes
    val ic_svc_queue: Int get() = R.drawable.ic_svc_queue
    @get:DrawableRes
    val ic_svc_cache: Int get() = R.drawable.ic_svc_cache
    @get:DrawableRes
    val ic_svc_search_index: Int get() = R.drawable.ic_svc_search_index
    @get:DrawableRes
    val ic_svc_notification_hub: Int get() = R.drawable.ic_svc_notification_hub
    @get:DrawableRes
    val ic_svc_database_sql: Int get() = R.drawable.ic_svc_database_sql
    @get:DrawableRes
    val ic_svc_vector_db: Int get() = R.drawable.ic_svc_vector_db
    @get:DrawableRes
    val ic_svc_storage_bucket: Int get() = R.drawable.ic_svc_storage_bucket
    @get:DrawableRes
    val ic_svc_cdn: Int get() = R.drawable.ic_svc_cdn
    @get:DrawableRes
    val ic_svc_dns: Int get() = R.drawable.ic_svc_dns
    @get:DrawableRes
    val ic_svc_load_balancer: Int get() = R.drawable.ic_svc_load_balancer
    @get:DrawableRes
    val ic_svc_message_queue: Int get() = R.drawable.ic_svc_message_queue
    @get:DrawableRes
    val ic_svc_stream: Int get() = R.drawable.ic_svc_stream
    @get:DrawableRes
    val ic_svc_social: Int get() = R.drawable.ic_svc_social
    @get:DrawableRes
    val ic_svc_video_app: Int get() = R.drawable.ic_svc_video_app
    @get:DrawableRes
    val ic_svc_music_app: Int get() = R.drawable.ic_svc_music_app
    @get:DrawableRes
    val ic_svc_file_sync: Int get() = R.drawable.ic_svc_file_sync
    @get:DrawableRes
    val ic_svc_identity: Int get() = R.drawable.ic_svc_identity
    @get:DrawableRes
    val ic_svc_webhook_inbox: Int get() = R.drawable.ic_svc_webhook_inbox
    @get:DrawableRes
    val ic_svc_sandbox: Int get() = R.drawable.ic_svc_sandbox
    @get:DrawableRes
    val ic_svc_logs: Int get() = R.drawable.ic_svc_logs
    @get:DrawableRes
    val ic_svc_monitor_alert: Int get() = R.drawable.ic_svc_monitor_alert
    @get:DrawableRes
    val ic_svc_backup_restore: Int get() = R.drawable.ic_svc_backup_restore
    @get:DrawableRes
    val ic_svc_key_vault: Int get() = R.drawable.ic_svc_key_vault
    @get:DrawableRes
    val ic_svc_secret: Int get() = R.drawable.ic_svc_secret
    @get:DrawableRes
    val ic_svc_feature_flag: Int get() = R.drawable.ic_svc_feature_flag
    @get:DrawableRes
    val ic_svc_experiment: Int get() = R.drawable.ic_svc_experiment
    @get:DrawableRes
    val ic_chat: Int get() = R.drawable.ic_chat
    @get:DrawableRes
    val ic_comment: Int get() = R.drawable.ic_comment
    @get:DrawableRes
    val ic_send: Int get() = R.drawable.ic_send
    @get:DrawableRes
    val ic_mic: Int get() = R.drawable.ic_mic
    @get:DrawableRes
    val ic_mic_off: Int get() = R.drawable.ic_mic_off
    @get:DrawableRes
    val ic_keyboard: Int get() = R.drawable.ic_keyboard
    @get:DrawableRes
    val ic_emoji: Int get() = R.drawable.ic_emoji
    @get:DrawableRes
    val ic_voice_wave: Int get() = R.drawable.ic_voice_wave
    @get:DrawableRes
    val ic_hashtag: Int get() = R.drawable.ic_hashtag
    @get:DrawableRes
    val ic_sticker: Int get() = R.drawable.ic_sticker
    @get:DrawableRes
    val ic_subtitles: Int get() = R.drawable.ic_subtitles
    @get:DrawableRes
    val ic_home: Int get() = R.drawable.ic_home
    @get:DrawableRes
    val ic_menu: Int get() = R.drawable.ic_menu
    @get:DrawableRes
    val ic_close: Int get() = R.drawable.ic_close
    @get:DrawableRes
    val ic_plus: Int get() = R.drawable.ic_plus
    @get:DrawableRes
    val ic_minus: Int get() = R.drawable.ic_minus
    @get:DrawableRes
    val ic_check: Int get() = R.drawable.ic_check
    @get:DrawableRes
    val ic_chevron_right: Int get() = R.drawable.ic_chevron_right
    @get:DrawableRes
    val ic_chevron_left: Int get() = R.drawable.ic_chevron_left
    @get:DrawableRes
    val ic_chevron_up: Int get() = R.drawable.ic_chevron_up
    @get:DrawableRes
    val ic_chevron_down: Int get() = R.drawable.ic_chevron_down
    @get:DrawableRes
    val ic_arrow_right: Int get() = R.drawable.ic_arrow_right
    @get:DrawableRes
    val ic_arrow_left: Int get() = R.drawable.ic_arrow_left
    @get:DrawableRes
    val ic_arrow_up: Int get() = R.drawable.ic_arrow_up
    @get:DrawableRes
    val ic_arrow_down: Int get() = R.drawable.ic_arrow_down
    @get:DrawableRes
    val ic_more_horiz: Int get() = R.drawable.ic_more_horiz
    @get:DrawableRes
    val ic_more_vert: Int get() = R.drawable.ic_more_vert
    @get:DrawableRes
    val ic_refresh: Int get() = R.drawable.ic_refresh
    @get:DrawableRes
    val ic_share: Int get() = R.drawable.ic_share
    @get:DrawableRes
    val ic_edit: Int get() = R.drawable.ic_edit
    @get:DrawableRes
    val ic_trash: Int get() = R.drawable.ic_trash
    @get:DrawableRes
    val ic_copy: Int get() = R.drawable.ic_copy
    @get:DrawableRes
    val ic_download: Int get() = R.drawable.ic_download
    @get:DrawableRes
    val ic_upload: Int get() = R.drawable.ic_upload
    @get:DrawableRes
    val ic_filter: Int get() = R.drawable.ic_filter
    @get:DrawableRes
    val ic_sort: Int get() = R.drawable.ic_sort
    @get:DrawableRes
    val ic_bookmark: Int get() = R.drawable.ic_bookmark
    @get:DrawableRes
    val ic_star: Int get() = R.drawable.ic_star
    @get:DrawableRes
    val ic_heart: Int get() = R.drawable.ic_heart
    @get:DrawableRes
    val ic_pin: Int get() = R.drawable.ic_pin
    @get:DrawableRes
    val ic_fullscreen: Int get() = R.drawable.ic_fullscreen
    @get:DrawableRes
    val ic_drag_handle: Int get() = R.drawable.ic_drag_handle
    @get:DrawableRes
    val ic_bottom_nav: Int get() = R.drawable.ic_bottom_nav
    @get:DrawableRes
    val ic_tabs: Int get() = R.drawable.ic_tabs
    @get:DrawableRes
    val ic_view_sidebar: Int get() = R.drawable.ic_view_sidebar
    @get:DrawableRes
    val ic_split_view: Int get() = R.drawable.ic_split_view
    @get:DrawableRes
    val ic_pagination: Int get() = R.drawable.ic_pagination
    @get:DrawableRes
    val ic_floating_action: Int get() = R.drawable.ic_floating_action
    @get:DrawableRes
    val ic_bottom_sheet: Int get() = R.drawable.ic_bottom_sheet
    @get:DrawableRes
    val ic_empty_state: Int get() = R.drawable.ic_empty_state
    @get:DrawableRes
    val ic_skeleton: Int get() = R.drawable.ic_skeleton
    @get:DrawableRes
    val ic_app_shortcut: Int get() = R.drawable.ic_app_shortcut
    @get:DrawableRes
    val ic_settings: Int get() = R.drawable.ic_settings
    @get:DrawableRes
    val ic_settings_slider: Int get() = R.drawable.ic_settings_slider
    @get:DrawableRes
    val ic_theme_auto: Int get() = R.drawable.ic_theme_auto
    @get:DrawableRes
    val ic_theme_light: Int get() = R.drawable.ic_theme_light
    @get:DrawableRes
    val ic_theme_dark: Int get() = R.drawable.ic_theme_dark
    @get:DrawableRes
    val ic_theme_switch: Int get() = R.drawable.ic_theme_switch
    @get:DrawableRes
    val ic_font_size: Int get() = R.drawable.ic_font_size
    @get:DrawableRes
    val ic_notifications: Int get() = R.drawable.ic_notifications
    @get:DrawableRes
    val ic_notifications_off: Int get() = R.drawable.ic_notifications_off
    @get:DrawableRes
    val ic_vibrate: Int get() = R.drawable.ic_vibrate
    @get:DrawableRes
    val ic_update: Int get() = R.drawable.ic_update
    @get:DrawableRes
    val ic_clean: Int get() = R.drawable.ic_clean
    @get:DrawableRes
    val ic_wallpaper: Int get() = R.drawable.ic_wallpaper
    @get:DrawableRes
    val ic_dynamic_color: Int get() = R.drawable.ic_dynamic_color
    @get:DrawableRes
    val ic_user: Int get() = R.drawable.ic_user
    @get:DrawableRes
    val ic_user_add: Int get() = R.drawable.ic_user_add
    @get:DrawableRes
    val ic_user_group: Int get() = R.drawable.ic_user_group
    @get:DrawableRes
    val ic_verified: Int get() = R.drawable.ic_verified
    @get:DrawableRes
    val ic_credit_card: Int get() = R.drawable.ic_credit_card
    @get:DrawableRes
    val ic_wallet: Int get() = R.drawable.ic_wallet
    @get:DrawableRes
    val ic_gift: Int get() = R.drawable.ic_gift
    @get:DrawableRes
    val ic_membership: Int get() = R.drawable.ic_membership
    @get:DrawableRes
    val ic_coin: Int get() = R.drawable.ic_coin
    @get:DrawableRes
    val ic_success: Int get() = R.drawable.ic_success
    @get:DrawableRes
    val ic_error: Int get() = R.drawable.ic_error
    @get:DrawableRes
    val ic_warning: Int get() = R.drawable.ic_warning
    @get:DrawableRes
    val ic_info: Int get() = R.drawable.ic_info
    @get:DrawableRes
    val ic_help: Int get() = R.drawable.ic_help
    @get:DrawableRes
    val ic_loading: Int get() = R.drawable.ic_loading
    @get:DrawableRes
    val ic_loading_dots: Int get() = R.drawable.ic_loading_dots
    @get:DrawableRes
    val ic_offline: Int get() = R.drawable.ic_offline
    @get:DrawableRes
    val ic_sync: Int get() = R.drawable.ic_sync
    @get:DrawableRes
    val ic_blocked: Int get() = R.drawable.ic_blocked
    @get:DrawableRes
    val ic_new_releases: Int get() = R.drawable.ic_new_releases
    @get:DrawableRes
    val ic_badge_count: Int get() = R.drawable.ic_badge_count
    @get:DrawableRes
    val ic_update_available: Int get() = R.drawable.ic_update_available
    @get:DrawableRes
    val ic_wifi: Int get() = R.drawable.ic_wifi
    @get:DrawableRes
    val ic_wifi_off: Int get() = R.drawable.ic_wifi_off
    @get:DrawableRes
    val ic_signal: Int get() = R.drawable.ic_signal
    @get:DrawableRes
    val ic_signal_off: Int get() = R.drawable.ic_signal_off
    @get:DrawableRes
    val ic_battery: Int get() = R.drawable.ic_battery
    @get:DrawableRes
    val ic_battery_charge: Int get() = R.drawable.ic_battery_charge
    @get:DrawableRes
    val ic_phone_device: Int get() = R.drawable.ic_phone_device
    @get:DrawableRes
    val ic_cpu: Int get() = R.drawable.ic_cpu
    @get:DrawableRes
    val ic_memory: Int get() = R.drawable.ic_memory
    @get:DrawableRes
    val ic_monitor: Int get() = R.drawable.ic_monitor
    @get:DrawableRes
    val ic_offline_cloud: Int get() = R.drawable.ic_offline_cloud
    @get:DrawableRes
    val ic_cloud: Int get() = R.drawable.ic_cloud
    @get:DrawableRes
    val ic_speed: Int get() = R.drawable.ic_speed
    @get:DrawableRes
    val ic_bandwidth: Int get() = R.drawable.ic_bandwidth
    @get:DrawableRes
    val ic_lock: Int get() = R.drawable.ic_lock
    @get:DrawableRes
    val ic_unlock: Int get() = R.drawable.ic_unlock
    @get:DrawableRes
    val ic_key: Int get() = R.drawable.ic_key
    @get:DrawableRes
    val ic_shield: Int get() = R.drawable.ic_shield
    @get:DrawableRes
    val ic_shield_check: Int get() = R.drawable.ic_shield_check
    @get:DrawableRes
    val ic_shield_alert: Int get() = R.drawable.ic_shield_alert
    @get:DrawableRes
    val ic_fingerprint: Int get() = R.drawable.ic_fingerprint
    @get:DrawableRes
    val ic_face_id: Int get() = R.drawable.ic_face_id
    @get:DrawableRes
    val ic_eye: Int get() = R.drawable.ic_eye
    @get:DrawableRes
    val ic_eye_off: Int get() = R.drawable.ic_eye_off
    @get:DrawableRes
    val ic_incognito: Int get() = R.drawable.ic_incognito
    @get:DrawableRes
    val ic_password: Int get() = R.drawable.ic_password
    @get:DrawableRes
    val ic_chart_bar: Int get() = R.drawable.ic_chart_bar
    @get:DrawableRes
    val ic_chart_line: Int get() = R.drawable.ic_chart_line
    @get:DrawableRes
    val ic_chart_pie: Int get() = R.drawable.ic_chart_pie
    @get:DrawableRes
    val ic_chart_donut: Int get() = R.drawable.ic_chart_donut
    @get:DrawableRes
    val ic_chart_area: Int get() = R.drawable.ic_chart_area
    @get:DrawableRes
    val ic_chart_trend_up: Int get() = R.drawable.ic_chart_trend_up
    @get:DrawableRes
    val ic_chart_trend_down: Int get() = R.drawable.ic_chart_trend_down
    @get:DrawableRes
    val ic_statistics: Int get() = R.drawable.ic_statistics
    @get:DrawableRes
    val ic_analysis: Int get() = R.drawable.ic_analysis
    @get:DrawableRes
    val ic_search_bar: Int get() = R.drawable.ic_search_bar
    @get:DrawableRes
    val ic_chip: Int get() = R.drawable.ic_chip
    @get:DrawableRes
    val ic_dialog: Int get() = R.drawable.ic_dialog
    @get:DrawableRes
    val ic_snackbar: Int get() = R.drawable.ic_snackbar
    @get:DrawableRes
    val ic_tooltip: Int get() = R.drawable.ic_tooltip
    @get:DrawableRes
    val ic_stepper_bar: Int get() = R.drawable.ic_stepper_bar
    @get:DrawableRes
    val ic_carousel: Int get() = R.drawable.ic_carousel
    @get:DrawableRes
    val ic_pull_refresh: Int get() = R.drawable.ic_pull_refresh
    @get:DrawableRes
    val ic_swipe: Int get() = R.drawable.ic_swipe
    @get:DrawableRes
    val ic_avatar: Int get() = R.drawable.ic_avatar
    @get:DrawableRes
    val ic_avatar_group: Int get() = R.drawable.ic_avatar_group
    @get:DrawableRes
    val ic_divider: Int get() = R.drawable.ic_divider
    @get:DrawableRes
    val ic_app_bar: Int get() = R.drawable.ic_app_bar
    @get:DrawableRes
    val ic_collapse: Int get() = R.drawable.ic_collapse
    @get:DrawableRes
    val ic_expand_panel: Int get() = R.drawable.ic_expand_panel
    @get:DrawableRes
    val ic_swipe_action: Int get() = R.drawable.ic_swipe_action
    @get:DrawableRes
    val ic_pause: Int get() = R.drawable.ic_pause
    @get:DrawableRes
    val ic_stop: Int get() = R.drawable.ic_stop
    @get:DrawableRes
    val ic_record: Int get() = R.drawable.ic_record
    @get:DrawableRes
    val ic_skip_next: Int get() = R.drawable.ic_skip_next
    @get:DrawableRes
    val ic_skip_prev: Int get() = R.drawable.ic_skip_prev
    @get:DrawableRes
    val ic_forward_10: Int get() = R.drawable.ic_forward_10
    @get:DrawableRes
    val ic_replay_10: Int get() = R.drawable.ic_replay_10
    @get:DrawableRes
    val ic_volume: Int get() = R.drawable.ic_volume
    @get:DrawableRes
    val ic_volume_mute: Int get() = R.drawable.ic_volume_mute
    @get:DrawableRes
    val ic_equalizer: Int get() = R.drawable.ic_equalizer
    @get:DrawableRes
    val ic_waveform: Int get() = R.drawable.ic_waveform
    @get:DrawableRes
    val ic_transcript: Int get() = R.drawable.ic_transcript
    @get:DrawableRes
    val ic_pending: Int get() = R.drawable.ic_pending
    @get:DrawableRes
    val ic_running: Int get() = R.drawable.ic_running
    @get:DrawableRes
    val ic_queued: Int get() = R.drawable.ic_queued
    @get:DrawableRes
    val ic_retry: Int get() = R.drawable.ic_retry
    @get:DrawableRes
    val ic_timeout: Int get() = R.drawable.ic_timeout
    @get:DrawableRes
    val ic_paused_state: Int get() = R.drawable.ic_paused_state
    @get:DrawableRes
    val ic_completed: Int get() = R.drawable.ic_completed
    @get:DrawableRes
    val ic_cancelled: Int get() = R.drawable.ic_cancelled
    @get:DrawableRes
    val ic_partial: Int get() = R.drawable.ic_partial
    @get:DrawableRes
    val ic_progress_bar: Int get() = R.drawable.ic_progress_bar
    @get:DrawableRes
    val ic_progress_ring: Int get() = R.drawable.ic_progress_ring
    @get:DrawableRes
    val ic_add_row: Int get() = R.drawable.ic_add_row
    @get:DrawableRes
    val ic_merge_cells: Int get() = R.drawable.ic_merge_cells
    @get:DrawableRes
    val ic_group_by: Int get() = R.drawable.ic_group_by
    @get:DrawableRes
    val ic_pivot: Int get() = R.drawable.ic_pivot
    @get:DrawableRes
    val ic_diff: Int get() = R.drawable.ic_diff
    @get:DrawableRes
    val ic_json_data: Int get() = R.drawable.ic_json_data
    @get:DrawableRes
    val ic_schema: Int get() = R.drawable.ic_schema
    @get:DrawableRes
    val ic_field: Int get() = R.drawable.ic_field
    @get:DrawableRes
    val ic_snapshot: Int get() = R.drawable.ic_snapshot
    @get:DrawableRes
    val ic_rollback: Int get() = R.drawable.ic_rollback
}
