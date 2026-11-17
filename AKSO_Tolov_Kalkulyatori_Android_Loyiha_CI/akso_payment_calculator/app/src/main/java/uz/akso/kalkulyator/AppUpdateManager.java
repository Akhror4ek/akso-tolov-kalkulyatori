package uz.akso.kalkulyator;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mandatory release updater for AKSO Finance.
 * A newer official release is persisted locally and blocks app use until its
 * package is installed. Android's final package installation confirmation is
 * still required by the operating system.
 */
final class AppUpdateManager {
    private static final String REPOSITORY = "Akhror4ek/akso-tolov-kalkulyatori";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String APK_ASSET_NAME = "AKSO-Finance.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String PREFS = "akso_app_updates";
    private static final String LAST_CHECK = "last_check_at";
    private static final String PENDING_VERSION = "mandatory_pending_version";
    private static final String PENDING_NAME = "mandatory_pending_name";
    private static final String PENDING_TAG = "mandatory_pending_tag";
    private static final String PENDING_NOTES = "mandatory_pending_notes";
    private static final String PENDING_APK_URL = "mandatory_pending_apk_url";
    private static final String DOWNLOAD_ID = "mandatory_download_id";
    private static final String DOWNLOAD_VERSION = "mandatory_download_version";
    private static final long CHECK_INTERVAL_MS = 5L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean promptVisible = false;
    private static boolean checkInProgress = false;

    private AppUpdateManager() { }

    private static final class Update {
        final long versionCode;
        final String versionName;
        final String tagName;
        final String notes;
        final String apkUrl;

        Update(long versionCode, String versionName, String tagName,
               String notes, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.tagName = tagName == null ? "" : tagName;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
        }
    }

    static void checkForUpdate(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        SharedPreferences preferences =
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long installedVersion = getInstalledVersionCode(activity);
        Update pending = readPendingUpdate(preferences);

        // A successful installation clears the mandatory gate immediately.
        if (pending != null && installedVersion >= pending.versionCode) {
            clearPendingUpdate(preferences);
            preferences.edit().putLong(LAST_CHECK, 0L).apply();
            pending = null;
        }

        // Pending releases bypass the five-minute network throttle. An older
        // version can never dismiss or skip a previously discovered update.
        if (pending != null) {
            showMandatoryUpdateDialog(activity, pending, installedVersion);
            return;
        }

        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong(LAST_CHECK, 0L);
        if (now - lastCheck < CHECK_INTERVAL_MS) return;

        synchronized (AppUpdateManager.class) {
            if (checkInProgress) return;
            checkInProgress = true;
        }
        preferences.edit().putLong(LAST_CHECK, now).apply();

        EXECUTOR.execute(() -> {
            Update update = null;
            try {
                update = fetchLatestRelease();
            } catch (Exception ignored) {
                // A temporary network failure does not make app startup fail.
                preferences.edit().putLong(LAST_CHECK, 0L).apply();
            } finally {
                synchronized (AppUpdateManager.class) {
                    checkInProgress = false;
                }
            }

            if (update == null) return;
            final Update available = update;
            final long currentlyInstalled = getInstalledVersionCode(activity);
            if (available.versionCode <= currentlyInstalled) return;

            // Save before showing the dialog. If the process/activity is closed,
            // the mandatory gate is restored on the next app open, even offline.
            savePendingUpdate(preferences, available);
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showMandatoryUpdateDialog(activity, available, currentlyInstalled);
            });
        });
    }

    private static Update readPendingUpdate(SharedPreferences preferences) {
        long version = preferences.getLong(PENDING_VERSION, -1L);
        String url = preferences.getString(PENDING_APK_URL, "");
        if (version <= 0 || url == null || !isTrustedApkUrl(url)) return null;
        return new Update(version,
                preferences.getString(PENDING_NAME, "AKSO Finance"),
                preferences.getString(PENDING_TAG, "v" + version),
                preferences.getString(PENDING_NOTES, ""),
                url);
    }

    private static void savePendingUpdate(SharedPreferences preferences, Update update) {
        preferences.edit()
                .putLong(PENDING_VERSION, update.versionCode)
                .putString(PENDING_NAME, update.versionName)
                .putString(PENDING_TAG, update.tagName)
                .putString(PENDING_NOTES, update.notes)
                .putString(PENDING_APK_URL, update.apkUrl)
                .apply();
    }

    private static void clearPendingUpdate(SharedPreferences preferences) {
        preferences.edit()
                .remove(PENDING_VERSION)
                .remove(PENDING_NAME)
                .remove(PENDING_TAG)
                .remove(PENDING_NOTES)
                .remove(PENDING_APK_URL)
                .remove(DOWNLOAD_ID)
                .remove(DOWNLOAD_VERSION)
                .apply();
    }

    private static Update fetchLatestRelease() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "AKSO-Finance-Android");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub release tekshiruvi HTTP " + status + " qaytardi.");
            }

            String body = readLimited(connection.getInputStream());
            JSONObject release = new JSONObject(body);
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                return null;
            }

            String tag = release.optString("tag_name", "").trim();
            long releaseVersion = parseVersionCode(tag);
            if (releaseVersion <= 0) return null;

            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) return null;
            String apkUrl = "";
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String assetName = asset.optString("name", "").trim();
                String assetLabel = asset.optString("label", "").trim();
                if (APK_ASSET_NAME.equals(assetName)
                        || APK_ASSET_NAME.equals(assetLabel)
                        || "app-debug.apk".equalsIgnoreCase(assetName)) {
                    apkUrl = asset.optString("browser_download_url", "").trim();
                    break;
                }
            }
            if (!isTrustedApkUrl(apkUrl)) return null;

            String name = release.optString("name", "").trim();
            if (name.isEmpty()) name = tag;
            String notes = release.optString("body", "").trim();
            if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
            return new Update(releaseVersion, name, tag, notes, apkUrl);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static long parseVersionCode(String tag) {
        if (tag == null) return -1L;
        String value = tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean isTrustedApkUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        Uri uri = Uri.parse(value);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return "https".equalsIgnoreCase(uri.getScheme())
                && "github.com".equalsIgnoreCase(uri.getHost())
                && path.startsWith("/" + REPOSITORY + "/releases/download/")
                && path.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
    }

    private static long getInstalledVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
            //noinspection deprecation
            return info.versionCode;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static void showMandatoryUpdateDialog(Activity activity, Update update,
                                                  long installedVersion) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || promptVisible) return;

        promptVisible = true;
        StringBuilder message = new StringBuilder()
                .append("Yangi versiya: ").append(update.versionName)
                .append(" (").append(update.versionCode).append(")")
                .append("\nO'rnatilgan versiya: ").append(installedVersion)
                .append("\n\nAKSO Finance'dan foydalanishni davom ettirish uchun ushbu yangilanishni o'rnatish shart.");
        if (!update.notes.isEmpty()) {
            message.append("\n\nYangilanishdagi o'zgarishlar:\n").append(update.notes);
        }
        message.append("\n\nYangilanish o'rnatilmaguncha dastur bo'limlari ochilmaydi.");

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("MAJBURIY YANGILANISH")
                .setMessage(message.toString())
                .setPositiveButton("YANGILASHNI DAVOM ETISH", (d, which) ->
                        MAIN.post(() -> continueMandatoryUpdate(activity, update)))
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> promptVisible = false);
        dialog.show();
    }

    private static void continueMandatoryUpdate(Activity activity, Update update) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        SharedPreferences preferences =
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (getInstalledVersionCode(activity) >= update.versionCode) {
            clearPendingUpdate(preferences);
            return;
        }

        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            showInstallPermissionDialog(activity);
            return;
        }

        DownloadManager manager = (DownloadManager)
                activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            showMandatoryStatusDialog(activity, update,
                    "Yuklab olish xizmati mavjud emas. Qayta urinib ko'ring.");
            return;
        }

        long previousVersion = preferences.getLong(DOWNLOAD_VERSION, -1L);
        long previousId = preferences.getLong(DOWNLOAD_ID, -1L);
        if (previousVersion == update.versionCode && previousId > 0) {
            int status = getDownloadStatus(manager, previousId);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                openDownloadedApk(activity, manager, previousId);
                return;
            }
            if (status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED) {
                showMandatoryStatusDialog(activity, update,
                        "Yangilanish yuklanmoqda. Yuklab olish tugagach, o'rnatish oynasi ochiladi.");
                return;
            }
        }

        try {
            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(update.apkUrl));
            request.setMimeType(APK_MIME_TYPE);
            request.setTitle("AKSO Finance " + update.versionName);
            request.setDescription("Majburiy yangilanish yuklab olinmoqda.");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "AKSO-Finance-update-v" + update.versionCode + ".apk");

            long downloadId = manager.enqueue(request);
            preferences.edit()
                    .putLong(DOWNLOAD_ID, downloadId)
                    .putLong(DOWNLOAD_VERSION, update.versionCode)
                    .putLong("last_download_id", downloadId)
                    .apply();

            showMandatoryStatusDialog(activity, update,
                    "Yangilanish yuklab olinmoqda. Yuklab olish tugagach bildirishnomani bosing yoki holatni tekshiring.");
        } catch (Exception error) {
            showMandatoryStatusDialog(activity, update,
                    "APK yuklab olinmadi. Internetni tekshirib, qayta urinib ko'ring.");
        }
    }

    private static int getDownloadStatus(DownloadManager manager, long downloadId) {
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor == null || !cursor.moveToFirst()) return -1;
            int column = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return column < 0 ? -1 : cursor.getInt(column);
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void showMandatoryStatusDialog(Activity activity, Update update,
                                                  String statusMessage) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || promptVisible) return;
        promptVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("YANGILANISH MAJBURIY")
                .setMessage(statusMessage + "\n\nEski versiyada ishlashni davom ettirib bo'lmaydi.")
                .setPositiveButton("HOLATNI TEKSHIRISH", (d, which) ->
                        MAIN.post(() -> continueMandatoryUpdate(activity, update)))
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> promptVisible = false);
        dialog.show();
    }

    private static void showInstallPermissionDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || promptVisible) return;
        promptVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("O'RNATISHGA RUXSAT KERAK")
                .setMessage("Android xavfsizligi uchun AKSO Finance yangilanishini o'rnatishga ruxsat bering. Sozlamalarda 'Ushbu manbadan ruxsat berish'ni yoqing. Ruxsat berilmaguncha ilovadan foydalanib bo'lmaydi.")
                .setPositiveButton("SOZLAMANI OCHISH", (d, which) -> MAIN.post(() -> {
                    try {
                        Intent settings = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settings);
                    } catch (Exception error) {
                        Toast.makeText(activity,
                                "O'rnatish ruxsatini Android sozlamalaridan yoqing.",
                                Toast.LENGTH_LONG).show();
                    }
                }))
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> promptVisible = false);
        dialog.show();
    }

    private static void openDownloadedApk(Activity activity, DownloadManager manager,
                                          long downloadId) {
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            showInstallPermissionDialog(activity);
            return;
        }
        try {
            Uri apkUri = manager.getUriForDownloadedFile(downloadId);
            if (apkUri == null) {
                showMandatoryStatusDialog(activity, readPendingUpdate(
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
                        "Yuklangan APK topilmadi. Qayta yuklashga urinib ko'ring.");
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, APK_MIME_TYPE);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Exception error) {
            Toast.makeText(activity, "Android o'rnatish oynasini ochib bo'lmadi.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String readLimited(InputStream stream) throws Exception {
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("GitHub release javobi juda katta.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
}
