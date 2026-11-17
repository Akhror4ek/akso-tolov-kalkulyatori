package uz.akso.kalkulyator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a seller-specific performance snapshot from completed WASYT carts.
 * Monetary totals always come from WASYT's server-calculated cart total.
 * Product rankings use the product rows returned with each completed cart.
 */
final class WasytSalesInsights {
    static final class Period {
        final BigDecimal total;
        final BigDecimal discountTotal;
        final int saleCount;

        Period(BigDecimal total, BigDecimal discountTotal, int saleCount) {
            this.total = total;
            this.discountTotal = discountTotal;
            this.saleCount = saleCount;
        }
    }

    static final class ProductStat {
        final long productId;
        final String name;
        final String unit;
        final String category;
        final String kind;
        final BigDecimal quantity;
        final int lineCount;

        ProductStat(long productId, String name, String unit, String category,
                    String kind, BigDecimal quantity, int lineCount) {
            this.productId = productId;
            this.name = safe(name);
            this.unit = safe(unit);
            this.category = safe(category);
            this.kind = safe(kind);
            this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
            this.lineCount = lineCount;
        }
    }

    static final class CategoryStat {
        final String name;
        final int lineCount;

        CategoryStat(String name, int lineCount) {
            this.name = safe(name);
            this.lineCount = lineCount;
        }
    }

    static final class Snapshot {
        final Period today;
        final Period week;
        final Period previousWeek;
        final Period month;
        final Period previousMonthComparable;
        final List<ProductStat> topProducts;
        final CategoryStat topCategory;
        final boolean hasProductDetails;
        final int productLineCount;
        final String productPeriodLabel;

        Snapshot(Period today, Period week, Period previousWeek,
                 Period month, Period previousMonthComparable,
                 List<ProductStat> topProducts, CategoryStat topCategory,
                 boolean hasProductDetails, int productLineCount,
                 String productPeriodLabel) {
            this.today = today;
            this.week = week;
            this.previousWeek = previousWeek;
            this.month = month;
            this.previousMonthComparable = previousMonthComparable;
            this.topProducts = topProducts;
            this.topCategory = topCategory;
            this.hasProductDetails = hasProductDetails;
            this.productLineCount = productLineCount;
            this.productPeriodLabel = safe(productPeriodLabel);
        }
    }

    private static final class MutablePeriod {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        int count;

        void add(WasytSellerApi.Cart cart) {
            total = total.add(BigDecimal.valueOf(Math.max(0L, cart.totalAmount)));
            discounts = discounts.add(BigDecimal.valueOf(Math.max(0L, cart.discountAmount)));
            count++;
        }

        Period freeze() {
            return new Period(total, discounts, count);
        }
    }

    private static final class MutableProduct {
        long id;
        String name;
        String unit;
        String category;
        String kind;
        BigDecimal quantity = BigDecimal.ZERO;
        int lineCount;

        ProductStat freeze() {
            return new ProductStat(id, name, unit, category, kind, quantity, lineCount);
        }
    }

    private WasytSalesInsights() { }

    static Snapshot build(List<WasytSellerApi.Cart> sales, Calendar referenceDate) {
        Calendar now = referenceDate == null
                ? Calendar.getInstance() : (Calendar) referenceDate.clone();
        Calendar todayStart = startOfDay(now);
        Calendar tomorrow = addDays(todayStart, 1);

        Calendar weekStart = (Calendar) todayStart.clone();
        int daysSinceMonday = (weekStart.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        weekStart.add(Calendar.DAY_OF_YEAR, -daysSinceMonday);
        Calendar nextWeek = addDays(weekStart, 7);
        Calendar previousWeekStart = addDays(weekStart, -7);

        Calendar monthStart = (Calendar) todayStart.clone();
        monthStart.set(Calendar.DAY_OF_MONTH, 1);
        Calendar nextMonth = (Calendar) monthStart.clone();
        nextMonth.add(Calendar.MONTH, 1);

        Calendar previousMonthStart = (Calendar) monthStart.clone();
        previousMonthStart.add(Calendar.MONTH, -1);
        Calendar previousMonthEnd = (Calendar) previousMonthStart.clone();
        int comparableDay = Math.min(todayStart.get(Calendar.DAY_OF_MONTH),
                previousMonthEnd.getActualMaximum(Calendar.DAY_OF_MONTH));
        previousMonthEnd.set(Calendar.DAY_OF_MONTH, comparableDay);
        previousMonthEnd = addDays(startOfDay(previousMonthEnd), 1);

        MutablePeriod today = new MutablePeriod();
        MutablePeriod week = new MutablePeriod();
        MutablePeriod previousWeek = new MutablePeriod();
        MutablePeriod month = new MutablePeriod();
        MutablePeriod previousMonth = new MutablePeriod();

        ArrayList<WasytSellerApi.Cart> monthSales = new ArrayList<>();
        ArrayList<WasytSellerApi.Cart> weekSales = new ArrayList<>();

        if (sales != null) {
            for (WasytSellerApi.Cart cart : sales) {
                if (cart == null) continue;
                Calendar saleDate = WasytSalesRepository.getSaleDate(cart);
                if (saleDate == null) continue;
                Calendar date = startOfDay(saleDate);

                if (inRange(date, todayStart, tomorrow)) today.add(cart);
                if (inRange(date, weekStart, nextWeek)) {
                    week.add(cart);
                    weekSales.add(cart);
                }
                if (inRange(date, previousWeekStart, weekStart)) previousWeek.add(cart);
                if (inRange(date, monthStart, nextMonth)) {
                    month.add(cart);
                    monthSales.add(cart);
                }
                if (inRange(date, previousMonthStart, previousMonthEnd)) previousMonth.add(cart);
            }
        }

        // Use current-month products where available; otherwise fall back to
        // the latest seven-day period so new sellers can still get useful feedback.
        List<WasytSellerApi.Cart> productSales = monthSales;
        String productPeriod = "Joriy oy";
        if (!hasProductRows(monthSales)) {
            productSales = weekSales;
            productPeriod = "So'nggi 7 kun";
        }

        HashMap<String, MutableProduct> products = new HashMap<>();
        HashMap<String, Integer> categories = new HashMap<>();
        int productLineCount = 0;

        for (WasytSellerApi.Cart cart : productSales) {
            if (cart.products == null) continue;
            for (WasytSellerApi.CartProduct row : cart.products) {
                if (row == null) continue;
                String name = safe(row.name).trim();
                if (name.isEmpty() || row.quantity == null
                        || row.quantity.signum() <= 0) continue;

                String unit = safe(row.unit).trim();
                String category = safe(row.category).trim();
                if (category.isEmpty()) category = "Kategoriya ko'rsatilmagan";

                String kind = row.service ? "Xizmat"
                        : (row.bundle ? "Komplekt" : "Tovar");
                String key = productKey(row, name, unit, kind);
                MutableProduct product = products.get(key);
                if (product == null) {
                    product = new MutableProduct();
                    product.id = row.id;
                    product.name = name;
                    product.unit = unit;
                    product.category = category;
                    product.kind = kind;
                    products.put(key, product);
                }
                product.quantity = product.quantity.add(row.quantity);
                product.lineCount++;
                productLineCount++;
                categories.put(category, categories.containsKey(category)
                        ? categories.get(category) + 1 : 1);
            }
        }

        ArrayList<ProductStat> rankedProducts = new ArrayList<>();
        for (MutableProduct product : products.values()) {
            rankedProducts.add(product.freeze());
        }
        Collections.sort(rankedProducts, new Comparator<ProductStat>() {
            @Override public int compare(ProductStat first, ProductStat second) {
                int lines = Integer.compare(second.lineCount, first.lineCount);
                if (lines != 0) return lines;
                return second.quantity.compareTo(first.quantity);
            }
        });
        if (rankedProducts.size() > 3) {
            rankedProducts = new ArrayList<>(rankedProducts.subList(0, 3));
        }

        CategoryStat topCategory = null;
        for (Map.Entry<String, Integer> entry : categories.entrySet()) {
            if (topCategory == null || entry.getValue() > topCategory.lineCount) {
                topCategory = new CategoryStat(entry.getKey(), entry.getValue());
            }
        }

        return new Snapshot(
                today.freeze(), week.freeze(), previousWeek.freeze(),
                month.freeze(), previousMonth.freeze(),
                rankedProducts, topCategory, productLineCount > 0,
                productLineCount, productPeriod);
    }

    private static boolean hasProductRows(List<WasytSellerApi.Cart> sales) {
        if (sales == null) return false;
        for (WasytSellerApi.Cart cart : sales) {
            if (cart != null && cart.products != null && !cart.products.isEmpty()) return true;
        }
        return false;
    }

    private static String productKey(WasytSellerApi.CartProduct product,
                                     String name, String unit, String kind) {
        if (product.id > 0) {
            return kind + "|" + product.id + "|" + unit.toLowerCase(Locale.ROOT);
        }
        return kind + "|" + name.toLowerCase(Locale.ROOT) + "|"
                + unit.toLowerCase(Locale.ROOT);
    }

    private static boolean inRange(Calendar value, Calendar start, Calendar endExclusive) {
        return value != null && !value.before(start) && value.before(endExclusive);
    }

    private static Calendar startOfDay(Calendar value) {
        Calendar result = (Calendar) value.clone();
        result.set(Calendar.HOUR_OF_DAY, 0);
        result.set(Calendar.MINUTE, 0);
        result.set(Calendar.SECOND, 0);
        result.set(Calendar.MILLISECOND, 0);
        return result;
    }

    private static Calendar addDays(Calendar value, int days) {
        Calendar result = (Calendar) value.clone();
        result.add(Calendar.DAY_OF_YEAR, days);
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
