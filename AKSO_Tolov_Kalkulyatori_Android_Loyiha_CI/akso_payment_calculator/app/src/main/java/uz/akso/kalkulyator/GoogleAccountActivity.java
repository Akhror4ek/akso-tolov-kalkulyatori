package uz.akso.kalkulyator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Google account sign-in and profile screen for AKSO. */
public class GoogleAccountActivity extends Activity {
    public static final String ACTION_PROFILE = "uz.akso.kalkulyator.action.PROFILE";

    private FirebaseAuth auth;
    private CredentialManager credentialManager;
    private LinearLayout root;
    private TextView statusView;
    private Button googleButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = FirebaseAuth.getInstance();
        credentialManager = CredentialManager.create(this);

        if (ACTION_PROFILE.equals(getIntent().getAction())) {
            if (auth.getCurrentUser() == null) showLogin("Google akkauntingizga kiring.");
            else showProfile();
            return;
        }

        FirebaseUser current = auth.getCurrentUser();
        if (current != null) {
            startInitialSyncThenOpenMain();
        } else {
            showLogin(null);
        }
    }

    private void showLogin(String message) {
        if (statusRefresh != null) handler.removeCallbacks(statusRefresh);
        root = createRoot();
        LinearLayout card = createCard();

        addText(card, "AKSO  /  FINANCE", 12, Color.rgb(219, 255, 234), true, Gravity.START);
        addSpace(card, 20);
        addText(card, "👤", 42, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 8);
        addText(card, "AKSO hisobingizga kiring", 20, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 8);
        addText(card,
                "Google akkauntingiz bilan kiring. Shaxsiy hamyon, savdo kalendari va viktorina natijalaringiz akkauntingizga bog‘lanadi.",
                11, Color.rgb(235, 249, 240), false, Gravity.CENTER);
        addSpace(card, 20);

        googleButton = makeButton("G   Google orqali kirish", true);
        card.addView(googleButton, fullWidth(50));
        googleButton.setOnClickListener(v -> beginGoogleSignIn());

        addSpace(card, 12);
        addText(card,
                "Birinchi kirishda internet kerak. Kirganingizdan keyin ma’lumotlar qurilmada ham saqlanadi va internet qaytganda bulut bilan sinxronlanadi.",
                9, Color.rgb(221, 244, 230), false, Gravity.CENTER);

        if (message != null && !message.trim().isEmpty()) {
            addSpace(card, 14);
            statusView = addText(card, message, 10, Color.rgb(255, 225, 174), true, Gravity.CENTER);
        }
        root.addView(card, centeredCardParams());
        setContentView(root);
    }

    private void beginGoogleSignIn() {
        if (googleButton != null) {
            googleButton.setEnabled(false);
            googleButton.setText("Google bilan bog‘lanilmoqda…");
        }

        try {
            GetGoogleIdOption googleOption = new GetGoogleIdOption.Builder()
                    .setServerClientId(getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build();

            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build();

            Executor mainExecutor = command -> handler.post(command);
            credentialManager.getCredentialAsync(
                    this,
                    request,
                    new CancellationSignal(),
                    mainExecutor,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            handleCredential(result.getCredential());
                        }

                        @Override
                        public void onError(GetCredentialException error) {
                            showLogin(friendlyError(error.getMessage()));
                        }
                    });
        } catch (Exception error) {
            showLogin(friendlyError(error.getMessage()));
        }
    }

    private void handleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            showLogin("Google akkaunti tanlanmadi. Qayta urinib ko‘ring.");
            return;
        }

        try {
            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(
                    ((CustomCredential) credential).getData());
            AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(
                    googleCredential.getIdToken(), null);

            auth.signInWithCredential(firebaseCredential).addOnCompleteListener(this, task -> {
                if (task.isSuccessful() && auth.getCurrentUser() != null) {
                    Toast.makeText(this, "Google akkaunt tasdiqlandi.", Toast.LENGTH_SHORT).show();
                    startInitialSyncThenOpenMain();
                } else {
                    String detail = task.getException() == null
                            ? "Noma’lum xatolik" : task.getException().getMessage();
                    showLogin("Kirish amalga oshmadi: " + friendlyError(detail));
                }
            });
        } catch (GoogleIdTokenParsingException error) {
            showLogin("Google tokenini o‘qib bo‘lmadi. Qayta urinib ko‘ring.");
        }
    }

    private void startInitialSyncThenOpenMain() {
        showLoading("Google akkaunt tasdiqlandi. Bulutdagi ma’lumotlar tekshirilmoqda…");
        AksoCloudSync.start(getApplicationContext(), this::openMainAndClose);
    }

    private void showLoading(String message) {
        root = createRoot();
        LinearLayout card = createCard();
        addText(card, "AKSO  /  FINANCE", 12, Color.rgb(219, 255, 234), true, Gravity.START);
        addSpace(card, 20);
        addText(card, "⟳", 38, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 12);
        addText(card, message, 13, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 10);
        addText(card, "Bu jarayon tugaguncha ilovani yopmang.", 9,
                Color.rgb(225, 244, 232), false, Gravity.CENTER);
        root.addView(card, centeredCardParams());
        setContentView(root);
    }

    private void openMainAndClose() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void showProfile() {
        if (statusRefresh != null) handler.removeCallbacks(statusRefresh);
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            showLogin(null);
            return;
        }

        root = createRoot();
        LinearLayout card = createCard();
        addText(card, "AKSO  /  FOYDALANUVCHI PROFILI", 11,
                Color.rgb(219, 255, 234), true, Gravity.START);
        addSpace(card, 20);
        addText(card, "👤", 40, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 10);
        String displayName = user.getDisplayName() == null || user.getDisplayName().trim().isEmpty()
                ? "AKSO foydalanuvchisi" : user.getDisplayName();
        addText(card, displayName, 18, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 4);
        addText(card, user.getEmail() == null ? "Google akkaunt" : user.getEmail(),
                10, Color.rgb(226, 244, 232), false, Gravity.CENTER);
        addSpace(card, 18);

        statusView = addText(card, AksoCloudSync.getStatus(getApplicationContext()),
                10, Color.rgb(231, 247, 237), false, Gravity.CENTER);
        statusView.setLineSpacing(5, 1f);
        addSpace(card, 16);

        Button sync = makeButton("↻  Hozir sinxronlash", true);
        card.addView(sync, fullWidth(46));
        sync.setOnClickListener(v -> {
            sync.setEnabled(false);
            sync.setText("Sinxronlanmoqda…");
            AksoCloudSync.syncNow(getApplicationContext());
            handler.postDelayed(() -> {
                if (!isFinishing() && sync.getParent() != null) {
                    sync.setEnabled(true);
                    sync.setText("↻  Hozir sinxronlash");
                    if (statusView != null) statusView.setText(AksoCloudSync.getStatus(getApplicationContext()));
                }
            }, 2500L);
        });

        addSpace(card, 8);
        Button signOut = makeButton("Akkauntdan chiqish", false);
        card.addView(signOut, fullWidth(44));
        signOut.setOnClickListener(v -> confirmSignOut());

        addSpace(card, 8);
        Button close = makeButton("Yopish", false);
        card.addView(close, fullWidth(42));
        close.setOnClickListener(v -> finish());

        root.addView(card, centeredCardParams());
        setContentView(root);

        statusRefresh = new Runnable() {
            @Override public void run() {
                if (statusView != null && !isFinishing()) {
                    statusView.setText(AksoCloudSync.getStatus(getApplicationContext()));
                    handler.postDelayed(this, 1800L);
                }
            }
        };
        handler.postDelayed(statusRefresh, 1800L);
    }

    private void confirmSignOut() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Google akkauntdan chiqish")
                .setMessage("Avval hamyon, savdo kalendari va viktorina ma’lumotlari bulutga saqlanadi. Saqlash muvaffaqiyatsiz bo‘lsa, akkauntdan chiqilmaydi.")
                .setNegativeButton("Bekor qilish", (d, w) -> d.dismiss())
                .setPositiveButton("Chiqish", (d, w) -> {
                    if (statusView != null) statusView.setText("Ma’lumotlar bulutga saqlanmoqda…");
                    AksoCloudSync.flushAndClear(getApplicationContext(), (success, message) -> {
                        if (!success) {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                            if (statusView != null) statusView.setText(message);
                            return;
                        }
                        auth.signOut();
                        Toast.makeText(this, "Akkauntdan chiqdingiz.", Toast.LENGTH_SHORT).show();
                        showLogin("Akkauntdan chiqdingiz. Davom etish uchun Google orqali kiring.");
                    });
                }).show();
    }

    private String friendlyError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Internet va Firebase sozlamalarini tekshiring.";
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("no credential") || lower.contains("no credentials"))
            return "Telefonda Google akkaunt mavjudligini tekshiring.";
        if (lower.contains("network") || lower.contains("unavailable"))
            return "Internet aloqasini tekshiring.";
        if (lower.contains("developer_error") || lower.contains("12500") || lower.contains("10"))
            return "Firebase SHA-1 va OAuth sozlamalarini tekshirish kerak.";
        return raw.length() > 170 ? raw.substring(0, 170) : raw;
    }

    private FrameLayout createRoot() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0C2118, 0xFF17402C, 0xFF0D2D20}));
        return frame;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xE8406853, 0xE51E3C2D, 0xED142C21});
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0xBFFFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(8));
        return card;
    }

    private TextView addText(LinearLayout parent, String value, float size, int color,
                             boolean bold, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.DEFAULT, bold
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        view.setGravity(gravity);
        view.setLineSpacing(dp(3), 1f);
        parent.addView(view, fullWidth(-2));
        return view;
    }

    private Button makeButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                primary ? new int[]{0xFF20C878, 0xFF078B4B}
                        : new int[]{0xB83A5747, 0xA91C3328});
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), primary ? 0xAAE8FFF1 : 0x99FFFFFF);
        button.setBackground(bg);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private FrameLayout.LayoutParams centeredCardParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        params.setMargins(dp(20), dp(22), dp(20), dp(22));
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (auth != null && auth.getCurrentUser() == null) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (statusRefresh != null) handler.removeCallbacks(statusRefresh);
    }

    @Override
    protected void onDestroy() {
        if (statusRefresh != null) handler.removeCallbacks(statusRefresh);
        super.onDestroy();
    }
}
