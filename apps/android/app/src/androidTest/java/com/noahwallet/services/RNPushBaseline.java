package com.noahwallet.services;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Runs against the unmodified RN release. Only platform APIs and RN's own runtime classes. */
public final class RNPushBaseline extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String stage = "provider";
        try {
            Context context = getTargetContext();
            if (!context.getPackageName().equals("com.noahwallet.regtest")) throw new Exception();
            android.content.ComponentName widget = new android.content.ComponentName(context.getPackageName(), "com.noahwallet.widgets.NoahWidgetRegtestProvider");
            android.appwidget.AppWidgetProviderInfo provider = android.appwidget.AppWidgetManager.getInstance(context).getInstalledProviders()
                .stream().filter(info -> info.provider.equals(widget)).findFirst().orElseThrow(Exception::new);
            result.putString("widgetInfoSha256", sha(provider.provider.flattenToString() + "|" + provider.minWidth + "|" + provider.minHeight + "|" +
                provider.minResizeWidth + "|" + provider.minResizeHeight + "|" + provider.updatePeriodMillis + "|" + provider.resizeMode + "|" +
                provider.widgetCategory + "|" + context.getResources().getResourceEntryName(provider.initialLayout)));
            stage = "installation";
            ClassLoader loader = context.getClassLoader();
            Class<?> installation = loader.loadClass("expo.modules.notifications.serverregistration.InstallationId");
            Object identity = installation.getConstructor(Context.class).newInstance(context);
            String uuid = (String) installation.getMethod("getOrCreateUUID").invoke(identity);
            stage = "fcm";
            Class<?> firebase = loader.loadClass("com.google.firebase.messaging.FirebaseMessaging");
            Object messaging = firebase.getMethod("getInstance").invoke(null);
            firebase.getMethod("setAutoInitEnabled", boolean.class).invoke(messaging, true);
            Object task = firebase.getMethod("getToken").invoke(messaging);
            Class<?> tasks = loader.loadClass("com.google.android.gms.tasks.Tasks");
            Class<?> taskClass = loader.loadClass("com.google.android.gms.tasks.Task");
            String fcm = (String) tasks.getMethod("await", taskClass, long.class, java.util.concurrent.TimeUnit.class)
                .invoke(null, task, 60L, java.util.concurrent.TimeUnit.SECONDS);
            // Exchange with RN in the foreground, matching a real registration.
            stage = "foreground";
            startActivitySync(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setComponent(new android.content.ComponentName(context.getPackageName(), "com.noahwallet.MainActivity"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            stage = "expo";
            JSONObject request = new JSONObject().put("deviceId", uuid.toLowerCase(Locale.ROOT))
                .put("appId", context.getPackageName()).put("deviceToken", fcm).put("type", "fcm")
                .put("development", false).put("projectId", "6e79dffb-dcd4-4f3d-b596-638b16377eb0");
            HttpURLConnection connection = (HttpURLConnection) new URL("https://exp.host/--/api/v2/push/getExpoPushToken").openConnection();
            connection.setConnectTimeout(30000); connection.setReadTimeout(30000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try {
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                if (connection.getResponseCode() != 200) throw new Exception();
                String body;
                try (java.io.InputStream input = connection.getInputStream()) { body = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                String token = new JSONObject(body).getJSONObject("data").getString("expoPushToken");
                if (!token.startsWith("ExponentPushToken[") && !token.startsWith("ExpoPushToken[")) throw new Exception();
                // The RN JS gate skips emulators; register via the unchanged RN auth record.
                stage = "auth";
                String bearer = authToken(context, loader);
                stage = "registration";
                HttpURLConnection noah = (HttpURLConnection) new URL("http://10.0.2.2:3000/v0/register_push_token").openConnection();
                noah.setConnectTimeout(15000); noah.setReadTimeout(15000);
                noah.setRequestMethod("POST"); noah.setDoOutput(true);
                noah.setRequestProperty("Content-Type", "application/json");
                noah.setRequestProperty("Authorization", "Bearer " + bearer);
                try {
                    try (java.io.OutputStream output = noah.getOutputStream()) {
                        output.write(new JSONObject().put("push_token", token).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    if (noah.getResponseCode() != 200) throw new Exception();
                } finally { noah.disconnect(); }
                result.putString("expoIdSha256", sha(uuid));
                result.putString("expoTokenSha256", sha(token));
                result.putString("baseline", "RN Expo/FCM runtime token registered with Noah; hashes only");
            } finally { connection.disconnect(); }
            stage = "shortcut";
            android.content.pm.ShortcutManager shortcuts = context.getSystemService(android.content.pm.ShortcutManager.class);
            android.content.ComponentName activity = new android.content.ComponentName(context.getPackageName(), "com.noahwallet.MainActivity");
            android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(context, "noah-upgrade-check")
                .setShortLabel("Noah upgrade check").setActivity(activity)
                .setIntent(new android.content.Intent(android.content.Intent.ACTION_VIEW).setComponent(activity)).build();
            if (!shortcuts.isRequestPinShortcutSupported() || !shortcuts.requestPinShortcut(shortcut, null)) throw new Exception();
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            // Class names and fixed DNS codes are safe; never report provider exception messages.
            if (failure instanceof java.net.UnknownHostException) {
                Throwable cause = failure.getCause();
                result.putString("dnsCause", cause == null ? "none" : cause.getClass().getSimpleName());
                result.putString("dnsHost", failure.getMessage() != null && failure.getMessage().contains("exp.host") ? "exp.host" : "other");
            }
            result.putString("baseline", "Expo baseline unavailable at " + stage + " (" + failure.getClass().getSimpleName() + (failure instanceof java.net.UnknownHostException && failure.getMessage() != null && failure.getMessage().contains("Permission denied") ? ": permission denied" : "") + ")");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static String authToken(Context context, ClassLoader loader) throws Exception {
        byte[] bytes;
        try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(context.getFilesDir(), "datastore/RN_KEYCHAIN.preferences_pb"))) {
            bytes = input.readAllBytes();
        }
        Class<?> mapClass = loader.loadClass("androidx.datastore.preferences.PreferencesProto$PreferenceMap");
        Object proto = mapClass.getMethod("parseFrom", byte[].class).invoke(null, (Object) bytes);
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) mapClass.getMethod("getPreferencesMap").invoke(proto);
        String service = "com.noah.auth-token.regtest";
        Object marker = map.get(service + ":c"), password = map.get(service + ":p");
        if (marker == null || password == null) throw new Exception();
        String cipher = (String) marker.getClass().getMethod("getString").invoke(marker);
        byte[] encrypted = android.util.Base64.decode((String) password.getClass().getMethod("getString").invoke(password), android.util.Base64.DEFAULT);
        boolean gcm = cipher.equals("KeystoreAESGCM_NoAuth");
        if (!gcm && !cipher.equals("KeystoreAESCBC")) throw new Exception();
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        javax.crypto.SecretKey key = (javax.crypto.SecretKey) store.getKey(service, null);
        int length = gcm ? 12 : 16;
        javax.crypto.Cipher decrypt = javax.crypto.Cipher.getInstance(gcm ? "AES/GCM/NoPadding" : "AES/CBC/PKCS7Padding");
        decrypt.init(javax.crypto.Cipher.DECRYPT_MODE, key, gcm ? new javax.crypto.spec.GCMParameterSpec(128, java.util.Arrays.copyOf(encrypted, length))
            : new javax.crypto.spec.IvParameterSpec(java.util.Arrays.copyOf(encrypted, length)));
        byte[] plaintext = decrypt.doFinal(encrypted, length, encrypted.length - length);
        try { return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8); }
        finally { java.util.Arrays.fill(plaintext, (byte) 0); }
    }

    private static String sha(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
