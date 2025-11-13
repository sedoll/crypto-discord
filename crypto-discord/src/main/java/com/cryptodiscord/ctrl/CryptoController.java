package com.cryptodiscord.ctrl;

import com.cryptodiscord.dto.UnifiedTrade;
import com.cryptodiscord.service.CryptoService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api")
public class CryptoController {
    private final CryptoService cryptoService;
    public CryptoController(CryptoService cryptoService) { this.cryptoService = cryptoService; }
    @GetMapping("/my-assets")
    public Map<String, Object> getMyAssets(@RequestParam String discord_id) throws Exception {
        Map<String, Object> result = new HashMap<>();

        // 빗썸 자산 메시지
        List<Map<String, Object>> bithumbCoins = cryptoService.getBithumbAssets(discord_id);
        result.put("coins", bithumbCoins);

        return result;
    }

    @GetMapping("/assets/exchange")
    public Map<String, Object> getExchangeAssets(@RequestParam String discord_id, @RequestParam String exchange) throws Exception {
        if ("bithumb".equalsIgnoreCase(exchange)) {
            return Map.of("coins", cryptoService.getBithumbAssets(discord_id));
        } else if ("gateio".equalsIgnoreCase(exchange)) {
//            return Map.of("coins", cryptoService.getGateioAssets(discord_id));
            throw new IllegalArgumentException("지원하지 않는 거래소입니다.");
        } else {
            throw new IllegalArgumentException("지원하지 않는 거래소입니다.");
        }
    }

    @GetMapping("/trades")
    public List<UnifiedTrade> getExchangeTrades(
            @RequestParam String discord_id,
            @RequestParam String exchange,
            @RequestParam(required = false, defaultValue = "KRW-BTC") String market,
            @RequestParam(required = false, defaultValue = "wait") String state
    ) throws Exception {
        if ("bithumb".equalsIgnoreCase(exchange)) {

            // 1. raw orders
            Map<String, Object> raw = cryptoService.getBithumbOrders(discord_id, market, state);

            // 2. convert to UnifiedTrade
            return cryptoService.mapBithumbOrdersToUnifiedTrades(raw);

        } else if ("gateio".equalsIgnoreCase(exchange)) {

            return null;

        }

        throw new IllegalArgumentException("지원하지 않는 거래소입니다.");
    }
}
