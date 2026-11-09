package uz.akso.kalkulyator;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Code Scanner + read-only WASYT product lookup.
 * Uses the stock/catalog read endpoint rather than the product-admin list endpoint,
 * so seller accounts with catalog-search access can use the scanner.
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

    private static final class ProductAccumulator {
        final long id;
        final String name;
        final String barcode;
        long priceUzs;
        BigDecimal stockTotal = BigDecimal.ZERO;
        boolean hasStock;

        ProductAccumulator(long id, String name, String barcode, long priceUzs) {
            this.id = id;
            this.name = name;
            this.barcode = barcode;
            this.priceUzs = priceUzs;
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

    /**
     * Seller-facing WASYT catalogue endpoint. The previous implementation called
     * /company/product/product, which is the product-administration list route and
     * can return HTTP 403 for a seller who only has catalogue search permissions.
     */
    private static List<ProductInfo> searchProducts(String token, String barcode)
            throws IOException {
        String encoded = encode(barcode);
        String baseQuery = "&with[]=product&with[]=product.price&with[]=product_price"
                + "&with[]=available_quantity&page-size=20";

        // First try the exact product barcode filter documented for the stock catalogue.
        JSONObject response = request(
                "/company/stock/product/catalog?filter[product.barcode]="
                        + encoded + baseQuery, token);
        JSONArray rows = dataRows(response);

        // The common filter also searches the catalogue's general product identifiers.
        // It is useful for additional/industrial barcodes supported by WASYT search.
        if (rows == null || rows.length() == 0) {
            response = request("/company/stock/product/catalog?filter[common]="
                    + encoded + baseQuery, token);
            rows = dataRows(response);
        }

        if (rows == null || rows.length() == 0) {
            throw new IOException("WASYT katalogida " + barcode
                    + " shtrix-kodiga mos mahsulot topilmadi.");
        }

        Map<Long, ProductAccumulator> grouped = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;

            JSONObject product = row.optJSONObject("product");
            if (product == null) product = row;
            if (product.optBoolean("is_deleted", false)) continue;

            String mainBarcode = product.optString("barcode", "").trim();
            String industrialBarcode = product.optString("industrial_barcode", "").trim();
            String matchedBarcode = findMatchingBarcode(product, barcode);
            boolean hasBarcodeData = !mainBarcode.isEmpty() || !industrialBarcode.isEmpty()
                    || product.optJSONArray("additional_barcodes") != null;
            if (matchedBarcode == null && hasBarcodeData) continue;

            long productId = product.optLong("id", row.optLong("product_id", -1L));
            if (productId <= 0) continue;

            String name = product.optString("name", "").trim();
            if (name.isEmpty()) name = product.optString("full_text", "").trim();
            if (name.isEmpty()) name = "Nomsiz mahsulot";

            String resultBarcode = !mainBarcode.isEmpty() ? mainBarcode
                    : (!industrialBarcode.isEmpty() ? industrialBarcode : barcode);

            long priceUzs = extractCatalogPriceUzs(row, product);
            if (priceUzs <= 0) priceUzs = lookupPriceUzs(token, productId);

            ProductAccumulator item = grouped.get(productId);
            if (item == null) {
                item = new ProductAccumulator(productId, name, resultBarcode, priceUzs);
                grouped.put(productId, item);
            } else if (item.priceUzs <= 0 && priceUzs > 0) {
                item.priceUzs = priceUzs;
            }

            Object quantityValue = row.has("available_quantity")
                    ? row.opt("available_quantity") : row.opt("quantity");
            BigDecimal quantity = toDecimal(quantityValue);
            if (quantity != null) {
                item.stockTotal = item.stockTotal.add(quantity);
                item.hasStock = true;
            }
        }

        if (grouped.isEmpty()) {
            throw new IOException("WASYT katalogidan mahsulot topilmadi. Shtrix-kod: " + barcode);
        }

        ArrayList<ProductInfo> results = new ArrayList<>();
        for (ProductAccumulator item : grouped.values()) {
            String stock = item.hasStock ? normalizeQuantity(item.stockTotal) : "";
            results.add(new ProductInfo(item.id, item.name, item.barcode,
                    item.priceUzs, stock));
        }
        return results;
    }

    private static JSONArray dataRows(JSONObject response) {
        if (response == null) return null;
        JSONArray rows = response.optJSONArray("data");
        if (rows == null) {
            JSONObject nested = response.optJSONObject("data");
            if (nested != null) rows = nested.optJSONArray("data");
        }
        return rows;
    }

    private static String findMatchingBarcode(JSONObject product, String barcode) {
        String main = product.optString("barcode", "").trim();
        if (barcode.equals(main)) return main;

        String industrial = product.optString("industrial_barcode", "").trim();
        if (barcode.equals(industrial)) return industrial;

        JSONArray additional = product.optJSONArray("additional_barcodes");
        if (additional != null) {
            for (int i = 0; i < additional.length(); i++) {
                if (barcode.equals(additional.optString(i, "").trim())) return barcode;
            }
        }
        return null;
    }

    private static long extractCatalogPriceUzs(JSONObject row, JSONObject product) {
        long value = extractPriceUzs(row.optJSONObject("product_price"));
        if (value > 0) return value;
        value = extractPriceUzs(product.optJSONObject("price"));
        if (value > 0) return value;
        value = extractPriceUzs(row);
        if (value > 0) return value;
        return extractPriceUzs(product);
    }

    private static long lookupPriceUzs(String token, long productId) {
        try {
            String path = "/company/product/price?filter[product_id]=" + productId
                    + "&sort[0]=-id&page-size=20";
            JSONObject response = request(path, token);
            JSONArray rows = response.optJSONArray("data");
            if (rows == null) return 0L;

            for (int i = 0; i < rows.length(); i++) {
                JSONObject price = rows.optJSONObject(i);
                if (price == null) continue;
                long uzs = readLong(price, "price_uzs");
                if (uzs <= 0) uzs = readLong(price, "effective_min_price_uzs");
                if (uzs <= 0 && "uzs".equalsIgnoreCase(price.optString("currency", ""))) {
                    uzs = readLong(price, "price");
                }
                if (uzs > 0) return uzs;
            }
        } catch (Exception ignored) {
            // Price may already be embedded in the catalogue row, or separate price
            // permission may not be assigned. In that case show it as unavailable.
        }
        return 0L;
    }

    private static long extractPriceUzs(JSONObject object) {
        if (object == null) return 0L;
        long direct = readLong(object, "price_uzs");
        if (direct > 0) return direct;

        long minimum = readLong(object, "effective_min_price_uzs");
        if (minimum > 0) return minimum;

        long price = readLong(object, "price");
        if (price > 0 && "uzs".equalsIgnoreCase(object.optString("currency", ""))) {
            return price;
        }
        return 0L;
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
                String detail = json.optString("message", "").trim();
                if (detail.isEmpty()) {
                    throw new IOException("WASYT ushbu mahsulot katalogi so‘roviga ruxsat bermadi (HTTP 403). "
                            + "Sotuvchi rolida company.stock.product.catalog.index va narxni ko‘rish huquqini tekshiring.");
                }
                throw new IOException("WASYT ruxsat bermadi (HTTP 403): " + shortText(detail));
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
