package com.orderflow.engine.pricing.domain.lever;

import com.orderflow.engine.pricing.domain.ComboAllocation;
import com.orderflow.engine.pricing.domain.Money;
import com.orderflow.engine.pricing.domain.PricingSnapshot.Product;
import com.orderflow.engine.pricing.domain.lever.ComboRule.Item;
import com.orderflow.engine.pricing.domain.lever.TierDiscountStep.Units;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 2 of SPEC BR-01: the combos of BR-03. A combo applies as many whole times as the units left
 * allow, {@code floor(min(qty_i / minQty_i))}, and its discount is what its consumed units list at
 * minus the combo price of those applications. A combo that saves nothing does not apply and
 * consumes nothing. Combos run in id order, each on the units the previous ones left, and each
 * discount is split over the combo's lines by largest remainder, in proportion to the gross of the
 * units each line gave the combo (RF-3). The units no combo consumed are what step 3 may count.
 */
public final class ComboStep {

    // Ids are compared as text, which is how PostgreSQL orders uuid values, byte by byte.
    // UUID.compareTo compares signed longs, so it would put an id that starts with 8 to f first.
    private static final Comparator<ComboRule> ID_ORDER =
            Comparator.comparing(rule -> rule.id().toString());

    private ComboStep() {
    }

    /**
     * Applies the combos to the paid units, one entry per SKU as in a normalized cart, and returns
     * each SKU's allocations, in combo order, with the units no combo consumed.
     */
    public static Result apply(List<ComboRule> rules, List<Units> paid) {
        Map<String, Product> products = new HashMap<>();
        Map<String, Long> left = new HashMap<>();
        for (Units units : paid) {
            products.put(units.product().sku(), units.product());
            left.put(units.product().sku(), units.count());
        }
        Map<String, List<ComboAllocation>> allocations = new HashMap<>();
        for (ComboRule rule : rules.stream().sorted(ID_ORDER).toList()) {
            long applications = rule.items().stream()
                    .mapToLong(item -> left.getOrDefault(item.sku(), 0L) / item.minQty())
                    .min()
                    .orElseThrow();
            // A required SKU that is missing or short gives no whole application.
            if (applications == 0) {
                continue;
            }
            Map<String, Money> consumedGross = new HashMap<>();
            for (Item item : rule.items()) {
                Money listPrice = products.get(item.sku()).listPrice();
                consumedGross.put(item.sku(), listPrice.times(applications * item.minQty()));
            }
            Money listValue = consumedGross.values().stream().reduce(Money::plus).orElseThrow();
            Money bundlePrice = rule.comboPrice().times(applications);
            // A combo that saves nothing does not apply, so its units stay for the tier step (BR-03).
            if (bundlePrice.compareTo(listValue) >= 0) {
                continue;
            }
            for (Item item : rule.items()) {
                left.merge(item.sku(), applications * item.minQty(), (count, consumed) -> count - consumed);
            }
            Map<String, Money> shares =
                    LargestRemainderAllocator.allocate(listValue.minus(bundlePrice), consumedGross);
            shares.forEach((sku, amount) -> {
                // A share that rounds to zero is not emitted (RP-2).
                if (amount.amount().signum() > 0) {
                    allocations.computeIfAbsent(sku, none -> new ArrayList<>())
                            .add(new ComboAllocation(rule.id(), amount));
                }
            });
        }
        return new Result(allocations, paid.stream()
                .map(units -> new Units(units.product(), left.get(units.product().sku())))
                .toList());
    }

    /**
     * What step 2 leaves for the next steps: each SKU's combo allocations, in combo order, and the
     * paid units no combo consumed, in the order they were given.
     */
    public record Result(Map<String, List<ComboAllocation>> allocations, List<Units> unconsumed) {

        public Result {
            Map<String, List<ComboAllocation>> copy = new HashMap<>();
            allocations.forEach((sku, parts) -> copy.put(sku, List.copyOf(parts)));
            allocations = Map.copyOf(copy);
            unconsumed = List.copyOf(unconsumed);
        }

        /** The allocations of one SKU, in combo order, or none when no combo consumed it. */
        public List<ComboAllocation> allocationsOf(String sku) {
            return allocations.getOrDefault(sku, List.of());
        }
    }
}
