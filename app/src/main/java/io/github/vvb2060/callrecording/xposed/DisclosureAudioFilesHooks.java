package io.github.vvb2060.callrecording.xposed;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Compatibility hook for the modern Google Phone call-recording disclosure audio-file path.
 *
 * <p>Google Phone 240 materializes built-in recording disclosure audio through
 * CallRecordingDisclosureAudioFiles before handing the resulting File to the player. The hook
 * locates that path by its stable Kotlin source-file string and substitutes a valid silent WAV
 * only after Google has completed its normal file-creation/state work.</p>
 */
final class DisclosureAudioFilesHooks {
    private static final String TAG = "CallRecording";
    private static final String DIALER_PACKAGE = "com.google.android.dialer";
    private static final String AUDIO_FILES_ANCHOR = "CallRecordingDisclosureAudioFiles.kt";
    private static final String BEEP_ENABLED_ANCHOR = "BeepSoundCallRecordingDisclosureEnabledFn.kt";

    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static final AtomicBoolean beepStateLogged = new AtomicBoolean(false);
    private static volatile File silentDisclosureFile;

    private DisclosureAudioFilesHooks() {
    }

    static void install(String packageName, String processName, ClassLoader classLoader,
            String entrypoint) {
        if (!DIALER_PACKAGE.equals(packageName)) return;
        if (!packageName.equals(processName)) return;
        if (!installed.compareAndSet(false, true)) {
            Log.d(TAG, "DisclosureAudioFiles: already installed; entry=" + entrypoint);
            return;
        }

        try (DexHelper dex = new DexHelper(classLoader)) {
            int audioHooks = hookDisclosureAudioFileFactory(dex);
            int beepHooks = hookBeepAvailabilityObserver(dex);
            Log.w(TAG, "DisclosureAudioFiles: entry=" + entrypoint
                    + " audioHooks=" + audioHooks + " beepObservers=" + beepHooks);
        } catch (Throwable t) {
            installed.set(false);
            Log.e(TAG, "DisclosureAudioFiles: installation failed", t);
        }
    }

    private static int hookDisclosureAudioFileFactory(DexHelper dex) {
        long[] candidates = dex.findMethodUsingString(
                AUDIO_FILES_ANCHOR,
                false,
                -1,
                (short) -1,
                null,
                -1,
                null,
                null,
                null,
                false);
        int hooked = 0;
        for (long candidate : candidates) {
            Member member = dex.decodeMethodIndex(candidate);
            if (!(member instanceof Method)) continue;
            Method method = (Method) member;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    Object result = param.getResult();
                    if (!(result instanceof File)) return;
                    File original = (File) result;
                    File silent = getSilentDisclosureFile(original);
                    if (silent == null) return;
                    param.setResult(silent);
                    Log.w(TAG, "DisclosureAudioFiles: substituted "
                            + original.getName() + " -> " + silent.getName());
                }
            });
            hooked++;
            Log.w(TAG, "DisclosureAudioFiles: hooked "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
        }
        if (hooked == 0) {
            Log.w(TAG, "DisclosureAudioFiles: no method found for anchor=" + AUDIO_FILES_ANCHOR);
        }
        return hooked;
    }

    private static int hookBeepAvailabilityObserver(DexHelper dex) {
        long[] candidates = dex.findMethodUsingString(
                BEEP_ENABLED_ANCHOR,
                false,
                -1,
                (short) 0,
                "Z",
                -1,
                null,
                null,
                null,
                false);
        int hooked = 0;
        for (long candidate : candidates) {
            Member member = dex.decodeMethodIndex(candidate);
            if (!(member instanceof Method)) continue;
            Method method = (Method) member;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    if (beepStateLogged.compareAndSet(false, true)) {
                        Log.w(TAG, "DisclosureAudioFiles: BeepSound enabled=" + param.getResult());
                    }
                }
            });
            hooked++;
        }
        return hooked;
    }

    private static File getSilentDisclosureFile(File original) {
        File cached = silentDisclosureFile;
        if (cached != null && cached.exists()) return cached;
        synchronized (DisclosureAudioFilesHooks.class) {
            cached = silentDisclosureFile;
            if (cached != null && cached.exists()) return cached;
            File parent = original.getParentFile();
            if (parent == null) {
                Log.w(TAG, "DisclosureAudioFiles: original file has no parent: " + original);
                return null;
            }
            File silent = new File(parent, "silent_disclosure.wav");
            try (FileOutputStream out = new FileOutputStream(silent, false)) {
                out.write(Init.wav);
                out.flush();
                silentDisclosureFile = silent;
                return silent;
            } catch (Throwable t) {
                Log.e(TAG, "DisclosureAudioFiles: failed to create silent disclosure file", t);
                return null;
            }
        }
    }
}
