package uz.akso.kalkulyator;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

/**
 * Opens Android's package installer after the user taps the completed
 * AKSO Finance download notification.
 */
public final class AppUpdateDownloadReceiver extends BroadcastReceiver {
    private static final String PREFS = "akso_app_updates";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !DownloadManager.ACTION_NOTIFICATION_CLICKED.equals(intent.getAction())) {
            return;
        }

        long[] clickedIds = intent.getLongArrayExtra(
                DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS);
        if (clickedIds == null || clickedIds.length == 0) return;

        long expectedId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("last_download_id", -1L);
        if (expectedId <= 0) return;

        for (long id : clickedIds) {
            if (id != expectedId) continue;
            openDownloadedApk(context, id);
            return;
        }
    }

    private void openDownloadedApk(Context context, long downloadId) {
        DownloadManager manager = (DownloadManager)
                context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(context, "Yuklab olish xizmati topilmadi.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor == null || !cursor.moveToFirst()) {
                Toast.makeText(context, "Yuklangan APK topilmadi.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = statusColumn < 0 ? -1 : cursor.getInt(statusColumn);
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, "APK yuklab olinmadi. Internetni tekshirib qayta urinib ko'ring.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception error) {
            Toast.makeText(context, "Yuklangan APK holatini tekshirib bo'lmadi.",
                    Toast.LENGTH_LONG).show();
            return;
        } finally {
            if (cursor != null) cursor.close();
        }

        if (android.os.Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settings);
                Toast.makeText(context,
                        "AKSO Finance uchun APK o'rnatish ruxsatini yoqing. Ilovaga qaytgach o'rnatish davom etadi.",
                        Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                Toast.makeText(context,
                        "Android sozlamalaridan AKSO Finance uchun noma'lum manbadan o'rnatishga ruxsat bering.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        Uri apkUri = manager.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            Toast.makeText(context, "APK faylining manzili olinmadi.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, APK_MIME_TYPE);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(install);
        } catch (Exception error) {
            Toast.makeText(context,
                    "O'rnatish oynasini ochib bo'lmadi. Yuklab olish bildirishnomasini qayta bosing.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
