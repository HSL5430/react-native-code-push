package com.microsoft.codepush.react;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.TextUtils;

import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.JavaScriptModule;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.devsupport.DevInternalSettings;
import com.facebook.react.devsupport.interfaces.DevSupportManager;
import com.facebook.react.uimanager.ViewManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CodePush implements ReactPackage {

    private boolean sIsRunningBinaryVersion = false;
    private boolean sNeedToReportRollback = false;
    private boolean sTestConfigurationFlag = false;
    private String sAppVersion = null;

    private boolean mDidUpdate = false;

    private String mAssetsBundleFileName;
    private String mAssetsBundleFilePath;

    // Helper classes.
    private CodePushUpdateManager mUpdateManager;
    private CodePushTelemetryManager mTelemetryManager;
    private SettingsManager mSettingsManager;

    // Config properties.
    private String mDeploymentKey;
    private String mServerUrl = "https://codepush.azurewebsites.net/";

    private Context mContext;
    private final boolean mIsDebugMode;

    private String mPublicKey;

    public CodePush(String deploymentKey, Context context) {
        this(deploymentKey, context, false);
    }

    public CodePush(String deploymentKey, Context context, boolean isDebugMode) {
        mContext = context.getApplicationContext();

        mUpdateManager = createUpdateManager(context);
        mTelemetryManager = new CodePushTelemetryManager(mContext);
        mDeploymentKey = deploymentKey;
        mIsDebugMode = isDebugMode;
        mSettingsManager = new SettingsManager(mContext);

        if (sAppVersion == null) {
            try {
                PackageInfo pInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
                sAppVersion = pInfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                throw new CodePushUnknownException("Unable to get package info for " + mContext.getPackageName(), e);
            }
        }

        clearDebugCacheIfNeeded(null);
        initializeUpdateAfterRestart();
    }

    public CodePush(String deploymentKey, Context context, boolean isDebugMode, String serverUrl) {
        this(deploymentKey, context, isDebugMode);
        if (!TextUtils.isEmpty(serverUrl)) {
            mServerUrl = serverUrl;
        }
    }

    public CodePush(String deploymentKey, Context context, boolean isDebugMode, int publicKeyResourceDescriptor) {
        this(deploymentKey, context, isDebugMode, null, publicKeyResourceDescriptor);
    }

    public CodePush(String deploymentKey, Context context, boolean isDebugMode, String serverUrl, Integer publicKeyResourceDescriptor) {
        this(deploymentKey, context, isDebugMode, serverUrl);

        if (publicKeyResourceDescriptor != null) {
            mPublicKey = getPublicKeyByResourceDescriptor(publicKeyResourceDescriptor);
        }
    }

    private String getPublicKeyByResourceDescriptor(int publicKeyResourceDescriptor) {
        String publicKey;
        try {
            publicKey = mContext.getString(publicKeyResourceDescriptor);
        } catch (Resources.NotFoundException e) {
            throw new CodePushInvalidPublicKeyException(
                    "Unable to get public key, related resource descriptor " +
                            publicKeyResourceDescriptor +
                            " can not be found", e
            );
        }

        if (publicKey.isEmpty()) {
            throw new CodePushInvalidPublicKeyException("Specified public key is empty");
        }
        return publicKey;
    }

    public void clearDebugCacheIfNeeded(ReactInstanceManager instanceManager) {
        boolean isLiveReloadEnabled = false;

        // Use instanceManager for checking if we use LiveRelaod mode. In this case we should not remove ReactNativeDevBundle.js file
        // because we get error with trying to get this after reloading. Issue: https://github.com/Microsoft/react-native-code-push/issues/1272
        if (instanceManager != null) {
            DevSupportManager devSupportManager = instanceManager.getDevSupportManager();
            if (devSupportManager != null) {
                DevInternalSettings devInternalSettings = (DevInternalSettings) devSupportManager.getDevSettings();
                isLiveReloadEnabled = devInternalSettings.isReloadOnJSChangeEnabled();
            }
        }

        if (mIsDebugMode && mSettingsManager.isPendingUpdate(null) && !isLiveReloadEnabled) {
            // This needs to be kept in sync with https://github.com/facebook/react-native/blob/master/ReactAndroid/src/main/java/com/facebook/react/devsupport/DevSupportManager.java#L78
            File cachedDevBundle = new File(mContext.getFilesDir(), "ReactNativeDevBundle.js");
            if (cachedDevBundle.exists()) {
                cachedDevBundle.delete();
            }
        }
    }

    public boolean didUpdate() {
        return mDidUpdate;
    }

    public String getAppVersion() {
        return sAppVersion;
    }

    public String getAssetsBundleFileName() {
        return mAssetsBundleFileName;
    }

    public String getAssetsBundleFilePath() {
        return mAssetsBundleFilePath;
    }

    public String getAssetsBundleFileDir() {
        try {
            return new File(getAssetsBundleFilePath()).getParent();
        } catch (Exception e) {
            return null;
        }
    }

    public String getPublicKey() {
        return mPublicKey;
    }

    long getBinaryResourcesModifiedTime() {
        return getApkBuildTime(this.mContext);
    }

    public static long getApkBuildTime(Context context) {
        try {
            String packageName = context.getPackageName();
            int codePushApkBuildTimeId = context.getResources().getIdentifier(CodePushConstants.CODE_PUSH_APK_BUILD_TIME_KEY, "string", packageName);
            // replace double quotes needed for correct restoration of long value from strings.xml
            // https://github.com/Microsoft/cordova-plugin-code-push/issues/264
            String codePushApkBuildTime = context.getResources().getString(codePushApkBuildTimeId).replaceAll("\"", "");
            return Long.parseLong(codePushApkBuildTime);
        } catch (Exception e) {
            throw new CodePushUnknownException("Error in getting binary resources modified time", e);
        }
    }

    public String getPackageFolder() {
        JSONObject codePushLocalPackage = mUpdateManager.getCurrentPackage();
        if (codePushLocalPackage == null) {
            return null;
        }
        return mUpdateManager.getPackageFolderPath(codePushLocalPackage.optString("packageHash"));
    }

    @Deprecated
    public String getBundleUrl() {
        return getJSBundleFile();
    }

    @Deprecated
    public String getBundleUrl(String assetsBundleFileName) {
        return getJSBundleFile(assetsBundleFileName);
    }

    public Context getContext() {
        return mContext;
    }

    public String getDeploymentKey() {
        return mDeploymentKey;
    }

    public String getJSBundleFile() {
        return getJSBundleFile(CodePushConstants.DEFAULT_JS_BUNDLE_NAME);
    }

    public String getJSBundleFile(String assetsBundleFileName) {
        return getJSBundleFileInternal(assetsBundleFileName);
    }

    public String getJSBundleFileInternal(String assetsBundleFileName) {
        // 支持assets文件路径
        this.mAssetsBundleFilePath = assetsBundleFileName;
        File file = new File(assetsBundleFileName);
        mAssetsBundleFileName = file.getName();
        String binaryJsBundleUrl = CodePushConstants.ASSETS_BUNDLE_PREFIX + assetsBundleFileName;

        String packageFilePath = null;
        try {
            packageFilePath = mUpdateManager.getCurrentPackageBundlePath(this.mAssetsBundleFileName);
        } catch (CodePushMalformedDataException e) {
            // We need to recover the app in case 'codepush.json' is corrupted
            CodePushUtils.log(e.getMessage());
            clearUpdates();
        }

        if (packageFilePath == null) {
            // There has not been any downloaded updates.
            CodePushUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }

        JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
        if (isPackageBundleLatest(packageMetadata)) {
            CodePushUtils.logBundleUrl(packageFilePath);
            sIsRunningBinaryVersion = false;
            return packageFilePath;
        } else {
            // The binary version is newer.
            this.mDidUpdate = false;
            if (!this.mIsDebugMode || hasBinaryVersionChanged(packageMetadata)) {
                this.clearUpdates();
            }

            CodePushUtils.logBundleUrl(binaryJsBundleUrl);
            sIsRunningBinaryVersion = true;
            return binaryJsBundleUrl;
        }
    }

    public String getServerUrl() {
        return mServerUrl;
    }

    void initializeUpdateAfterRestart() {
        // Reset the state which indicates that
        // the app was just freshly updated.
        mDidUpdate = false;

        JSONObject pendingUpdate = mSettingsManager.getPendingUpdate();
        if (pendingUpdate != null) {
            JSONObject packageMetadata = this.mUpdateManager.getCurrentPackage();
            if (packageMetadata == null || !isPackageBundleLatest(packageMetadata) && hasBinaryVersionChanged(packageMetadata)) {
                CodePushUtils.log("Skipping initializeUpdateAfterRestart(), binary version is newer");
                return;
            }

            try {
                boolean updateIsLoading = pendingUpdate.getBoolean(CodePushConstants.PENDING_UPDATE_IS_LOADING_KEY);
                if (updateIsLoading) {
                    // Pending update was initialized, but notifyApplicationReady was not called.
                    // Therefore, deduce that it is a broken update and rollback.
                    CodePushUtils.log("Update did not finish loading the last time, rolling back to a previous version.");
                    sNeedToReportRollback = true;
                    rollbackPackage();
                } else {
                    // There is in fact a new update running for the first
                    // time, so update the local state to ensure the client knows.
                    mDidUpdate = true;

                    // Mark that we tried to initialize the new update, so that if it crashes,
                    // we will know that we need to rollback when the app next starts.
                    mSettingsManager.savePendingUpdate(pendingUpdate.getString(CodePushConstants.PENDING_UPDATE_HASH_KEY),
                            /* isLoading */true);
                }
            } catch (JSONException e) {
                // Should not happen.
                throw new CodePushUnknownException("Unable to read pending update metadata stored in SharedPreferences", e);
            }
        }
    }

    void invalidateCurrentInstance() {
        // mCurrentInstance = null;
    }

    public boolean isDebugMode() {
        return mIsDebugMode;
    }

    boolean isRunningBinaryVersion() {
        return sIsRunningBinaryVersion;
    }

    private boolean isPackageBundleLatest(JSONObject packageMetadata) {
        try {
            Long binaryModifiedDateDuringPackageInstall = null;
            String binaryModifiedDateDuringPackageInstallString = packageMetadata.optString(CodePushConstants.BINARY_MODIFIED_TIME_KEY, null);
            if (binaryModifiedDateDuringPackageInstallString != null) {
                binaryModifiedDateDuringPackageInstall = Long.parseLong(binaryModifiedDateDuringPackageInstallString);
            }
            String packageAppVersion = packageMetadata.optString("appVersion", null);
            long binaryResourcesModifiedTime = this.getBinaryResourcesModifiedTime();
            return binaryModifiedDateDuringPackageInstall != null &&
                    binaryModifiedDateDuringPackageInstall == binaryResourcesModifiedTime &&
                    (isUsingTestConfiguration() || TextUtils.equals(sAppVersion, packageAppVersion));
        } catch (NumberFormatException e) {
            throw new CodePushUnknownException("Error in reading binary modified date from package metadata", e);
        }
    }

    private boolean hasBinaryVersionChanged(JSONObject packageMetadata) {
        String packageAppVersion = packageMetadata.optString("appVersion", null);
        return !TextUtils.equals(sAppVersion, packageAppVersion);
    }

    boolean needToReportRollback() {
        return sNeedToReportRollback;
    }

    public void overrideAppVersion(String appVersionOverride) {
        sAppVersion = appVersionOverride;
    }

    private void rollbackPackage() {
        JSONObject failedPackage = mUpdateManager.getCurrentPackage();
        mSettingsManager.saveFailedUpdate(failedPackage);
        mUpdateManager.rollbackPackage();
        mSettingsManager.removePendingUpdate();
    }

    public void setNeedToReportRollback(boolean needToReportRollback) {
        sNeedToReportRollback = needToReportRollback;
    }

    /* The below 3 methods are used for running tests.*/
    public boolean isUsingTestConfiguration() {
        return sTestConfigurationFlag;
    }

    public void setDeploymentKey(String deploymentKey) {
        mDeploymentKey = deploymentKey;
    }

    public void setUsingTestConfiguration(boolean shouldUseTestConfiguration) {
        sTestConfigurationFlag = shouldUseTestConfiguration;
    }

    public void clearUpdates() {
        mUpdateManager.clearUpdates();
        mSettingsManager.removePendingUpdate();
        mSettingsManager.removeFailedUpdates();
    }

    @Override
    public List<NativeModule> createNativeModules(ReactApplicationContext reactApplicationContext) {
        CodePushNativeModule codePushModule = new CodePushNativeModule(reactApplicationContext, this, mUpdateManager, mTelemetryManager, mSettingsManager);
        CodePushDialog dialogModule = new CodePushDialog(reactApplicationContext);

        List<NativeModule> nativeModules = new ArrayList<>();
        nativeModules.add(codePushModule);
        nativeModules.add(dialogModule);
        return nativeModules;
    }

    // Deprecated in RN v0.47.
    public List<Class<? extends JavaScriptModule>> createJSModules() {
        return new ArrayList<>();
    }

    @Override
    public List<ViewManager> createViewManagers(ReactApplicationContext reactApplicationContext) {
        return new ArrayList<>();
    }

    public CodePushUpdateManager createUpdateManager(Context context) {
        return new CodePushUpdateManager(context.getFilesDir().getAbsolutePath(), this);
    }

    private ReactInstanceManager mReactInstanceManager;

    public void setReactInstanceManager(ReactInstanceManager reactInstanceManager) {
        mReactInstanceManager = reactInstanceManager;
    }

    public ReactInstanceManager getReactInstanceManager() {
        return mReactInstanceManager;
    }

}
