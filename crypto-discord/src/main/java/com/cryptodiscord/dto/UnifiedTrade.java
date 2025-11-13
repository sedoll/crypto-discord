package com.cryptodiscord.dto;

public record UnifiedTrade(
        String exchange,
        String symbol,
        String side,
        String price,
        String amount,
        String timestamp,
        String ord_type,
        String paid_fee
) {}
