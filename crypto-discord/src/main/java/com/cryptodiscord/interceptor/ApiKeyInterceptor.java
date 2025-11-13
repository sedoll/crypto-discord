package com.cryptodiscord.interceptor;

import com.cryptodiscord.component.UserApiKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.codec.binary.Hex;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {
    @Autowired
    private UserApiKeys userApiKeys;
    private static final String NONCE_HEADER = "X-Bot-Nonce";
    private static final String SIGNATURE_HEADER = "X-Bot-Signature";
    private static final long VALID_WINDOW_MS = 10000;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String submittedNonce = request.getHeader(NONCE_HEADER);
        String submittedSignature = request.getHeader(SIGNATURE_HEADER);
        if (submittedNonce == null || submittedSignature == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Nonce or Signature is missing");
            return false;
        }
        long nonce;
        try {
            nonce = Long.parseLong(submittedNonce);
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Nonce format");
            return false;
        }
        long serverTime = System.currentTimeMillis();
        if (Math.abs(serverTime - nonce) > VALID_WINDOW_MS) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Nonce is out of time window");
            return false;
        }
        try {
            String botSecretKey = userApiKeys.getBotSecretKey();
            String expectedSignature = createHmacSha256(botSecretKey, submittedNonce);
            if (MessageDigest.isEqual(expectedSignature.getBytes(), submittedSignature.toLowerCase().getBytes())) {
                return true;
            } else {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Signature");
                return false;
            }
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Signature validation error");
            return false;
        }
    }

    private String createHmacSha256(String secret, String data) throws Exception {
        Mac hmacSha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmacSha256.init(secretKeySpec);
        byte[] hash = hmacSha256.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(hash);
    }
}
