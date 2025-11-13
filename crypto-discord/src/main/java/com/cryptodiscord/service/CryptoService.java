package com.cryptodiscord.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.cryptodiscord.component.UserApiKeys;
import com.cryptodiscord.dto.UnifiedTrade;
import io.gate.gateapi.ApiClient;
import io.gate.gateapi.Configuration;
import io.gate.gateapi.api.SpotApi;
import io.gate.gateapi.api.WalletApi;
import io.gate.gateapi.models.TotalBalance;
import io.gate.gateapi.models.Trade;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class CryptoService {
    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private final RestTemplate restTemplate;
    private final UserApiKeys userApiKeys;

    public CryptoService(RestTemplate restTemplate, UserApiKeys userApiKeys) {
        this.restTemplate = restTemplate;
        this.userApiKeys = userApiKeys;
    }

    // 빗썸 주문 내역 조회 api 호출
    public Map<String, Object> getBithumbOrders(String discordId, String market, String state) throws Exception {

        UserApiKeys.ApiKeys keys = userApiKeys.getKeys(discordId, "bithumb");
        String accessKey = keys.apiKey();
        String secretKey = keys.secretKey();

        String url = "https://api.bithumb.com/v1/orders";

        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("market", market));
        params.add(new BasicNameValuePair("state", state));
        params.add(new BasicNameValuePair("page", "1"));
        params.add(new BasicNameValuePair("limit", "10"));
        params.add(new BasicNameValuePair("order_by", "desc"));

        String query = URLEncodedUtils.format(params, StandardCharsets.UTF_8);

        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update(query.getBytes(StandardCharsets.UTF_8));
        String queryHash = String.format("%0128x", new BigInteger(1, md.digest()));

        Algorithm algorithm = Algorithm.HMAC256(secretKey);
        String jwtToken = JWT.create()
                .withClaim("access_key", accessKey)
                .withClaim("nonce", UUID.randomUUID().toString())
                .withClaim("timestamp", System.currentTimeMillis())
                .withClaim("query_hash", queryHash)
                .withClaim("query_hash_alg", "SHA512")
                .sign(algorithm);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // ★ ResponseType을 Object로 받음
        ResponseEntity<Object> response =
                restTemplate.exchange(url + "?" + query, HttpMethod.GET, entity, Object.class);

        Object body = response.getBody();
        log.info("[BITHUMB][RAW RESPONSE] {}", body);

        // ★ 배열로 오는 경우 → 빈 data 처리
        if (body instanceof List) {
            return Map.of(
                    "status", "0000",
                    "data", body
            );
        }

        // ★ 정상 Map 반환
        return (Map<String, Object>) body;
    }

    private String safe(Object v) {
        return v == null ? "정보 없음" : v.toString();
    }

    // 빗썸 주문 내역 조회
    public List<UnifiedTrade> mapBithumbOrdersToUnifiedTrades(Map<String, Object> raw) {

        if (!"0000".equals(raw.get("status"))) {
            log.warn("[BITHUMB] 상태 코드 0000 아님 → {}", raw);
            return List.of();
        }

        List<Map<String,Object>> list = (List<Map<String,Object>>) raw.get("data");
        if (list == null) {
            log.warn("[BITHUMB] data 없음 → {}", raw);
            return List.of();
        }

        List<UnifiedTrade> result = new ArrayList<>();

        for (Map<String,Object> tx : list) {

            String uuid = safe(tx.get("uuid"));
            String side = safe(tx.get("side"));
            String ordType = safe(tx.get("ord_type"));
            String state = safe(tx.get("state"));
            String market = safe(tx.get("market"));
            String createdAt = safe(tx.get("created_at"));

            String price = safe(tx.get("price"));
            String volume = safe(tx.get("volume"));
            String remainingVolume = safe(tx.get("remaining_volume"));
            String executedVolume = safe(tx.get("executed_volume"));

            String reservedFee = safe(tx.get("reserved_fee"));
            String remainingFee = safe(tx.get("remaining_fee"));
            String paidFee = safe(tx.get("paid_fee"));
            String locked = safe(tx.get("locked"));

            String tradesCount = safe(tx.get("trades_count"));

            // 🔥 주문 1건 전체 필드 로그 출력
            log.info("[BITHUMB ORDER]");
            log.info(" uuid              : {}", uuid);
            log.info(" side              : {}", side);
            log.info(" ord_type          : {}", ordType);
            log.info(" price             : {}", price);
            log.info(" volume            : {}", volume);
            log.info(" remaining_volume  : {}", remainingVolume);
            log.info(" executed_volume   : {}", executedVolume);
            log.info(" market            : {}", market);
            log.info(" state             : {}", state);
            log.info(" created_at        : {}", createdAt);
            log.info(" reserved_fee      : {}", reservedFee);
            log.info(" remaining_fee     : {}", remainingFee);
            log.info(" paid_fee          : {}", paidFee);
            log.info(" locked            : {}", locked);
            log.info(" trades_count      : {}", tradesCount);
            log.info("--------------------------------------------------");

            UnifiedTrade trade = new UnifiedTrade(
                    "Bithumb",
                    market,
                    side,
                    price,
                    volume,
                    createdAt,
                    ordType,
                    paidFee
            );

            result.add(trade);
        }

        // 📌 변환된 UnifiedTrade 전체 로그 출력
        log.info("[BITHUMB][PARSED TRADES] 총 {}건", result.size());
        result.forEach(t -> log.info(" → {}", t));

        return result;
    }


    // --- 4. 거래소별 API 헬퍼 ---

    // 안전하게 Double 변환
    private double parseDoubleSafe(Object obj) {
        if (obj == null) return 0.0;
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // 빗썸 전체 자산 조회
    public List<Map<String, Object>> getBithumbAssets(String discordId) {
        UserApiKeys.ApiKeys keys = userApiKeys.getKeys(discordId, "bithumb");
        HttpHeaders headers = createBithumbJwtHeaders(keys);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "https://api.bithumb.com/v1/accounts",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        List<Map<String, Object>> coins = response.getBody();
        if (coins != null) {
            log.info("[BITHUMB] 전체 자산 목록:");
            for (Map<String, Object> coin : coins) {
                String currency = (String) coin.get("currency");
                String unit_currency = (String) coin.get("unit_currency");
                double balance = parseDoubleSafe(coin.get("balance"));
                double locked = parseDoubleSafe(coin.get("locked"));
                double avgBuyPrice = parseDoubleSafe(coin.get("avg_buy_price"));

                // 현재 가격 가져오기
                double currentPrice = "KRW".equalsIgnoreCase(currency) ? 1 : getBithumbCurrentPrice(currency + "_KRW");
                coin.put("current_price", currentPrice);

                // avg_buy_price가 0이면 current_price로 대체
                if (avgBuyPrice == 0) coin.put("avg_buy_price", currentPrice);

                log.info("코인: {}, balance: {}, locked: {}, avg_buy_price: {}, current_price: {} unit_currency: {}",
                        currency, balance, locked, coin.get("avg_buy_price"), currentPrice, unit_currency);
            }
        } else {
            log.warn("[BITHUMB] 자산 조회 결과가 없습니다.");
        }
        return coins != null ? coins : Collections.emptyList();
    }

    // JWT 헤더 생성
    private HttpHeaders createBithumbJwtHeaders(UserApiKeys.ApiKeys keys) {
        Algorithm algorithm = Algorithm.HMAC256(keys.secretKey());
        String jwtToken = JWT.create()
                .withClaim("access_key", keys.apiKey())
                .withClaim("nonce", UUID.randomUUID().toString())
                .withClaim("timestamp", System.currentTimeMillis())
                .sign(algorithm);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ApiClient createGateioApiClient(String discordId) {
        UserApiKeys.ApiKeys keys = userApiKeys.getKeys(discordId, "gateio");
        ApiClient apiClient = Configuration.getDefaultApiClient();
        apiClient.setApiKeySecret(keys.apiKey(), keys.secretKey());
        return apiClient;
    }

    private SpotApi createGateioSpotApi(String discordId) {
        return new SpotApi(createGateioApiClient(discordId));
    }

    private double getGateioTotalUsdt(String discordId) throws Exception {
        WalletApi walletApi = new WalletApi(createGateioApiClient(discordId));
        TotalBalance totalBalance = walletApi.getTotalBalance().execute();
        if (totalBalance != null && totalBalance.getTotal() != null && totalBalance.getTotal().getAmount() != null) {
            double result = Double.parseDouble(totalBalance.getTotal().getAmount());
            log.info("[GATE.IO] 전체 USDT: {}", result);
            return result;
        }
        return 0.0;
    }

//    private UnifiedTrade mapGateioToUnified(Trade trade) {
//        return new UnifiedTrade("Gate.io", trade.getCurrencyPair(), trade.getSide().getValue(),
//                trade.getPrice(), trade.getAmount(), trade.getCreateTime());
//    }

    private UnifiedTrade mapBithumbToUnified(Map<String,Object> tx) {
        String side = "1".equals(tx.get("search")) ? "buy" : ("2".equals(tx.get("search")) ? "sell" : "etc");
        return new UnifiedTrade("Bithumb", tx.get("order_currency").toString(), side,
                tx.get("price").toString(), tx.get("units_traded").toString(), tx.get("transaction_date").toString(),
                tx.get("ord_type").toString(), tx.get("paid_fee").toString());
    }

    private double getBithumbCurrentPrice(String pair) {
        try {
            String url = "https://api.bithumb.com/public/ticker/" + pair;
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getBody() != null && "0000".equals(response.getBody().get("status"))) {
                Map<String,Object> data = (Map<String,Object>) response.getBody().get("data");
                if (data != null && data.get("closing_price") != null) {
                    return Double.parseDouble(data.get("closing_price").toString());
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private double getUsdKrwRate() {
        try {
            double btcKrw = getBithumbCurrentPrice("BTC_KRW");
            double btcUsdt = getGateioCurrentPrice("BTC_USDT");
            if (btcUsdt > 0) return btcKrw / btcUsdt;
        } catch (Exception ignored) {}
        return 1350.0;
    }

    private double getGateioCurrentPrice(String pair) throws Exception {
        SpotApi spotApi = new SpotApi(Configuration.getDefaultApiClient());
        return Double.parseDouble(spotApi.listTickers().currencyPair(pair).execute().get(0).getLast());
    }
}
