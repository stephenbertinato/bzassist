package dev.bzassist.client.bazaar;

/**
 * One price level of the bazaar order book.
 *
 * @param amount       how many units are offered at this price by all the orders on it together
 * @param pricePerUnit coins per unit
 * @param orders       how many separate orders make up this level
 */
public record OrderLevel(int amount, double pricePerUnit, int orders) {
}
