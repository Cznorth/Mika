package com.wangzan101.mika.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.util.Log
import android.util.Base64
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.core.model.Capabilities
import java.io.File

/** Debug-only root/shell trigger for the on-device display backend smoke test. */
class RootProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uid = Binder.getCallingUid()
        Log.i(TAG, "receive action=${intent.action} uid=$uid")
        val goal = intent.getStringExtra(EXTRA_GOAL_B64)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8) }.getOrNull()
        }?.trim()?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() }
            ?: "Open Settings, capture the virtual display, and tap once"
        if (intent.action == ACTION_IMPORT_PROVIDER) {
            val f = File("/data/local/tmp/mika-provider-import.txt")
            val rows = runCatching { f.readLines() }.getOrElse { emptyList() }
            runCatching { f.delete() }
            val base = rows.getOrNull(0)?.trim().orEmpty()
            val model = rows.getOrNull(1)?.trim().orEmpty()
            val key = rows.getOrNull(2).orEmpty()
            if (base.isNotEmpty() && model.isNotEmpty() && key.isNotEmpty()) {
                val store = ProviderStore.get(context)
                // 和在界面上选预设一样:按地址认出服务商(OpenCode Go 要带 x-opencode-session 会话头)
                val preset = com.wangzan101.mika.settings.ProviderPresets.forBaseUrl(base)
                val provider = store.connect(preset?.label ?: "debug-import", ProviderConfig.Kind.OpenAiCompat, base, key,
                    emptyList(), verified = setOf(model), typed = setOf(model))
                val id = "debug-import-$model"
                store.save(ProviderConfig(id, ProviderConfig.Kind.OpenAiCompat, base, model,
                    Capabilities(toolCalls = true, vision = true, contextTokens = 128000, reasoning = false),
                    name = model, providerId = provider.id), null)
                store.choose(ModelRole.Main, RoleChoice.Model(id))
                Log.i(TAG, "provider import complete")
            }
            return
        }
        TaskService.start(context, goal)
    }
    companion object {
        private const val TAG = "MikaRootProbe"; const val EXTRA_GOAL = "goal"
        const val ACTION_IMPORT_PROVIDER = "com.wangzan101.mika.IMPORT_PROVIDER"
        const val ACTION_ROOT_PROBE = "com.wangzan101.mika.ROOT_PROBE"
        const val EXTRA_GOAL_B64 = "goal_b64"
        const val EXTRA_BASE_URL = "base_url"; const val EXTRA_MODEL = "model"; const val EXTRA_KEY = "key"
    }
}
