package io.github.vvb2060.callrecording.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Legacy Xposed entry point for the modern disclosure audio-files compatibility hook. */
public final class DisclosureAudioLegacyInit implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        DisclosureAudioFilesHooks.install(
                lpparam.packageName,
                lpparam.processName,
                lpparam.classLoader,
                "legacy-audiofiles");
    }
}
