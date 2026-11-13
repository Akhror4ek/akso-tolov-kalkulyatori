package uz.akso.kalkulyator;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One source of truth for completed WASYT sales used by both AKSO calendars.
 * Loads pages sequentially to respect WASYT's request limits and applies the
 * same completion, local-cancellation and sale-date rules everywhere.
 */
final class WasytSalesRepository {
    private static final String CANCEL_PREFS = "wasyt_seller_cancel_reasons";

    private WasytSalesRepository() { }

    static List<WasytSellerApi.Cart> loadCompletedSales(Context context, String token)
            throws IOException {
        if (context == null) throw new IOException("Ilova konteksti mavjud emas.");
        WasytSellerApi.CartPage first = WasytSellerApi.listCartPage(token, 1);
        ArrayList<WasytSellerApi.Cart> completed = new ArrayList<>();
        addCompleted(context, first.carts, completed);

        int lastPage = Math.max(1, first.lastPage);
        for (int page = 2; page <= lastPage; page++) {
            WasytSellerApi.CartPage next = WasytSellerApi.listCartPage(token, page);
            addCompleted(context, next.carts, completed);
        }
        return completed;
    }

    private static void addCompleted(Context context, List<WasytSellerApi.Cart> source,
                                     List<WasytSellerApi.Cart> destination) {
        if (source == null) return;
        for (WasytSellerApi.Cart cart : source) {
            if (isCompletedSale(context, cart)) destination.add(cart);
        }
    }

    static boolean isCompletedSale(Context context, WasytSellerApi.Cart cart) {
        if (cart == null || isCancelled(context, cart)) return false;
        return isCompletedState(cart.status) || isCompletedState(cart.saleStatus);
    }

    private static boolean isCompletedState(String value) {
        String normalized = safeLower(value);
        return "completed".equals(normalized) || "done".equals(normalized)
                || "finished".equals(normalized) || "paid".equals(normalized)
                || "closed".equals(normalized) || "sold".equals(normalized);
    }

    private static boolean isCancelled(Context context, WasytSellerApi.Cart cart) {
        String status = safeLower(cart.status);
        String sale = safeLower(cart.saleStatus);
        String combined = status + " " + sale;
        if (combined.contains("cancel") || combined.contains("reject")
                || combined.contains("void")) return true;
        if (context == null || cart.id <= 0) return false;
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(CANCEL_PREFS, Context.MODE_PRIVATE);
        return preferences.getBoolean("cancelled_" + cart.id, false);
    }

    static Calendar getSaleDate(WasytSellerApi.Cart cart) {
        if (cart == null) return null;
        String[] candidates = {
                cart.completedAt, cart.updatedAt, cart.sentAt, cart.createdAt
        };
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()
                    || "null".equalsIgnoreCase(candidate.trim())) continue;
            for (String pattern : patterns) {
                try {
                    SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                    parser.setLenient(false);
                    Date parsed = parser.parse(candidate.trim());
                    if (parsed != null) {
                        Calendar date = Calendar.getInstance();
                        date.setTime(parsed);
                        return date;
                    }
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    static boolean isSameDate(Calendar first, Calendar second) {
        return first != null && second != null
                && first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    static BigDecimal getDailyTotal(List<WasytSellerApi.Cart> sales, Calendar date) {
        BigDecimal total = BigDecimal.ZERO;
        if (sales == null || date == null) return total;
        for (WasytSellerApi.Cart sale : sales) {
            Calendar saleDate = getSaleDate(sale);
            if (isSameDate(saleDate, date)) {
                total = total.add(BigDecimal.valueOf(Math.max(0L, sale.totalAmount)));
            }
        }
        return total;
    }

    static int getDailyCount(List<WasytSellerApi.Cart> sales, Calendar date) {
        int count = 0;
        if (sales == null || date == null) return count;
        for (WasytSellerApi.Cart sale : sales) {
            if (isSameDate(getSaleDate(sale), date)) count++;
        }
        return count;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
