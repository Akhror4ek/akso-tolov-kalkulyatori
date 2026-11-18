package uz.akso.kalkulyator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Seller coaching analytics based only on completed WASYT sales.
 * All sales totals are the server-calculated cart totals. Product insights use
 * actual returned cart rows; unknown costs, stock and margins are never inferred.
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

        BigDecimal averageCheck() {
            if (saleCount <= 0) return BigDecimal.ZERO;
            return total.divide(BigDecimal.valueOf(saleCount), 0, RoundingMode.HALF_UP);
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
        final int transactionCount;

        ProductStat(long productId, String name, String unit, String category,
                    String kind, BigDecimal quantity, int lineCount, int transactionCount) {
            this.productId = productId;
            this.name = safe(name);
            this.unit = safe(unit);
            this.category = safe(category);
            this.kind = safe(kind);
            this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
            this.lineCount = lineCount;
            this.transactionCount = transactionCount;
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
        // Previous week is deliberately matched to the same elapsed weekdays
        // as the current week, avoiding misleading Monday-to-Sunday comparisons.
        final Period previousWeek;
        final Period month;
        final Period previousMonthComparable;
        final List<ProductStat> topProducts;
        final CategoryStat topCategory;
        final boolean hasProductDetails;
        final int productLineCount;
        final String productPeriodLabel;
        final WasytSellerApi.Cart latestSale;
        final WasytSellerApi.Cart largestSale;
        final BigDecimal todayAverageCheck;
        final BigDecimal weekAverageCheck;
        final BigDecimal monthAverageCheck;
        final BigDecimal weekDiscountRate;
        final BigDecimal averageItemsPerSale;
        final BigDecimal topProductShare;
        final BigDecimal topCategoryShare;
        final int uniqueCustomerCount;
        final int repeatCustomerCount;
        final int singleItemSaleCount;
        final int multiItemSaleCount;
        final int salesWithCustomerCount;
        final int comparedWeekDays;
        final String strongestWeekday;
        final String strongestHour;
        final String pairedProducts;
        final int pairedSaleCount;
        final BigDecimal projectedMonthTotal;

        Snapshot(Period today, Period week, Period previousWeek,
                 Period month, Period previousMonthComparable,
                 List<ProductStat> topProducts, CategoryStat topCategory,
                 boolean hasProductDetails, int productLineCount,
                 String productPeriodLabel, WasytSellerApi.Cart latestSale,
                 WasytSellerApi.Cart largestSale, BigDecimal todayAverageCheck,
                 BigDecimal weekAverageCheck, BigDecimal monthAverageCheck,
                 BigDecimal weekDiscountRate, BigDecimal averageItemsPerSale,
                 BigDecimal topProductShare, BigDecimal topCategoryShare,
                 int uniqueCustomerCount, int repeatCustomerCount,
                 int singleItemSaleCount, int multiItemSaleCount,
                 int salesWithCustomerCount, int comparedWeekDays,
                 String strongestWeekday, String strongestHour,
                 String pairedProducts, int pairedSaleCount,
                 BigDecimal projectedMonthTotal) {
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
            this.latestSale = latestSale;
            this.largestSale = largestSale;
            this.todayAverageCheck = todayAverageCheck;
            this.weekAverageCheck = weekAverageCheck;
            this.monthAverageCheck = monthAverageCheck;
            this.weekDiscountRate = weekDiscountRate;
            this.averageItemsPerSale = averageItemsPerSale;
            this.topProductShare = topProductShare;
            this.topCategoryShare = topCategoryShare;
            this.uniqueCustomerCount = uniqueCustomerCount;
            this.repeatCustomerCount = repeatCustomerCount;
            this.singleItemSaleCount = singleItemSaleCount;
            this.multiItemSaleCount = multiItemSaleCount;
            this.salesWithCustomerCount = salesWithCustomerCount;
            this.comparedWeekDays = comparedWeekDays;
            this.strongestWeekday = safe(strongestWeekday);
            this.strongestHour = safe(strongestHour);
            this.pairedProducts = safe(pairedProducts);
            this.pairedSaleCount = pairedSaleCount;
            this.projectedMonthTotal = projectedMonthTotal;
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
        int transactionCount;

        ProductStat freeze() {
            return new ProductStat(id, name, unit, category, kind,
                    quantity, lineCount, transactionCount);
        }
    }

    private static final class PairStat {
        String label;
        int count;
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
        int comparedWeekDays = daysSinceMonday + 1;
        Calendar previousWeekComparableEnd = addDays(previousWeekStart, comparedWeekDays);

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
        ArrayList<WasytSellerApi.Cart> allValidSales = new ArrayList<>();
        HashMap<String, Integer> customerVisits = new HashMap<>();
        BigDecimal[] weekdayTotals = new BigDecimal[7];
        int[] weekdayCounts = new int[7];
        int[] hourCounts = new int[4];
        int singleItemSaleCount = 0;
        int multiItemSaleCount = 0;
        int salesWithCustomerCount = 0;
        int salesWithProductDetails = 0;
        int totalObservedItems = 0;
        WasytSellerApi.Cart latestSale = null;
        Calendar latestSaleDate = null;
        WasytSellerApi.Cart largestSale = null;

        for (int i = 0; i < weekdayTotals.length; i++) weekdayTotals[i] = BigDecimal.ZERO;

        if (sales != null) {
            for (WasytSellerApi.Cart cart : sales) {
                if (cart == null) continue;
                Calendar saleDate = WasytSalesRepository.getSaleDate(cart);
                if (saleDate == null) continue;
                Calendar date = startOfDay(saleDate);
                allValidSales.add(cart);

                if (latestSaleDate == null || saleDate.after(latestSaleDate)) {
                    latestSale = cart;
                    latestSaleDate = (Calendar) saleDate.clone();
                }

                if (inRange(date, monthStart, nextMonth)) {
                    if (largestSale == null || cart.totalAmount > largestSale.totalAmount) {
                        largestSale = cart;
                    }
                    month.add(cart);
                    monthSales.add(cart);
                    int weekday = saleDate.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
                    if (weekday >= 0 && weekday < 7) {
                        weekdayTotals[weekday] = weekdayTotals[weekday]
                                .add(BigDecimal.valueOf(Math.max(0L, cart.totalAmount)));
                        weekdayCounts[weekday]++;
                    }
                    int hour = saleDate.get(Calendar.HOUR_OF_DAY);
                    int hourBand = hour < 12 ? 0 : (hour < 16 ? 1 : (hour < 20 ? 2 : 3));
                    hourCounts[hourBand]++;
                }

                if (inRange(date, todayStart, tomorrow)) today.add(cart);
                if (inRange(date, weekStart, nextWeek)) {
                    week.add(cart);
                    weekSales.add(cart);
                }
                if (inRange(date, previousWeekStart, previousWeekComparableEnd)) {
                    previousWeek.add(cart);
                }
                if (inRange(date, previousMonthStart, previousMonthEnd)) {
                    previousMonth.add(cart);
                }

                String phone = digitsOnly(cart.clientPhone);
                if (!phone.isEmpty()) {
                    salesWithCustomerCount++;
                    customerVisits.put(phone, customerVisits.containsKey(phone)
                            ? customerVisits.get(phone) + 1 : 1);
                }

                int itemCount = cart.products != null && !cart.products.isEmpty()
                        ? cart.products.size() : Math.max(0, cart.productsCount);
                if (itemCount > 0) {
                    totalObservedItems += itemCount;
                    salesWithProductDetails++;
                    if (itemCount == 1) singleItemSaleCount++;
                    else multiItemSaleCount++;
                }
            }
        }

        List<WasytSellerApi.Cart> productSales = monthSales;
        String productPeriod = "Joriy oy";
        if (!hasProductRows(monthSales)) {
            productSales = weekSales;
            productPeriod = "So'nggi hafta";
        }

        HashMap<String, MutableProduct> products = new HashMap<>();
        HashMap<String, Integer> categories = new HashMap<>();
        HashMap<String, PairStat> productPairs = new HashMap<>();
        int productLineCount = 0;
        int pairSalesCount = 0;

        for (WasytSellerApi.Cart cart : productSales) {
            if (cart.products == null || cart.products.isEmpty()) continue;
            HashSet<String> productsInCart = new HashSet<>();
            HashMap<String, String> namesInCart = new HashMap<>();

            for (WasytSellerApi.CartProduct row : cart.products) {
                if (row == null) continue;
                String name = safe(row.name).trim();
                if (name.isEmpty() || row.quantity == null || row.quantity.signum() <= 0) continue;

                String unit = safe(row.unit).trim();
                String category = safe(row.category).trim();
                if (category.isEmpty()) category = "Kategoriya ko'rsatilmagan";
                String kind = row.service ? "Xizmat" : (row.bundle ? "Komplekt" : "Tovar");
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
                productsInCart.add(key);
                namesInCart.put(key, name);
            }

            for (String productKey : productsInCart) {
                MutableProduct product = products.get(productKey);
                if (product != null) product.transactionCount++;
            }

            if (productsInCart.size() > 1) {
                pairSalesCount++;
                ArrayList<String> keys = new ArrayList<>(productsInCart);
                Collections.sort(keys);
                for (int i = 0; i < keys.size(); i++) {
                    for (int j = i + 1; j < keys.size(); j++) {
                        String pairKey = keys.get(i) + "::" + keys.get(j);
                        PairStat pair = productPairs.get(pairKey);
                        if (pair == null) {
                            pair = new PairStat();
                            pair.label = namesInCart.get(keys.get(i)) + " + "
                                    + namesInCart.get(keys.get(j));
                            productPairs.put(pairKey, pair);
                        }
                        pair.count++;
                    }
                }
            }
        }

        ArrayList<ProductStat> rankedProducts = new ArrayList<>();
        for (MutableProduct product : products.values()) rankedProducts.add(product.freeze());
        Collections.sort(rankedProducts, new Comparator<ProductStat>() {
            @Override public int compare(ProductStat first, ProductStat second) {
                int transactions = Integer.compare(second.transactionCount, first.transactionCount);
                if (transactions != 0) return transactions;
                int lines = Integer.compare(second.lineCount, first.lineCount);
                if (lines != 0) return lines;
                return second.quantity.compareTo(first.quantity);
            }
        });

        ProductStat topProduct = rankedProducts.isEmpty() ? null : rankedProducts.get(0);
        BigDecimal topProductShare = topProduct == null || productLineCount <= 0
                ? BigDecimal.ZERO
                : percent(topProduct.lineCount, productLineCount);

        if (rankedProducts.size() > 3) {
            rankedProducts = new ArrayList<>(rankedProducts.subList(0, 3));
        }

        CategoryStat topCategory = null;
        for (Map.Entry<String, Integer> entry : categories.entrySet()) {
            if (topCategory == null || entry.getValue() > topCategory.lineCount) {
                topCategory = new CategoryStat(entry.getKey(), entry.getValue());
            }
        }
        BigDecimal topCategoryShare = topCategory == null || productLineCount <= 0
                ? BigDecimal.ZERO : percent(topCategory.lineCount, productLineCount);

        PairStat topPair = null;
        for (PairStat pair : productPairs.values()) {
            if (topPair == null || pair.count > topPair.count) topPair = pair;
        }

        int uniqueCustomers = customerVisits.size();
        int repeatCustomers = 0;
        for (Integer visits : customerVisits.values()) if (visits != null && visits > 1) repeatCustomers++;

        String strongestWeekday = strongestWeekday(weekdayTotals, weekdayCounts);
        String strongestHour = strongestHour(hourCounts);
        Period todayPeriod = today.freeze();
        Period weekPeriod = week.freeze();
        Period monthPeriod = month.freeze();
        BigDecimal weekDiscountRate = discountRate(weekPeriod);
        BigDecimal averageItems = salesWithProductDetails <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalObservedItems)
                .divide(BigDecimal.valueOf(salesWithProductDetails), 1, RoundingMode.HALF_UP);
        BigDecimal projectedMonth = monthPeriod.saleCount <= 0
                ? BigDecimal.ZERO
                : monthPeriod.total.divide(BigDecimal.valueOf(todayStart.get(Calendar.DAY_OF_MONTH)),
                        0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(todayStart.getActualMaximum(Calendar.DAY_OF_MONTH)));

        return new Snapshot(
                todayPeriod, weekPeriod, previousWeek.freeze(),
                monthPeriod, previousMonth.freeze(),
                rankedProducts, topCategory, productLineCount > 0,
                productLineCount, productPeriod, latestSale, largestSale,
                todayPeriod.averageCheck(), weekPeriod.averageCheck(), monthPeriod.averageCheck(),
                weekDiscountRate, averageItems, topProductShare, topCategoryShare,
                uniqueCustomers, repeatCustomers, singleItemSaleCount,
                multiItemSaleCount, salesWithCustomerCount, comparedWeekDays,
                strongestWeekday, strongestHour,
                topPair == null ? "" : topPair.label,
                topPair == null ? 0 : topPair.count, projectedMonth);
    }

    private static BigDecimal percent(int part, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal discountRate(Period period) {
        if (period == null) return BigDecimal.ZERO;
        BigDecimal base = period.total.add(period.discountTotal);
        if (base.signum() <= 0) return BigDecimal.ZERO;
        return period.discountTotal.multiply(BigDecimal.valueOf(100))
                .divide(base, 1, RoundingMode.HALF_UP);
    }

    private static String strongestWeekday(BigDecimal[] totals, int[] counts) {
        int best = -1;
        for (int i = 0; i < totals.length; i++) {
            if (counts[i] <= 0) continue;
            if (best < 0 || totals[i].compareTo(totals[best]) > 0) best = i;
        }
        if (best < 0) return "Ma'lumot yetarli emas";
        switch (best) {
            case Calendar.MONDAY - Calendar.SUNDAY: return "Dushanba";
            case Calendar.TUESDAY - Calendar.SUNDAY: return "Seshanba";
            case Calendar.WEDNESDAY - Calendar.SUNDAY: return "Chorshanba";
            case Calendar.THURSDAY - Calendar.SUNDAY: return "Payshanba";
            case Calendar.FRIDAY - Calendar.SUNDAY: return "Juma";
            case Calendar.SATURDAY - Calendar.SUNDAY: return "Shanba";
            default: return "Yakshanba";
        }
    }

    private static String strongestHour(int[] counts) {
        int best = -1;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            if (best < 0 || counts[i] > counts[best]) best = i;
        }
        if (best < 0) return "Ma'lumot yetarli emas";
        switch (best) {
            case 0: return "08:00–12:00";
            case 1: return "12:00–16:00";
            case 2: return "16:00–20:00";
            default: return "20:00 dan keyin";
        }
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

    private static String digitsOnly(String value) {
        return safe(value).replaceAll("[^0-9]", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
