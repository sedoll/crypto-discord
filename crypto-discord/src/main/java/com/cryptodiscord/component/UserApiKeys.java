package com.cryptodiscord.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UserApiKeys {
    // [수정] Passphrase가 필요 없으므로 레코드 간소화
    public record ApiKeys(String apiKey, String secretKey) {}
    private final Map<String, Map<String, ApiKeys>> userKeysDb = new ConcurrentHashMap<>();
    private String botSecretKey;

    // --- (prod) 파일 경로
    @Value("${api.keys.bithumb.key_file:#{null}}") private String BITHUMB_KEY_PATH;
    @Value("${api.keys.bithumb.secret_file:#{null}}") private String BITHUMB_SECRET_PATH;
    @Value("${api.keys.gateio.key_file:#{null}}") private String GATEIO_KEY_PATH;
    @Value("${api.keys.gateio.secret_file:#{null}}") private String GATEIO_SECRET_PATH;
    @Value("${api.bot-access-key_file:#{null}}") private String BOT_SECRET_KEY_PATH;

    // --- (dev) 키 값
    @Value("${api.keys.bithumb.key:#{null}}") private String BITHUMB_KEY;
    @Value("${api.keys.bithumb.secret:#{null}}") private String BITHUMB_SECRET;
    @Value("${api.keys.gateio.key:#{null}}") private String GATEIO_KEY;
    @Value("${api.keys.gateio.secret:#{null}}") private String GATEIO_SECRET;
    @Value("${api.bot-access-key:#{null}}") private String BOT_SECRET_KEY_VAL;

    // 디스코드 id
    @Value("${api.keys.discord.user-key:#{null}") private String DISCORD_USER_KEY;

    @PostConstruct
    public void init() throws IOException {
        String bithumbKey, bithumbSecret, gateioKey, gateioSecret;

        if (BOT_SECRET_KEY_PATH != null) {
            // === PROD (파일) 모드 ===
            System.out.println("운영 모드(prod): Docker Secrets에서 키를 로드합니다.");
            bithumbKey = readSecret(BITHUMB_KEY_PATH);
            bithumbSecret = readSecret(BITHUMB_SECRET_PATH);
            gateioKey = readSecret(GATEIO_KEY_PATH);
            gateioSecret = readSecret(GATEIO_SECRET_PATH);
            this.botSecretKey = readSecret(BOT_SECRET_KEY_PATH);
        } else {
            // === DEV (환경변수 값) 모드 ===
            System.out.println("개발 모드(dev): 환경 변수(.env.local)에서 키를 로드합니다.");
            bithumbKey = BITHUMB_KEY;
            bithumbSecret = BITHUMB_SECRET;
            gateioKey = GATEIO_KEY;
            gateioSecret = GATEIO_SECRET;
            this.botSecretKey = BOT_SECRET_KEY_VAL;
        }

        Map<String, ApiKeys> testUserKeys = new ConcurrentHashMap<>();
        testUserKeys.put("bithumb", new ApiKeys(bithumbKey, bithumbSecret)); // [수정]
        testUserKeys.put("gateio", new ApiKeys(gateioKey, gateioSecret));   // [수정]

        userKeysDb.put(DISCORD_USER_KEY, testUserKeys);
    }

    private String readSecret(String path) throws IOException {
        try {
            return new String(Files.readAllBytes(Paths.get(path))).trim();
        } catch (IOException e) {
            System.err.println("!!! 시크릿 파일 읽기 실패: " + path);
            throw e;
        }
    }

    public ApiKeys getKeys(String discordId, String exchange) {
        ApiKeys keys = userKeysDb.getOrDefault(discordId, Map.of()).get(exchange);
        if (keys == null || keys.apiKey() == null) {
            throw new RuntimeException(exchange + " API 키가 서버에 설정되지 않았습니다. (User: " + discordId + ")");
        }
        return keys;
    }

    public String getBotSecretKey() {
        if (this.botSecretKey == null) {
            throw new RuntimeException("봇 서명 키(BOT_ACCESS_KEY)가 로드되지 않았습니다.");
        }
        return this.botSecretKey;
    }
}
