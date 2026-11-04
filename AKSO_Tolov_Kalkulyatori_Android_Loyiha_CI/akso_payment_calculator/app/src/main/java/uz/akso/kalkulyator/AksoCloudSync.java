package uz.akso.kalkulyator;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the AKSO app's existing SharedPreferences in sync with the signed-in Firebase user. */
public final class AksoCloudSync {
    private static final String META_PREFS = "akso_cloud_sync_metadata";
    private static final String MODIFIED_PREFIX = "modified_";
    private static final String STATUS_KEY = "sync_status";
    private static final String[] BUCKETS = {
            "akso_ui_preferences",
            "akso_daily_sales",
            "akso_personal_wallet",
            "akso_seller_quiz"
    };
    private static final long UPLOAD_DELAY_MS = 700L;

    private static Context appContext;
    private static FirebaseFirestore firestore;
    private static FirebaseAuth auth;
    private static String activeUid;
    private static boolean started;
    private static boolean applyingCloudData;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Map<String, SharedPreferences> preferenceFiles = new HashMap<>();
    private static final Map<String, SharedPreferences.OnSharedPreferenceChangeListener> preferenceListeners = new HashMap<>();
    private static final Map<String, ListenerRegistration> firestoreListeners = new HashMap<>();
    private static final Map<String, Runnable> pendingUploads = new HashMap<>();

    private AksoCloudSync() { }

    public interface ResultCallback {
        void onComplete(boolean success, String message);
    }

    public static synchronized void start(Context context) {
        start(context, null);
    }

    /** Starts synchronization and invokes onInitialSyncChecked after all four buckets
     * have either loaded their cloud snapshot or recorded an offline/read error. */
    public static synchronized void start(Context context, Runnable onInitialSyncChecked) {
        Context safeContext = context.getApplicationContext();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            if (onInitialSyncChecked != null) handler.post(onInitialSyncChecked);
            return;
        }
        if (started && user.getUid().equals(activeUid)) {
            if (onInitialSyncChecked != null) handler.post(onInitialSyncChecked);
            return;
        }
        if (started) stop();

        appContext = safeContext;
        auth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();
        activeUid = user.getUid();
        SharedPreferences meta = meta();

        for (String bucket : BUCKETS) {
            SharedPreferences prefs = safeContext.getSharedPreferences(bucket, Context.MODE_PRIVATE);
            preferenceFiles.put(bucket, prefs);
            SharedPreferences.OnSharedPreferenceChangeListener listener = (shared, key) -> {
                if (applyingCloudData || !isCurrentUser(activeUid)) return;
                queueUpload(bucket);
            };
            preferenceListeners.put(bucket, listener);
            prefs.registerOnSharedPreferenceChangeListener(listener);
        }
        started = true;
        setStatus("Bulut bilan sinxronlash boshlandi…");
        writeUserProfile(user);
        AtomicInteger remaining = new AtomicInteger(BUCKETS.length);
        Runnable bucketFinished = () -> {
            if (remaining.decrementAndGet() == 0 && onInitialSyncChecked != null) {
                handler.post(onInitialSyncChecked);
            }
        };
        for (String bucket : BUCKETS) {
            synchronizeInitialBucket(bucket, user.getUid(), bucketFinished);
        }
    }

    public static synchronized String getStatus(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                .getString(STATUS_KEY, "Sinxronlash hali boshlanmagan.");
    }

    public static void syncNow(Context context) {
        start(context);
        if (!started) return;
        setStatus("Ma’lumotlar bulut bilan tekshirilmoqda…");
        for (String bucket : BUCKETS) synchronizeInitialBucket(bucket, activeUid, null);
    }

    private static void writeUserProfile(FirebaseUser user) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("uid", user.getUid());
        if (user.getDisplayName() != null) profile.put("displayName", user.getDisplayName());
        if (user.getEmail() != null) profile.put("email", user.getEmail());
        if (user.getPhotoUrl() != null) profile.put("photoUrl", user.getPhotoUrl().toString());
        profile.put("lastSeenAt", System.currentTimeMillis());
        firestore.collection("users").document(user.getUid())
                .set(profile, com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener(error -> setStatus("Profilni saqlashda xatolik: " + shortMessage(error.getMessage())));
    }

    private static void synchronizeInitialBucket(String bucket, String uid, Runnable onFinished) {
        if (!isCurrentUser(uid) || firestore == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        DocumentReference doc = bucketDocument(uid, bucket);
        doc.get().addOnSuccessListener(snapshot -> {
            if (isCurrentUser(uid)) {
                long localModified = meta().getLong(MODIFIED_PREFIX + bucket, 0L);
                long remoteModified = readTimestamp(snapshot);
                if (!snapshot.exists() || localModified > remoteModified) {
                    uploadBucket(bucket);
                } else {
                    applyRemoteBucket(bucket, snapshot, remoteModified);
                }
                attachRemoteListener(uid, bucket);
            }
            if (onFinished != null) onFinished.run();
        }).addOnFailureListener(error -> {
            setStatus("Bulutga ulanib bo‘lmadi. Mahalliy ma’lumotlar saqlanadi: "
                    + shortMessage(error.getMessage()));
            attachRemoteListener(uid, bucket);
            if (onFinished != null) onFinished.run();
        });
    }

    private static void attachRemoteListener(String uid, String bucket) {
        synchronized (AksoCloudSync.class) {
            if (firestoreListeners.containsKey(bucket) || !isCurrentUser(uid)) return;
            ListenerRegistration registration = bucketDocument(uid, bucket)
                    .addSnapshotListener((snapshot, error) -> {
                        if (error != null || snapshot == null
                                || snapshot.getMetadata().hasPendingWrites() || !isCurrentUser(uid)) return;
                        if (!snapshot.exists()) {
                            uploadBucket(bucket);
                            return;
                        }
                        long remoteModified = readTimestamp(snapshot);
                        long localModified = meta().getLong(MODIFIED_PREFIX + bucket, 0L);
                        if (remoteModified > localModified) {
                            applyRemoteBucket(bucket, snapshot, remoteModified);
                            setStatus("Boshqa qurilmadagi o‘zgarishlar qabul qilindi.");
                        }
                    });
            firestoreListeners.put(bucket, registration);
        }
    }

    private static void queueUpload(String bucket) {
        Runnable previous = pendingUploads.remove(bucket);
        if (previous != null) handler.removeCallbacks(previous);
        Runnable next = () -> uploadBucket(bucket);
        pendingUploads.put(bucket, next);
        handler.postDelayed(next, UPLOAD_DELAY_MS);
    }

    private static void uploadBucket(String bucket) {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        if (user == null || !user.getUid().equals(activeUid)) return;
        long previous = meta().getLong(MODIFIED_PREFIX + bucket, 0L);
        long modifiedAt = Math.max(System.currentTimeMillis(), previous + 1L);
        meta().edit().putLong(MODIFIED_PREFIX + bucket, modifiedAt).apply();
        bucketDocument(user.getUid(), bucket).set(buildBucketDocument(bucket, user.getUid(), modifiedAt))
                .addOnSuccessListener(unused -> setStatus("Barcha ma’lumotlar bulut bilan sinxronlandi."))
                .addOnFailureListener(error -> setStatus("Sinxronlashda xatolik. Internetni tekshiring: "
                        + shortMessage(error.getMessage())));
    }

    private static Map<String, Object> buildBucketDocument(String bucket, String uid, long modifiedAt) {
        Map<String, Object> document = new HashMap<>();
        document.put("schemaVersion", 1L);
        document.put("uid", uid);
        document.put("updatedAt", modifiedAt);
        document.put("payload", encodePreferences(preferenceFiles.get(bucket)));
        return document;
    }

    private static Map<String, Object> encodePreferences(SharedPreferences preferences) {
        Map<String, Object> payload = new HashMap<>();
        if (preferences == null) return payload;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) payload.put(entry.getKey(), typed("string", value));
            else if (value instanceof Boolean) payload.put(entry.getKey(), typed("boolean", value));
            else if (value instanceof Integer) payload.put(entry.getKey(), typed("int", value));
            else if (value instanceof Long) payload.put(entry.getKey(), typed("long", value));
            else if (value instanceof Float) payload.put(entry.getKey(), typed("float", value));
            else if (value instanceof Set) {
                ArrayList<String> list = new ArrayList<>();
                for (Object item : (Set<?>) value) if (item instanceof String) list.add((String) item);
                payload.put(entry.getKey(), typed("string_set", list));
            }
        }
        return payload;
    }

    private static Map<String, Object> typed(String type, Object value) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("type", type);
        wrapper.put("value", value);
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private static void applyRemoteBucket(String bucket, DocumentSnapshot snapshot, long remoteModified) {
        Object rawPayload = snapshot.get("payload");
        if (!(rawPayload instanceof Map) || !preferenceFiles.containsKey(bucket)) return;
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        SharedPreferences.Editor editor = preferenceFiles.get(bucket).edit().clear();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<?, ?> wrapper = (Map<?, ?>) entry.getValue();
            Object typeObject = wrapper.get("type");
            Object value = wrapper.get("value");
            if (!(typeObject instanceof String)) continue;
            String type = (String) typeObject;
            String key = entry.getKey();
            if ("string".equals(type) && value instanceof String) editor.putString(key, (String) value);
            else if ("boolean".equals(type) && value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if ("int".equals(type) && value instanceof Number) editor.putInt(key, ((Number) value).intValue());
            else if ("long".equals(type) && value instanceof Number) editor.putLong(key, ((Number) value).longValue());
            else if ("float".equals(type) && value instanceof Number) editor.putFloat(key, ((Number) value).floatValue());
            else if ("string_set".equals(type) && value instanceof List) {
                Set<String> values = new HashSet<>();
                for (Object item : (List<?>) value) if (item instanceof String) values.add((String) item);
                editor.putStringSet(key, values);
            }
        }
        applyingCloudData = true;
        editor.commit();
        meta().edit().putLong(MODIFIED_PREFIX + bucket, remoteModified).apply();
        applyingCloudData = false;
    }

    private static long readTimestamp(DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return 0L;
        Object value = snapshot.get("updatedAt");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static DocumentReference bucketDocument(String uid, String bucket) {
        return firestore.collection("users").document(uid)
                .collection("appData").document(bucket);
    }

    private static SharedPreferences meta() {
        return appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
    }

    private static void setStatus(String value) {
        if (appContext == null) return;
        meta().edit().putString(STATUS_KEY, value).putLong("last_status_at", System.currentTimeMillis()).apply();
    }

    private static boolean isCurrentUser(String uid) {
        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        return started && uid != null && uid.equals(activeUid)
                && user != null && uid.equals(user.getUid());
    }

    public static void flushAndClear(Context context, ResultCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onComplete(false, "Google akkaunt topilmadi.");
            return;
        }
        start(context);
        List<Task<Void>> writes = new ArrayList<>();
        String uid = user.getUid();
        for (String bucket : BUCKETS) {
            long previous = meta().getLong(MODIFIED_PREFIX + bucket, 0L);
            long modifiedAt = Math.max(System.currentTimeMillis(), previous + 1L);
            meta().edit().putLong(MODIFIED_PREFIX + bucket, modifiedAt).apply();
            writes.add(bucketDocument(uid, bucket).set(buildBucketDocument(bucket, uid, modifiedAt)));
        }
        Tasks.whenAll(writes).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                callback.onComplete(false, "Bulutga saqlash amalga oshmadi. Internetni tekshiring; akkauntdan chiqilmadi.");
                return;
            }
            stop();
            for (String bucket : BUCKETS) {
                context.getApplicationContext().getSharedPreferences(bucket, Context.MODE_PRIVATE)
                        .edit().clear().commit();
            }
            context.getApplicationContext().getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().commit();
            callback.onComplete(true, "Ma’lumotlar bulutga saqlandi.");
        });
    }

    private static synchronized void stop() {
        for (Map.Entry<String, SharedPreferences.OnSharedPreferenceChangeListener> entry
                : preferenceListeners.entrySet()) {
            SharedPreferences prefs = preferenceFiles.get(entry.getKey());
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(entry.getValue());
        }
        preferenceListeners.clear();
        preferenceFiles.clear();
        for (ListenerRegistration listener : firestoreListeners.values()) listener.remove();
        firestoreListeners.clear();
        for (Runnable runnable : pendingUploads.values()) handler.removeCallbacks(runnable);
        pendingUploads.clear();
        started = false;
        activeUid = null;
        applyingCloudData = false;
    }

    private static String shortMessage(String value) {
        if (value == null || value.trim().isEmpty()) return "Noma’lum xatolik";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
