package uz.akso.kalkulyator;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Connects one seller's WASYT company account to this device. */
public class WasytAccountActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private EditText usernameInput;
    private EditText passwordInput;
    private TextView connectButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (WasytSecureStore.getValidToken(this) != null) {
            showConnected();
        } else {
            showLogin(null);
        }
    }

    private void showLogin(String message) {
        root = createRoot();
        LinearLayout card = createCard();
        addText(card, "AKSO  /  WASYT INTEGRATSIYA", 11,
                Color.rgb(219, 255, 234), true, Gravity.START);
        addSpace(card, 18);
        addText(card, "WASYT akkauntini ulash", 20, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 8);
        addText(card,
                "Sotuvchi o‘z WASYT login va parolini shu yerga kiritadi. Akkaunt tekshirilgach, shtrix-kod orqali tovar qidirish uchun xavfsiz ulanish saqlanadi.",
                11, Color.rgb(235, 249, 240), false, Gravity.CENTER);
        addSpace(card, 18);

        addText(card, "WASYT login", 10, Color.rgb(221, 244, 230), true, Gravity.START);
        usernameInput = makeInput("Login yoki foydalanuvchi nomi", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        card.addView(usernameInput, fullWidth(dp(48)));
        String savedUsername = WasytSecureStore.getUsername(this);
        if (!savedUsername.isEmpty()) usernameInput.setText(savedUsername);

        addSpace(card, 10);
        addText(card, "WASYT parol", 10, Color.rgb(221, 244, 230), true, Gravity.START);
        passwordInput = makeInput("Parol", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(passwordInput, fullWidth(dp(48)));

        addSpace(card, 16);
        connectButton = makeButton("WASYT AKKAUNTINI TEKSHIRISH VA ULASH", true);
        card.addView(connectButton, fullWidth(dp(48)));
        connectButton.setOnClickListener(v -> connectAccount());

        addSpace(card, 10);
        addText(card,
                "Parol AKSO Finance’da saqlanmaydi. Faqat WASYT tokeni telefonning Android Keystore tizimi bilan shifrlanadi. Token muddati taxminan 7 kun.",
                9, Color.rgb(221, 244, 230), false, Gravity.CENTER);

        if (message != null && !message.trim().isEmpty()) {
            addSpace(card, 12);
            statusText = addText(card, message, 10,
                    Color.rgb(255, 225, 174), true, Gravity.CENTER);
        }
        addSpace(card, 12);
        TextView close = makeButton("YOPISH", false);
        card.addView(close, fullWidth(dp(42)));
        close.setOnClickListener(v -> finish());

        root.addView(card, centeredCardParams());
        setContentView(root);
    }

    private void connectAccount() {
        String username = usernameInput == null ? "" : usernameInput.getText().toString().trim();
        String password = passwordInput == null ? "" : passwordInput.getText().toString();
        if (username.isEmpty() || password.isEmpty()) {
            showLogin("Login va parolni kiriting.");
            return;
        }
        connectButton.setEnabled(false);
        connectButton.setAlpha(0.65f);
        connectButton.setText("WASYT BILAN BOG‘LANMOQDA...");
        passwordInput.setText("");

        network.execute(() -> {
            try {
                WasytApiClient.LoginResult result =
                        WasytApiClient.loginAndReadProfile(username, password);
                WasytSecureStore.save(getApplicationContext(), username,
                        result.token, result.account);
                handler.post(() -> {
                    if (!isFinishing()) showConnected();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "WASYT bilan ulanishda xatolik yuz berdi." : error.getMessage();
                handler.post(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    showLogin(message);
                });
            }
        });
    }

    private void showConnected() {
        String token = WasytSecureStore.getValidToken(this);
        if (token == null) {
            showLogin("WASYT tokenining muddati tugagan. Qayta kiring.");
            return;
        }
        root = createRoot();
        LinearLayout card = createCard();
        addText(card, "AKSO  /  WASYT INTEGRATSIYA", 11,
                Color.rgb(219, 255, 234), true, Gravity.START);
        addSpace(card, 18);
        addText(card, "✓  WASYT ULANDI", 19,
                Color.rgb(119, 255, 177), true, Gravity.CENTER);
        addSpace(card, 12);
        addText(card, "Login: " + safe(WasytSecureStore.getUsername(this)),
                12, Color.WHITE, true, Gravity.CENTER);
        addSpace(card, 6);
        addText(card, "Kompaniya: " + safe(WasytSecureStore.getCompanyName(this)),
                11, Color.rgb(226, 244, 232), false, Gravity.CENTER);
        addSpace(card, 6);
        addText(card, "Kompaniya ID: " + WasytSecureStore.getCompanyId(this),
                10, Color.rgb(226, 244, 232), false, Gravity.CENTER);
        addSpace(card, 6);
        String expires = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(new Date(WasytSecureStore.getExpiresAt(this)));
        addText(card, "Token amal qilish muddati: " + expires,
                9, Color.rgb(226, 244, 232), false, Gravity.CENTER);
        addSpace(card, 16);
        addText(card,
                "Ulanish faqat shu telefonda saqlanadi. WASYT paroli saqlanmagan va Firebase bulutiga yuborilmaydi.",
                10, Color.rgb(221, 244, 230), false, Gravity.CENTER);
        addSpace(card, 18);

        TextView disconnect = makeButton("WASYT AKKAUNTINI UZISH", false);
        card.addView(disconnect, fullWidth(dp(44)));
        disconnect.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("WASYT akkauntini uzish")
                    .setMessage("Ushbu telefondagi shifrlangan token o‘chiriladi. WASYT akkauntingiz boshqa qurilmalarda faol qoladi.")
                    .setNegativeButton("Bekor qilish", (dialog, which) -> dialog.dismiss())
                    .setPositiveButton("Uzish", (dialog, which) -> {
                        WasytSecureStore.clearToken(this);
                        showLogin("WASYT akkaunti ushbu telefondan uzildi.");
                    }).show();
        });
        addSpace(card, 8);
        TextView close = makeButton("YOPISH", true);
        card.addView(close, fullWidth(dp(42)));
        close.setOnClickListener(v -> finish());
        root.addView(card, centeredCardParams());
        setContentView(root);
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
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
        view.setTypeface(android.graphics.Typeface.DEFAULT,
                bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        view.setGravity(gravity);
        view.setLineSpacing(dp(3), 1f);
        parent.addView(view, fullWidth(-2));
        return view;
    }

    private EditText makeInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setTextSize(13);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xBFE7F5EC);
        input.setPadding(dp(13), 0, dp(13), 0);
        GradientDrawable bg = new GradientDrawable();
        // 0xAARRGGBB must contain exactly eight hexadecimal digits.
        bg.setColor(0x553F5F4F);
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), 0x99FFFFFF);
        input.setBackground(bg);
        return input;
    }

    private TextView makeButton(String label, boolean primary) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(11);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                primary ? new int[]{0xFF20C878, 0xFF078B4B}
                        : new int[]{0xB83A5747, 0xA91C3328});
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), primary ? 0xAAE8FFF1 : 0x99FFFFFF);
        button.setBackground(bg);
        return button;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        android.view.View space = new android.view.View(this);
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
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }
}
