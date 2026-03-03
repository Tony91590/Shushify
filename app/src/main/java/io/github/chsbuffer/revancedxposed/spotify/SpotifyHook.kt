package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.UnlockPremium
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets
import app.revanced.extension.shared.Logger

@Suppress("UNCHECKED_CAST")
class SpotifyHook(app: Application, lpparam: LoadPackageParam) : BaseHook(app, lpparam) {
    override val hooks = arrayOf(
        ::Extension,
        ::SanitizeSharingLinks,
        ::UnlockPremium,
        ::FixThirdPartyLaunchersWidgets,
        ::AdSkipper
    )

    companion object {
        private var originalVolume = -1
        private var isMuted = false
    }

    /**
     * AdSkipper — Detects Spotify ads via MediaSession.setMetadata.
     *
     * Every Spotify ad has a media ID containing ":ad:" (e.g. "spotify:ad:XXXX").
     * On detection: instantly mutes the system music stream.
     * When real music resumes: restores the previous volume.
     */
    fun AdSkipper() {
        val ctx: Context = app
        runCatching {
            XposedHelpers.findAndHookMethod(
                MediaSession::class.java.name,
                classLoader,
                "setMetadata",
                MediaMetadata::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val metadata = param.args[0] as? MediaMetadata ?: return
                            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
                            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                            if (mediaId.contains(":ad:")) {
                                val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                if (!isMuted && vol > 0) {
                                    Logger.printDebug { "AdSkipper: Ad detected → muting (ID=$mediaId)" }
                                    originalVolume = vol
                                    isMuted = true
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                                }
                            } else if (isMuted && originalVolume != -1) {
                                Logger.printDebug { "AdSkipper: Music resumed → volume restored ($originalVolume)" }
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                                isMuted = false
                                originalVolume = -1
                            }
                        } catch (e: Throwable) {
                            Logger.printDebug { "AdSkipper: Error → ${e.message}" }
                        }
                    }
                }
            )
        }.onFailure { Logger.printDebug { "AdSkipper hook failed: ${it.message}" } }
    }

    fun Extension() {
        // load stubbed spotify classes
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }
}
