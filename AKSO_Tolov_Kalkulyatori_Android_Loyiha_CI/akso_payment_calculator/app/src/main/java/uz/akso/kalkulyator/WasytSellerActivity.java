package uz.akso.kalkulyator;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated WASYT seller workflow. The installment calculator, wallet and
 * Firebase synchronization are intentionally not changed by this screen.
 */
public class WasytSellerActivity extends Activity {
    private static final int BG = 0xFF0C2118;
    private static final int CARD = 0xE52B4B3A;
    private static final int GREEN = 0xFF0BB34B;
    private static final int LIGHT_GREEN = 0xFF78E4A8;
    private static final int WHITE = 0xFFF1F8F3;
    private static final int MUTED = 0xFFB4C8BA;
    private static final int RED = 0xFFE36A65;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<WasytBarcodeScanner.ProductInfo> searchResults = new ArrayList<>();
    private final List<CartItem> cartItems = new ArrayList<>();

    private LinearLayout page;
    private LinearLayout resultsPanel;
    private LinearLayout cartPanel;
    private EditText productQuery;
    private EditText clientQuery;
    private EditText discountInput;
    private TextView selectedClientView;
    private TextView totalView;
    private TextView statusView;
    private TextView reserveButton;
    private TextView discountModeButton;
    private WasytSellerApi.Client selectedClient;
    private boolean fixedDiscount = false;
    private boolean reserveStock = true;
    private long pendingCartId = -1L;
    private String pendingCartCode = "";

    private static final class CartItem {
        final WasytBarcodeScanner.ProductInfo product;
        int quantity;
        CartItem(WasytBarcodeScanner.ProductInfo product) {
            this.product = product;
            this.quantity = 1;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (WasytSecureStore.getValidToken(this) == null
                || WasytSecureStore.getCompanyId(this) <= 0) {
            new AlertDialog.Builder(this)
                    .setTitle("WASYT akkaunti ulanmagan")
                    .setMessage("Sotuvchi modulini ochishdan oldin WASYT akkauntini ulang.")
                    .setPositiveButton("Akkauntni ulash", (dialog, which) ->
                            startActivity(new android.content.Intent(this, WasytAccountActivity.class)))
                    .setNegativeButton("Yopish", (dialog, which) -> finish())
                    .show();
            return;
        }
        buildScreen();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(15), dp(14), dp(15), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(12), dp(12));
        header.setBackground(rounded(0xFF087A43, 0xFF123B2A, 22, 0x99FFFFFF));
        page.addView(header, matchWrap());

        TextView back = button("‹", false);
        back.setTextSize(23);
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        back.setOnClickListener(v -> finish());

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(10);
        header.addView(titleBox, titleParams);
        titleBox.addView(label("AKSO  /  WASYT", 9, 0xFFD9F8E5, true), matchWrap());
        titleBox.addView(label("Sotuvchi moduli", 17, Color.WHITE, true), matchWrap());

        TextView history = button("SAVATLAR", false);
        history.setTextSize(9);
        header.addView(history, new LinearLayout.LayoutParams(dp(88), dp(40)));
        history.setOnClickListener(v -> showCartHistory());

        addSpace(page, 12);
        addSectionTitle("MAHSULOT QIDIRISH");
        LinearLayout searchCard = card();
        page.addView(searchCard, matchWrap());
        productQuery = input("Mahsulot nomi yoki shtrix-kod", InputType.TYPE_CLASS_TEXT);
        searchCard.addView(productQuery, matchWidth(dp(48)));
        addSpace(searchCard, 8);

        LinearLayout searchButtons = new LinearLayout(this);
        searchButtons.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.addView(searchButtons, matchWrap());
        TextView search = button("QIDIRISH", true);
        searchButtons.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1f));
        search.setOnClickListener(v -> searchProducts());
        TextView scan = button("▦  SKANER", false);
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        scanParams.leftMargin = dp(8);
        searchButtons.addView(scan, scanParams);
        scan.setOnClickListener(v -> startBarcodeScan());

        resultsPanel = new LinearLayout(this);
        resultsPanel.setOrientation(LinearLayout.VERTICAL);
        addSpace(page, 8);
        page.addView(resultsPanel, matchWrap());

        addSpace(page, 14);
        addSectionTitle("SAVAT");
        cartPanel = new LinearLayout(this);
        cartPanel.setOrientation(LinearLayout.VERTICAL);
        page.addView(cartPanel, matchWrap());
        renderCart();

        addSpace(page, 14);
        addSectionTitle("MIJOZ");
        LinearLayout clientCard = card();
        page.addView(clientCard, matchWrap());
        LinearLayout clientSearchRow = new LinearLayout(this);
        clientSearchRow.setOrientation(LinearLayout.HORIZONTAL);
        clientCard.addView(clientSearchRow, matchWrap());
        clientQuery = input("Mijoz ismi yoki telefoni", InputType.TYPE_CLASS_TEXT);
        clientSearchRow.addView(clientQuery, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView findClient = button("TOPISH", false);
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(dp(86), dp(46));
        findParams.leftMargin = dp(7);
        clientSearchRow.addView(findClient, findParams);
        findClient.setOnClickListener(v -> searchClients());
        TextView newClient = button("+ YANGI MIJOZ", false);
        LinearLayout.LayoutParams newParams = matchWidth(dp(42));
        newParams.topMargin = dp(8);
        clientCard.addView(newClient, newParams);
        newClient.setOnClickListener(v -> showNewClientDialog());

        selectedClientView = label("Mijoz tanlanmagan", 12, MUTED, true);
        selectedClientView.setPadding(dp(3), dp(12), dp(3), dp(3));
        clientCard.addView(selectedClientView, matchWrap());

        addSpace(page, 14);
        addSectionTitle("CHEGIRMA VA BRON");
        LinearLayout options = card();
        page.addView(options, matchWrap());

        LinearLayout discountHeader = new LinearLayout(this);
        discountHeader.setGravity(Gravity.CENTER_VERTICAL);
        options.addView(discountHeader, matchWrap());
        discountModeButton = button("FOIZ %", false);
        discountHeader.addView(discountModeButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        discountModeButton.setOnClickListener(v -> {
            fixedDiscount = !fixedDiscount;
            discountModeButton.setText(fixedDiscount ? "ANIQ SUMMA" : "FOIZ %");
            discountInput.setHint(fixedDiscount ? "Chegirma summasi (so'm)" : "Chegirma foizi");
            updateTotal();
        });
        discountInput = input("Chegirma foizi", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams discountParams = matchWidth(dp(46));
        discountParams.topMargin = dp(8);
        options.addView(discountInput, discountParams);
        discountInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateTotal(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });

        reserveButton = button("✓  OMBORDAN BRON QILISH  ·  YOQILGAN", false);
        LinearLayout.LayoutParams reserveParams = matchWidth(dp(44));
        reserveParams.topMargin = dp(8);
        options.addView(reserveButton, reserveParams);
        reserveButton.setOnClickListener(v -> {
            reserveStock = !reserveStock;
            updateReserveLabel();
        });

        addSpace(page, 14);
        LinearLayout totalCard = card();
        page.addView(totalCard, matchWrap());
        totalView = label("JAMI: 0 so'm", 21, LIGHT_GREEN, true);
        totalView.setGravity(Gravity.END);
        totalCard.addView(totalView, matchWrap());

        statusView = label("", 10, MUTED, false);
        statusView.setPadding(dp(3), dp(8), dp(3), dp(3));
        page.addView(statusView, matchWrap());

        TextView checkout = button("KASSAGA YUBORISH  ›", true);
        checkout.setTextSize(14);
        LinearLayout.LayoutParams checkoutParams = matchWidth(dp(54));
        checkoutParams.topMargin = dp(10);
        page.addView(checkout, checkoutParams);
        checkout.setOnClickListener(v -> confirmCheckout());

        setContentView(scroll);
        updateReserveLabel();
        updateTotal();
    }

    private void addSectionTitle(String value) {
        TextView title = label(value, 10, MUTED, true);
        title.setLetterSpacing(0.08f);
        title.setPadding(dp(2), 0, 0, dp(6));
        page.addView(title, matchWrap());
    }

    private void searchProducts() {
        String query = productQuery.getText().toString().trim();
        if (query.length() < 2) {
            showStatus("Kamida 2 ta belgi kiriting.", RED);
            return;
        }
        String token = WasytSecureStore.getValidToken(this);
        showStatus("WASYT katalogi qidirilmoqda…", MUTED);
        runNetwork("Mahsulot qidirilmoqda…", () -> {
            List<WasytBarcodeScanner.ProductInfo> found =
                    WasytBarcodeScanner.searchByText(token, query);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                searchResults.clear();
                searchResults.addAll(found);
                renderSearchResults();
                showStatus(found.size() + " ta mahsulot topildi.", LIGHT_GREEN);
            });
        });
    }

    private void startBarcodeScan() {
        WasytBarcodeScanner.start(this, new WasytBarcodeScanner.Listener() {
            @Override public void onScanStarted() {
                Toast.makeText(WasytSellerActivity.this,
                        "Kamerani mahsulot shtrix-kodiga qarating.", Toast.LENGTH_SHORT).show();
            }
            @Override public void onLookupStarted(String barcode) {
                showStatus("Shtrix-kod qidirilmoqda: " + barcode, MUTED);
            }
            @Override public void onProductsFound(String barcode,
                    List<WasytBarcodeScanner.ProductInfo> products) {
                searchResults.clear();
                searchResults.addAll(products);
                renderSearchResults();
                showStatus(products.size() + " ta mos mahsulot topildi.", LIGHT_GREEN);
            }
            @Override public void onCancelled() { }
            @Override public void onError(String message) { showError("WASYT skaneri", message); }
        });
    }

    private void renderSearchResults() {
        resultsPanel.removeAllViews();
        if (searchResults.isEmpty()) return;
        LinearLayout panel = card();
        resultsPanel.addView(panel, matchWrap());
        for (WasytBarcodeScanner.ProductInfo product : searchResults) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            panel.addView(row, matchWrap());

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            row.addView(details, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            details.addView(label(product.name, 12, WHITE, true), matchWrap());
            String info = (product.barcode.isEmpty() ? "Shtrix-kod yo'q" : product.barcode)
                    + (product.stockQuantity == null || product.stockQuantity.isEmpty()
                    ? "" : "  ·  Qoldiq: " + product.stockQuantity);
            details.addView(label(info, 9, MUTED, false), matchWrap());
            details.addView(label(formatMoney(product.priceAmount) + " so'm", 12,
                    LIGHT_GREEN, true), matchWrap());

            TextView add = button("+", true);
            row.addView(add, new LinearLayout.LayoutParams(dp(42), dp(42)));
            add.setOnClickListener(v -> addToCart(product));
            View divider = new View(this);
            divider.setBackgroundColor(0x33FFFFFF);
            panel.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private void addToCart(WasytBarcodeScanner.ProductInfo product) {
        if (product == null || product.id <= 0) return;
        if (!product.hasUzsPrice()) {
            showError("Mahsulot narxi", "Mahsulotning UZS sotuv narxi ko'rsatilmagan. Uni savatga qo'shib bo'lmaydi.");
            return;
        }
        for (CartItem item : cartItems) {
            if (item.product.id == product.id) {
                item.quantity++;
                renderCart();
                showStatus("Mahsulot miqdori oshirildi.", LIGHT_GREEN);
                return;
            }
        }
        cartItems.add(new CartItem(product));
        renderCart();
        showStatus("Mahsulot savatga qo'shildi.", LIGHT_GREEN);
    }

    private void renderCart() {
        if (cartPanel == null) return;
        cartPanel.removeAllViews();
        if (cartItems.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("Savat hozircha bo'sh. Mahsulotni nomi yoki shtrix-kod bilan qidiring.",
                    11, MUTED, false), matchWrap());
            cartPanel.addView(empty, matchWrap());
            updateTotal();
            return;
        }
        LinearLayout box = card();
        cartPanel.addView(box, matchWrap());
        for (int i = 0; i < cartItems.size(); i++) {
            final CartItem item = cartItems.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(6));
            box.addView(row, matchWrap());

            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            row.addView(details, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            details.addView(label(item.product.name, 11, WHITE, true), matchWrap());
            details.addView(label(formatMoney(item.product.priceAmount) + " × "
                    + item.quantity + " = "
                    + formatMoney(safeMultiply(item.product.priceAmount, item.quantity))
                    + " so'm", 10, LIGHT_GREEN, true), matchWrap());

            TextView minus = button("−", false);
            row.addView(minus, new LinearLayout.LayoutParams(dp(36), dp(36)));
            minus.setOnClickListener(v -> {
                if (item.quantity > 1) item.quantity--;
                else cartItems.remove(item);
                renderCart();
            });
            TextView quantity = label(String.valueOf(item.quantity), 13, WHITE, true);
            quantity.setGravity(Gravity.CENTER);
            row.addView(quantity, new LinearLayout.LayoutParams(dp(30), dp(36)));
            TextView plus = button("+", false);
            row.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
            plus.setOnClickListener(v -> {
                if (item.quantity < 999) item.quantity++;
                renderCart();
            });
        }
        TextView clear = button("SAVATNI TOZALASH", false);
        LinearLayout.LayoutParams clearParams = matchWidth(dp(38));
        clearParams.topMargin = dp(6);
        box.addView(clear, clearParams);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Savat tozalansinmi?")
                .setMessage("Savatdagi barcha mahsulotlar olib tashlanadi.")
                .setNegativeButton("Yo'q", null)
                .setPositiveButton("Tozalash", (dialog, which) -> {
                    cartItems.clear();
                    pendingCartId = -1L;
                    pendingCartCode = "";
                    renderCart();
                }).show());
        updateTotal();
    }

    private void searchClients() {
        String query = clientQuery.getText().toString().trim();
        if (query.isEmpty()) {
            showStatus("Mijoz ismi yoki telefonini kiriting.", RED);
            return;
        }
        String token = WasytSecureStore.getValidToken(this);
        runNetwork("Mijoz qidirilmoqda…", () -> {
            List<WasytSellerApi.Client> clients = WasytSellerApi.searchClients(token, query);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (clients.isEmpty()) {
                    showStatus("Mijoz topilmadi. Yangi mijoz yaratishingiz mumkin.", MUTED);
                    return;
                }
                String[] labels = new String[clients.size()];
                for (int i = 0; i < clients.size(); i++) labels[i] = clients.get(i).toString();
                new AlertDialog.Builder(this)
                        .setTitle("Mijozni tanlang")
                        .setItems(labels, (dialog, which) -> selectClient(clients.get(which)))
                        .setNegativeButton("Yopish", null)
                        .show();
            });
        });
    }

    private void selectClient(WasytSellerApi.Client client) {
        selectedClient = client;
        selectedClientView.setText("✓  " + client.fullName + "  ·  " + client.phone);
        selectedClientView.setTextColor(LIGHT_GREEN);
        showStatus("Mijoz tanlandi.", LIGHT_GREEN);
    }

    private void showNewClientDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(4));
        EditText name = input("Mijoz F.I.Sh.", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText phone = input("Telefon raqami: 998...", InputType.TYPE_CLASS_PHONE);
        form.addView(name, matchWidth(dp(48)));
        addSpace(form, 8);
        form.addView(phone, matchWidth(dp(48)));
        addSpace(form, 8);
        RadioGroup genderGroup = new RadioGroup(this);
        genderGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton male = new RadioButton(this);
        male.setText("Erkak");
        male.setTextColor(WHITE);
        male.setChecked(true);
        RadioButton female = new RadioButton(this);
        female.setText("Ayol");
        female.setTextColor(WHITE);
        genderGroup.addView(male);
        genderGroup.addView(female);
        form.addView(genderGroup, matchWrap());

        new AlertDialog.Builder(this)
                .setTitle("Yangi mijoz")
                .setView(form)
                .setNegativeButton("Bekor qilish", null)
                .setPositiveButton("Saqlash", (dialog, which) -> {
                    String fullName = name.getText().toString().trim();
                    String mobile = phone.getText().toString().trim();
                    String gender = female.isChecked() ? "female" : "male";
                    String token = WasytSecureStore.getValidToken(this);
                    runNetwork("Mijoz yaratilmoqda…", () -> {
                        WasytSellerApi.Client client = WasytSellerApi.createClient(
                                token, fullName, mobile, gender, "uz");
                        main.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            selectClient(client);
                            clientQuery.setText(client.fullName);
                            Toast.makeText(this, "Mijoz WASYT bazasiga qo'shildi.",
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                }).show();
    }

    private void confirmCheckout() {
        if (cartItems.isEmpty()) {
            showStatus("Savat bo'sh.", RED);
            return;
        }
        if (selectedClient == null) {
            showStatus("Kassaga yuborishdan oldin mijozni tanlang.", RED);
            return;
        }
        long value;
        try {
            value = parseDiscountValue();
        } catch (Exception error) {
            showStatus(error.getMessage(), RED);
            return;
        }
        long subtotal = calculateSubtotal();
        if (fixedDiscount && value > subtotal) {
            showStatus("Chegirma savat summasidan katta bo'lmasligi kerak.", RED);
            return;
        }
        if (!fixedDiscount && value > 100) {
            showStatus("Foizli chegirma 100% dan oshmasligi kerak.", RED);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Savat kassaga yuborilsinmi?")
                .setMessage("Mijoz: " + selectedClient.fullName
                        + "\nMahsulotlar: " + cartItems.size()
                        + "\nJami: " + formatMoney(calculateTotalAfterDiscount()) + " so'm"
                        + "\nOmbordan bron: " + (reserveStock ? "Ha" : "Yo'q"))
                .setNegativeButton("Tekshirish", null)
                .setPositiveButton("Kassaga yuborish", (dialog, which) -> checkout())
                .show();
    }

    private void checkout() {
        final String token = WasytSecureStore.getValidToken(this);
        final long companyId = WasytSecureStore.getCompanyId(this);
        final long stockId = WasytSellerApi.DEFAULT_STOCK_ID;
        final long clientId = selectedClient.id;
        final long discount;
        final String checkoutClientName = selectedClient.fullName;
        try {
            discount = parseDiscountValue();
        } catch (Exception error) {
            showStatus(error.getMessage(), RED);
            return;
        }

        final ArrayList<WasytSellerApi.CartLine> lines = new ArrayList<>();
        for (CartItem item : cartItems) {
            lines.add(new WasytSellerApi.CartLine(item.product.id,
                    item.quantity, item.product.priceAmount));
        }
        runNetwork("WASYT savati kassaga yuborilmoqda…", () -> {
            long cartId = pendingCartId;
            String code = pendingCartCode;
            if (cartId <= 0) {
                JSONObject created = WasytSellerApi.createCart(token, companyId,
                        stockId, reserveStock, lines);
                JSONObject data = created.optJSONObject("data");
                if (data == null) data = created;
                cartId = data.optLong("id", -1L);
                code = data.optString("code", "");
                if (cartId <= 0) {
                    throw new java.io.IOException("WASYT savat yaratdi, ammo savat ID qaytmadi.");
                }
                final long savedId = cartId;
                final String savedCode = code;
                main.post(() -> {
                    pendingCartId = savedId;
                    pendingCartCode = savedCode;
                    showStatus("Savat WASYTda yaratildi. Kassaga yuborish davom etmoqda…", MUTED);
                });
            }

            WasytSellerApi.updateCart(token, cartId, companyId, stockId,
                    clientId, reserveStock, lines, fixedDiscount, discount);
            JSONObject sent = WasytSellerApi.sendCart(token, cartId);
            JSONObject sentData = sent.optJSONObject("data");
            if (sentData == null) sentData = sent;
            String sentCode = sentData.optString("code", code);
            String finalCode = sentCode;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                cartItems.clear();
                selectedClient = null;
                selectedClientView.setText("Mijoz tanlanmagan");
                selectedClientView.setTextColor(MUTED);
                clientQuery.setText("");
                discountInput.setText("");
                pendingCartId = -1L;
                pendingCartCode = "";
                renderCart();
                showStatus("✓ Savat " + (finalCode.isEmpty() ? "" : "#" + finalCode + " ")
                        + "kassaga yuborildi.", LIGHT_GREEN);
                new AlertDialog.Builder(this)
                        .setTitle("Kassaga yuborildi")
                        .setMessage("Mijozni kassaga yo'llang va navbat raqamini ayting.\n\n"
                                + "Savat: " + (finalCode.isEmpty() ? "tayyor" : finalCode)
                                + "\nMijoz: " + checkoutClientName)
                        .setPositiveButton("Yangi savat", null)
                        .show();
            });
        });
    }

    private String selectedClientNameSafe(long clientId) {
        return "Mijoz ID: " + clientId;
    }

    private void showCartHistory() {
        String token = WasytSecureStore.getValidToken(this);
        runNetwork("Savatlar yuklanmoqda…", () -> {
            List<WasytSellerApi.Cart> carts = WasytSellerApi.listCarts(token, 1);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (carts.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle("Savatlar tarixi")
                            .setMessage("Hozircha savatlar topilmadi.")
                            .setPositiveButton("Yopish", null).show();
                    return;
                }
                String[] labels = new String[carts.size()];
                for (int i = 0; i < carts.size(); i++) {
                    WasytSellerApi.Cart cart = carts.get(i);
                    labels[i] = "#" + cart.code + "  ·  " + cart.clientName
                            + "\n" + formatMoney(cart.totalAmount) + " so'm  ·  " + cart.status;
                }
                new AlertDialog.Builder(this).setTitle("Mening savatlarim")
                        .setItems(labels, (dialog, which) -> showCartActions(carts.get(which)))
                        .setPositiveButton("Yopish", null).show();
            });
        });
    }

    private void showCartActions(WasytSellerApi.Cart cart) {
        String status = cart.status.toLowerCase(Locale.ROOT);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Savat #" + cart.code)
                .setMessage("Mijoz: " + cart.clientName
                        + "\nHolati: " + cart.status
                        + "\nMahsulotlar summasi: " + formatMoney(cart.amount)
                        + "\nChegirma: " + formatMoney(cart.discountAmount)
                        + "\nJami: " + formatMoney(cart.totalAmount) + " so'm")
                .setPositiveButton("Yopish", null);
        if ("picking".equals(status) || "reserved".equals(status)) {
            builder.setNeutralButton("Savatni bekor qilish", (dialog, which) ->
                    confirmCancelCart(cart));
        }
        builder.show();
    }

    private void confirmCancelCart(WasytSellerApi.Cart cart) {
        new AlertDialog.Builder(this)
                .setTitle("Savat bekor qilinsinmi?")
                .setMessage("#" + cart.code + " savati bekor qilinadi. Tovarlar sotuvga qaytishi mumkin.")
                .setNegativeButton("Ortga", null)
                .setPositiveButton("Bekor qilish", (dialog, which) -> {
                    String token = WasytSecureStore.getValidToken(this);
                    runNetwork("Savat bekor qilinmoqda…", () ->
                            WasytSellerApi.cancelCart(token, cart.id));
                    showStatus("Savatni bekor qilish so'rovi yuborildi.", MUTED);
                }).show();
    }

    private void runNetwork(String message, NetworkTask task) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("WASYT");
        progress.setMessage(message);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        if (!isFinishing() && !isDestroyed()) progress.show();

        network.execute(() -> {
            try {
                task.run();
            } catch (Exception error) {
                String detail = error.getMessage() == null
                        ? "WASYT bilan ishlashda xatolik yuz berdi." : error.getMessage();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showError("WASYT sotuvchi moduli", detail);
                });
            } finally {
                main.post(() -> {
                    if (progress.isShowing()) {
                        try { progress.dismiss(); } catch (RuntimeException ignored) { }
                    }
                });
            }
        });
    }

    private interface NetworkTask { void run() throws Exception; }

    private long parseDiscountValue() throws Exception {
        String raw = discountInput == null ? "" : discountInput.getText().toString().trim();
        if (raw.isEmpty()) return 0L;
        BigDecimal value = new BigDecimal(raw.replace(" ", "").replace(",", "."));
        if (value.signum() < 0) throw new Exception("Chegirma manfiy bo'lmasligi kerak.");
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private long calculateSubtotal() {
        long sum = 0L;
        for (CartItem item : cartItems) {
            sum = safeAdd(sum, safeMultiply(item.product.priceAmount, item.quantity));
        }
        return sum;
    }

    private long calculateTotalAfterDiscount() {
        long subtotal = calculateSubtotal();
        long discount = 0L;
        try {
            long value = parseDiscountValue();
            if (fixedDiscount) discount = Math.min(value, subtotal);
            else discount = BigDecimal.valueOf(subtotal)
                    .multiply(BigDecimal.valueOf(Math.min(100L, value)))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
        } catch (Exception ignored) { }
        return Math.max(0L, subtotal - discount);
    }

    private void updateTotal() {
        if (totalView != null) totalView.setText("JAMI: "
                + formatMoney(calculateTotalAfterDiscount()) + " so'm");
    }

    private void updateReserveLabel() {
        if (reserveButton != null) {
            reserveButton.setText((reserveStock ? "✓" : "○")
                    + "  OMBORDAN BRON QILISH  ·  "
                    + (reserveStock ? "YOQILGAN" : "O'CHIRILGAN"));
        }
    }

    private void showStatus(String message, int color) {
        if (statusView != null) {
            statusView.setText(message == null ? "" : message);
            statusView.setTextColor(color);
        }
    }

    private void showError(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Tushunarli", null).show();
    }

    private String formatMoney(long value) {
        return String.format(Locale.US, "%,d", Math.max(0L, value)).replace(',', ' ');
    }

    private static long safeMultiply(long a, long b) {
        try { return Math.multiplyExact(a, b); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private static long safeAdd(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(11), dp(12), dp(11));
        panel.setBackground(rounded(CARD, 0xB91E3428, 17, 0x66FFFFFF));
        return panel;
    }

    private GradientDrawable rounded(int top, int bottom, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private EditText input(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setTextSize(12);
        edit.setTextColor(WHITE);
        edit.setHintTextColor(0xFF9BB2A2);
        edit.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA12281D);
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), 0x88FFFFFF);
        edit.setBackground(bg);
        return edit;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView button(String value, boolean primary) {
        TextView view = label(value, 11, WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackground(primary
                ? rounded(0xFF1CCB7A, 0xFF078747, 14, 0xAAE8FFF1)
                : rounded(0xB83A5C48, 0xA91C3328, 14, 0x88FFFFFF));
        return view;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }
}