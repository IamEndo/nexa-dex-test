package org.nexadex.core.math

/**
 * Constant-product AMM math (x * y = k).
 *
 * All amounts are Long (satoshis for NEX, raw units for tokens).
 * No floating point for money — FP only for display prices.
 *
 * IMPORTANT: Must match SDK's exact pricing in ContractOperationsImpl.
 * The on-chain contract uses no-fee formula: Δy = Rout × Δx / (Rin + Δx).
 * Any mismatch causes reserve tracking drift and "Bad template operation" errors.
 */
object AmmMath {

    const val DUST_THRESHOLD: Long = 546L

    /** Default swap fee: 0.3% (30 basis points). */
    const val DEFAULT_FEE_BPS: Int = 30

    /**
     * Compute output amount for a constant-product swap with fee.
     *
     * Formula: amountInAfterFee = amountIn * (10000 - feeBps) / 10000
     *          Δy = (reserveOut × amountInAfterFee) / (reserveIn + amountInAfterFee)
     *
     * The on-chain contract validates: nexIn * tokenIn >= nexOut * tokenOut.
     * With fee, the service gives users less output than the no-fee formula,
     * so the inequality is satisfied and the fee stays in the pool.
     *
     * @param amountIn Input amount (positive)
     * @param reserveIn Input-side reserve (before swap)
     * @param reserveOut Output-side reserve (before swap)
     * @param feeBps Fee in basis points (default 30 = 0.3%)
     * @return Output amount, or null if inputs are invalid
     */
    fun computeSwapOutput(amountIn: Long, reserveIn: Long, reserveOut: Long, feeBps: Int = DEFAULT_FEE_BPS): Long? {
        if (amountIn <= 0 || reserveIn <= 0 || reserveOut <= 0) return null

        // Apply fee: amountInAfterFee = amountIn * (10000 - feeBps) / 10000
        val feeMultiplier = 10_000L - feeBps
        val amountInAfterFee = amountIn * feeMultiplier / 10_000L
        if (amountInAfterFee <= 0) return null

        // Use BigInteger to prevent overflow for large values
        val amountInBig = java.math.BigInteger.valueOf(amountInAfterFee)
        val reserveInBig = java.math.BigInteger.valueOf(reserveIn)
        val reserveOutBig = java.math.BigInteger.valueOf(reserveOut)

        val numerator = reserveOutBig * amountInBig
        val denominator = reserveInBig + amountInBig

        if (denominator <= java.math.BigInteger.ZERO) return null

        val output = (numerator / denominator).toLong()
        return if (output > 0) output else null
    }

    /**
     * Compute output for selling tokens → NEX.
     */
    fun computeSellOutput(tokenAmountIn: Long, nexReserve: Long, tokenReserve: Long, feeBps: Int = DEFAULT_FEE_BPS): Long? =
        computeSwapOutput(tokenAmountIn, tokenReserve, nexReserve, feeBps)

    /**
     * Compute output for buying tokens ← NEX.
     */
    fun computeBuyOutput(nexAmountIn: Long, nexReserve: Long, tokenReserve: Long, feeBps: Int = DEFAULT_FEE_BPS): Long? =
        computeSwapOutput(nexAmountIn, nexReserve, tokenReserve, feeBps)

    /**
     * Compute spot price (NEX per token) as a Double for display.
     * Returns null if reserves are zero.
     */
    fun spotPrice(nexReserve: Long, tokenReserve: Long): Double? {
        if (nexReserve <= 0 || tokenReserve <= 0) return null
        return nexReserve.toDouble() / tokenReserve.toDouble()
    }

    /**
     * Compute price impact in basis points.
     *
     * priceImpact = |1 - (executionPrice / spotPrice)| × 10000
     *
     * @return Impact in bps (0-10000), or null if can't compute
     */
    fun computePriceImpactBps(
        amountIn: Long,
        amountOut: Long,
        reserveIn: Long,
        reserveOut: Long,
    ): Int? {
        if (amountIn <= 0 || amountOut <= 0 || reserveIn <= 0 || reserveOut <= 0) return null

        val spotPriceRatio = reserveOut.toDouble() / reserveIn.toDouble()
        val executionPriceRatio = amountOut.toDouble() / amountIn.toDouble()

        val impact = Math.abs(1.0 - executionPriceRatio / spotPriceRatio)
        return (impact * 10000).toInt()
    }

    /**
     * Compute minimum received given slippage tolerance.
     *
     * @param expectedOut Expected output amount
     * @param slippageBps Slippage tolerance in basis points (e.g., 50 = 0.5%)
     * @return Minimum acceptable output amount
     */
    fun computeMinimumReceived(expectedOut: Long, slippageBps: Int): Long {
        if (expectedOut <= 0 || slippageBps < 0) return 0L
        return expectedOut * (10_000L - slippageBps) / 10_000L
    }

    /**
     * Compute TVL in NEX satoshis. For a balanced AMM: TVL = 2 × nexReserve.
     */
    fun computeTvl(nexReserve: Long): Long = nexReserve * 2

    /**
     * Compute new reserves after a SELL trade (tokens → NEX).
     *
     * @return Pair(newNexReserve, newTokenReserve), or null if swap output is null
     */
    fun reservesAfterSell(
        tokenAmountIn: Long,
        nexReserve: Long,
        tokenReserve: Long,
        feeBps: Int = DEFAULT_FEE_BPS,
    ): Pair<Long, Long>? {
        val nexOut = computeSellOutput(tokenAmountIn, nexReserve, tokenReserve, feeBps) ?: return null
        // Full tokenAmountIn goes to pool (fee is the difference between no-fee and fee-adjusted output)
        return Pair(nexReserve - nexOut, tokenReserve + tokenAmountIn)
    }

    /**
     * Compute new reserves after a BUY trade (NEX → tokens).
     *
     * @return Pair(newNexReserve, newTokenReserve), or null if swap output is null
     */
    fun reservesAfterBuy(
        nexAmountIn: Long,
        nexReserve: Long,
        tokenReserve: Long,
        feeBps: Int = DEFAULT_FEE_BPS,
    ): Pair<Long, Long>? {
        val tokensOut = computeBuyOutput(nexAmountIn, nexReserve, tokenReserve, feeBps) ?: return null
        // Full nexAmountIn goes to pool (fee stays as extra reserve)
        return Pair(nexReserve + nexAmountIn, tokenReserve - tokensOut)
    }

    /**
     * Validate that a trade doesn't drain reserves below dust.
     */
    fun validateReservesAfterTrade(newNexReserve: Long, newTokenReserve: Long): Boolean =
        newNexReserve >= DUST_THRESHOLD && newTokenReserve > 0

    /**
     * Compute proportional token amount for adding liquidity.
     * Maintains the current price ratio: tokenAmount = nexAmount * tokenReserve / nexReserve
     */
    fun computeProportionalTokenAmount(nexAmountIn: Long, nexReserve: Long, tokenReserve: Long): Long? {
        if (nexAmountIn <= 0 || nexReserve <= 0 || tokenReserve <= 0) return null
        // Use BigInteger to prevent overflow for large reserves
        return (java.math.BigInteger.valueOf(nexAmountIn) * java.math.BigInteger.valueOf(tokenReserve) /
                java.math.BigInteger.valueOf(nexReserve)).toLong()
    }

    /**
     * Compute withdrawal amounts for removing a percentage of liquidity.
     *
     * @param sharePct Percentage to remove (0-100)
     * @return Pair(nexAmount, tokenAmount) to withdraw, or null if invalid
     */
    fun computeWithdrawalAmounts(sharePct: Double, nexReserve: Long, tokenReserve: Long): Pair<Long, Long>? {
        if (sharePct <= 0.0 || sharePct > 100.0 || nexReserve <= 0 || tokenReserve <= 0) return null
        val nexOut = (nexReserve * sharePct / 100.0).toLong()
        val tokenOut = (tokenReserve * sharePct / 100.0).toLong()
        if (nexOut <= 0) return null
        return Pair(nexOut, tokenOut)
    }
}
