package uz.akso.kalkulyator;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks public GitHub Releases and downloads the newest official AKSO Finance APK.
 * Releases are published by .github/workflows/publish-app-update.yml.
 */
final class AppUpdateManager {
    private static final String REPOSITORY = "Akhror4ek/akso-tolov-kalkulyatori";
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String APK_ASSET_NAME = "AKSO-Finance.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String PREFS = "akso_app_updates";
    private static final String LAST_CHECK = "last_check_at";
    private static final String CHECK_IN_PROGRESS = "check_in_progress";
    private static final long CHECK_INTERVAL_MS = 4L * 60L * 60L * 1000L;
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
                // Update checks are optional. Network/API errors must not block app startup.
            } finally {
                synchronized (AppUpdateManager.class) {
                    checkInProgress = false;
                }
            }

            if (update == null) return;
            final Update available = update;
            final long installedVersion = getInstalledVersionCode(activity);
            if (available.versionCode <= installedVersion) return;

            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || promptVisible) return;
                showUpdateDialog(activity, available, installedVersion);
            });
        });
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
            if (status == 404) return null; // No public app release has been published yet.
            if (status != HttpURLConnection.HTTP_OK) return null;

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
                if (APK_ASSET_NAME.equals(asset.optString("name", ""))) {
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
                && path.endsWith("/" + APK_ASSET_NAME);
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

    private static void showUpdateDialog(Activity activity, Update update,
                                         long installedVersion) {
        promptVisible = true;
        StringBuilder message = new StringBuilder()
                .append("Yangi versiya: ").append(update.versionName)
                .append(" (").append(update.versionCode).append(")")
                .append("\nO'rnatilgan versiya: ").append(installedVersion)
                .append("\n\nAKSO Finance uchun yangilanish tayyor.");
        if (!update.notes.isEmpty()) {
            message.append("\n\nO'zgarishlar:\n").append(update.notes);
        }
        message.append("\n\nYangilanishni yuklab o'rnatasizmi?");

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("YANGILANISH MAVJUD")
                .setMessage(message.toString())
                .setPositiveButton("HOZIR YANGILASH", (d, which) ->
                        downloadUpdate(activity, update))
                .setNegativeButton("KEYINROQ", null)
                .create();
        dialog.setOnDismissListener(d -> promptVisible = false);
        dialog.show();
    }

    private static void downloadUpdate(Activity activity, Update update) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(LAST_CHECK, 0L).apply();

            new AlertDialog.Builder(activity)
                    .setTitle("O'RNATISHGA RUXSAT KERAK")
                    .setMessage("Android xavfsizligi uchun AKSO Finance APK faylini o'rnatishga ruxsat bering. Sozlamalarda 'Ushbu manbadan ruxsat berish'ni yoqing.")
                    .setNegativeButton("BEKOR QILISH", null)
                    .setPositiveButton("SOZLAMALARNI OCHISH", (d, which) -> {
                        try {
                            Intent settings = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(settings);
                        } catch (Exception error) {
                            Toast.makeText(activity,
                                    "O'rnatish ruxsatini Sozlamalardan yoqing.",
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }

        try {
            DownloadManager manager = (DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("Yuklab olish xizmati mavjud emas.");

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl));
            request.setMimeType(APK_MIME_TYPE);
            request.setTitle("AKSO Finance " + update.versionName);
            request.setDescription("Yangilanish yuklab olinmoqda. Tugagach bildirishnomani bosing.");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "AKSO-Finance-update-v" + update.versionCode + ".apk");

            long downloadId = manager.enqueue(request);
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong("last_download_id", downloadId).apply();
            Toast.makeText(activity,
                    "Yangilanish yuklanmoqda. Tugagach bildirishnomani bosib o'rnating.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(activity,
                    "Yangilanishni yuklab bo'lmadi. Internetni tekshiring.",
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
