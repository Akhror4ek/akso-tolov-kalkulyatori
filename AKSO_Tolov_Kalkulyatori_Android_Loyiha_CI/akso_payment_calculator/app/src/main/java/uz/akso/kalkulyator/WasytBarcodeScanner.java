package uz.akso.kalkulyator;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Code Scanner + read-only WASYT product lookup.
 * Does not store product credentials or send them to Firebase.
 */
final class WasytBarcodeScanner {
    private static final String BASE_URL = "https://api.wasyt.uz";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WasytBarcodeScanner() { }

    interface Listener {
        void onScanStarted();
        void onLookupStarted(String barcode);
        void onProductsFound(String barcode, List<ProductInfo> products);
        void onCancelled();
        void onError(String message);
    }

    static final class ProductInfo {
        final long id;
        final String name;
        final String barcode;
        final long priceUzs;
        final String stockQuantity;

        ProductInfo(long id, String name, String barcode, long priceUzs,
                    String stockQuantity) {
            this.id = id;
            this.name = name;
            this.barcode = barcode;
            this.priceUzs = priceUzs;
            this.stockQuantity = stockQuantity;
        }
    }

    static void start(Activity activity, Listener listener) {
        if (activity == null || listener == null) return;

        String token = WasytSecureStore.getValidToken(activity);
        if (token == null) {
            listener.onError("WASYT akkaunti ulanmagan yoki token muddati tugagan. Qayta ulang.");
            return;
        }

        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_ITF,
                        Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();

        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(activity, options);
        listener.onScanStarted();

        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    String rawValue = barcode == null ? null : barcode.getRawValue();
                    if (rawValue == null || rawValue.trim().isEmpty()) {
                        listener.onError("Shtrix-kod o‘qilmadi. Qayta skanerlang.");
                        return;
                    }

                    String code = rawValue.trim();
                    listener.onLookupStarted(code);

                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(() -> {
                        try {
                            List<ProductInfo> products = searchProducts(token, code);
                            MAIN.post(() -> {
                                if (!activity.isFinishing() && !activity.isDestroyed()) {
                                    listener.onProductsFound(code, products);
                                }
                            });
                        } catch (Exception error) {
                            String message = error.getMessage() == null
                                    ? "WASYT bazasidan mahsulot qidirishda xatolik."
                                    : error.getMessage();
                            MAIN.post(() -> {
                                if (!activity.isFinishing() && !activity.isDestroyed()) {
                                    listener.onError(message);
                                }
                            });
                        } finally {
                            executor.shutdown();
                        }
                    });
                })
                .addOnCanceledListener(listener::onCancelled)
                .addOnFailureListener(error -> {
                    String message = error.getMessage() == null
                            ? "Skaner ishga tushmadi. Google Play xizmatlarini tekshiring."
                            : "Skaner ishga tushmadi: " + shortText(error.getMessage());
                    listener.onError(message);
                });
    }

    private static List<ProductInfo> searchProducts(String token, String barcode)
            throws IOException {
        String encoded = encode(barcode);
        String path = "/company/product/product?filter[barcode][0]=" + encoded
                + "&with[]=price&with[]=stock_quantity&page-size=20";

        JSONObject response = request(path, token);
        JSONArray rows = response.optJSONArray("data");
        if (rows == null) {
            JSONObject nested = response.optJSONObject("data");
            if (nested != null) rows = nested.optJSONArray("data");
        }

        ArrayList<ProductInfo> results = new ArrayList<>();
        if (rows == null) {
            throw new IOException("WASYT javobi kutilgan formatda emas.");
        }

        for (int i = 0; i < rows.length(); i++) {
            JSONObject product = rows.optJSONObject(i);
            if (product == null || product.optBoolean("is_deleted", false)) continue;

            String mainBarcode = product.optString("barcode", "");
            JSONArray additional = product.optJSONArray("additional_barcodes");
            boolean exactMatch = barcode.equals(mainBarcode);
            if (!exactMatch && additional != null) {
                for (int j = 0; j < additional.length(); j++) {
                    if (barcode.equals(additional.optString(j, ""))) {
                        exactMatch = true;
                        break;
                    }
                }
            }
            // The API's barcode filter includes the primary and extra barcodes.
            // Keep the row if the server returned it even when the payload omits barcode.
            if (!exactMatch && !mainBarcode.isEmpty()) continue;

            long productId = product.optLong("id", -1L);
            String name = product.optString("name", "").trim();
            if (name.isEmpty()) name = product.optString("full_text", "").trim();
            if (name.isEmpty()) name = "Nomsiz mahsulot";

            long priceUzs = extractPriceUzs(product);
            if (priceUzs <= 0 && productId > 0) {
                priceUzs = lookupPriceUzs(token, productId);
            }

            String stock = extractStock(product);
            if (stock.isEmpty() && productId > 0) {
                stock = lookupStockQuantity(token, productId);
            }

            String resultBarcode = mainBarcode.isEmpty() ? barcode : mainBarcode;
            results.add(new ProductInfo(productId, name, resultBarcode, priceUzs, stock));
        }

        if (results.isEmpty()) {
            throw new IOException("WASYT bazasida " + barcode
                    + " shtrix-kodiga mos mahsulot topilmadi.");
        }
        return results;
    }

    private static long lookupPriceUzs(String token, long productId) {
        try {
            String path = "/company/product/price?filter[product_id]=" + productId
                    + "&sort[0]=-id&page-size=20";
            JSONObject response = request(path, token);
            JSONArray rows = response.optJSONArray("data");
            if (rows == null) return 0L;

            long best = 0L;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject price = rows.optJSONObject(i);
                if (price == null) continue;
                long uzs = readLong(price, "price_uzs");
                if (uzs <= 0) uzs = readLong(price, "effective_min_price_uzs");
                if (uzs <= 0 && "uzs".equalsIgnoreCase(price.optString("currency", ""))) {
                    uzs = readLong(price, "price");
                }
                if (uzs > 0) {
                    best = uzs;
                    break;
                }
            }
            return best;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String lookupStockQuantity(String token, long productId) {
        try {
            String path = "/company/stock/product/catalog?filter[product_id]="
                    + productId + "&with[]=available_quantity&page-size=20";
            JSONObject response = request(path, token);
            JSONArray rows = response.optJSONArray("data");
            if (rows == null) return "";
            BigDecimal total = BigDecimal.ZERO;
            boolean found = false;

            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Object quantity = row.has("available_quantity")
                        ? row.opt("available_quantity") : row.opt("quantity");
                BigDecimal value = toDecimal(quantity);
                if (value != null) {
                    total = total.add(value);
                    found = true;
                }
            }
            return found ? normalizeQuantity(total) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long extractPriceUzs(JSONObject product) {
        long direct = readLong(product, "price_uzs");
        if (direct > 0) return direct;

        JSONObject price = product.optJSONObject("price");
        if (price != null) {
            long uzs = readLong(price, "price_uzs");
            if (uzs <= 0) uzs = readLong(price, "effective_min_price_uzs");
            if (uzs > 0) return uzs;

            if ("uzs".equalsIgnoreCase(price.optString("currency", ""))) {
                return readLong(price, "price");
            }
        }
        return 0L;
    }

    private static String extractStock(JSONObject product) {
        Object value = product.has("stock_quantity")
                ? product.opt("stock_quantity") : product.opt("available_quantity");

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            value = object.has("available_quantity")
                    ? object.opt("available_quantity") : object.opt("quantity");
        }
        BigDecimal quantity = toDecimal(value);
        return quantity == null ? "" : normalizeQuantity(quantity);
    }

    private static long readLong(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return 0L;
        BigDecimal value = toDecimal(object.opt(key));
        if (value == null) return 0L;
        try {
            return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException ignored) {
            return 0L;
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        try {
            if (value instanceof Number) return new BigDecimal(value.toString());
            String raw = String.valueOf(value).trim().replace(" ", "").replace(",", ".");
            if (raw.isEmpty()) return null;
            return new BigDecimal(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeQuantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static JSONObject request(String path, String token) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BASE_URL + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization",
                    token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "uz");
            connection.setRequestProperty("User-Agent", "AKSO-Finance-Android/1.0");

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readLimited(stream);

            JSONObject json;
            try {
                json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception parseError) {
                throw new IOException("WASYT javobini o‘qib bo‘lmadi (HTTP "
                        + status + ").", parseError);
            }

            if (status == 401) {
                throw new IOException("WASYT tokenining muddati tugagan. WASYT akkauntini qayta ulang.");
            }
            if (status == 403) {
                throw new IOException("WASYT akkauntingizda mahsulotlarni ko‘rish huquqi yo‘q.");
            }
            if (status == 404) {
                throw new IOException("WASYT API mahsulot manzilini topmadi (HTTP 404).");
            }
            if (status == 429) {
                throw new IOException("WASYT so‘rovlari limiti oshdi. Birozdan keyin urinib ko‘ring.");
            }
            if (status < 200 || status >= 300) {
                String message = json.optString("message", "");
                if (message.trim().isEmpty()) {
                    message = "WASYT server xatoligi (HTTP " + status + ").";
                }
                throw new IOException(shortText(message));
            }
            return json;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("WASYT serveriga ulanib bo‘lmadi. Internetni tekshiring.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("WASYT javobi juda katta.");
                }
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private static String encode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            throw new IOException("Shtrix-kodni tayyorlab bo‘lmadi.", error);
        }
    }

    private static String shortText(String value) {
        if (value == null) return "Noma’lum xatolik.";
        String text = value.trim();
        return text.length() > 220 ? text.substring(0, 220) : text;
    }
}
