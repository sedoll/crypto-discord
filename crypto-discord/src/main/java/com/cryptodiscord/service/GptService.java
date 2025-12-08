package com.cryptodiscord.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * OpenAI Responses API 기반 GPT 서비스
 * - gpt-5 이상 버전 지원
 * - temperature 등 미지원 옵션 제거 / gpt4 이하 버전만 사용
 * - output 파싱 안정화
 */
@Service
public class GptService {

    private static final Logger log = LoggerFactory.getLogger(GptService.class);

    private final RestTemplate restTemplate;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-5-mini}")
    private String openAiModel;

    private static final String OPENAI_RESPONSES_URL =
            "https://api.openai.com/v1/responses";

    public GptService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 자산 목록을 기반으로 짧은 AI 피드백 생성
     */
    public String buildAssetFeedback(List<Map<String, Object>> assets) {
        if (assets == null || assets.isEmpty()) {
            return "보유한 자산 정보가 없어 요약할 수 없습니다.";
        }

        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            log.warn("OpenAI API key not configured");
            return "AI 피드백을 생성할 수 없습니다 (API 키 미설정).";
        }

        String prompt = buildPrompt(assets);

        try {
            return callResponsesApi(prompt);
        } catch (Exception e) {
            log.warn("Failed to build GPT feedback", e);
            return "AI 피드백을 생성하지 못했습니다.";
        }
    }

    /**
     * 프롬프트 구성
     */
    private String buildPrompt(List<Map<String, Object>> assets) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래는 사용자의 가상자산 보유 현황입니다.\n");
        sb.append("아래 형식을 반드시 지켜 한국어로 작성하세요.\n\n");

        sb.append("형식:\n");
        sb.append("총평: 전체 추정가와 자산 집중도를 한 문장으로 요약\n");
        sb.append("변동성: 가격 변동성·유동성 위험을 한 문장으로 설명\n");
        sb.append("분산: 자산 분산 상태를 한 문장으로 평가\n");
        sb.append("면책: \"이는 투자 조언이 아닙니다.\" 문구로 마무리\n\n");

        sb.append("조건:\n");
        sb.append("- 각 항목은 반드시 한 줄씩 작성\n");
        sb.append("- 항목명(총평, 변동성, 분산, 면책)을 그대로 사용\n");
        sb.append("- 수치는 대략적인 비율(%)로 표현\n");
        sb.append("- 특정 종목 매수/매도 추천은 하지 말 것\n\n");

        sb.append("자산 목록:\n");

        for (Map<String, Object> asset : assets) {
            String currency = Objects.toString(asset.get("currency"), "N/A");
            double balance = toDouble(asset.get("balance"));
            double locked = toDouble(asset.get("locked"));
            double avg = toDouble(asset.get("avg_buy_price"));
            double current = toDouble(asset.get("current_price"));

            double qty = balance + locked;
            double estKrw = qty * current;

            sb.append(String.format(
                    "- %s: 보유 %.4f, 평단 %.0f, 현재가 %.0f, 추정 %.0f KRW\n",
                    currency, qty, avg, current, estKrw
            ));
        }
        return sb.toString();
    }

    /**
     * OpenAI Responses API 호출
     */
    private String callResponsesApi(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", openAiModel);

        requestBody.put("input", List.of(
                Map.of(
                        "role", "system",
                        "content", List.of(
                                Map.of(
                                        "type", "input_text",
                                        "text",
                                        "You are a concise Korean crypto portfolio assistant. "
                                                + "Provide brief, risk-focused insights without recommending specific coins."
                                )
                        )
                ),
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "input_text",
                                        "text", prompt
                                )
                        )
                )
        ));

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    OPENAI_RESPONSES_URL,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            Map<String, Object> body = response.getBody();

            // ✅ OpenAI 원본 응답 로그
            log.info("[OpenAI RAW RESPONSE] {}", body);

            if (body == null) {
                return "AI 응답이 비어 있습니다.";
            }

            String text = extractOutputText(body);
            // ✅ 최종 AI 피드백 로그
            if (text != null) {
                log.info("[AI FEEDBACK RESULT] {}", text);
                return text;
            }

            log.warn("[AI FEEDBACK RESULT] 없음 (파싱 실패)");
            return "AI 피드백을 받을 수 없습니다.";

        } catch (RestClientException e) {
            log.warn("OpenAI Responses API call failed: {}", e.getMessage());
            return "AI 피드백을 생성할 수 없습니다 (API 오류).";
        }
    }

    /**
     * Responses API output 파싱
     */
    @SuppressWarnings("unchecked")
    private String extractOutputText(Map<String, Object> body) {

        try {
            List<Map<String, Object>> outputs =
                    (List<Map<String, Object>>) body.get("output");

            if (outputs == null || outputs.isEmpty()) {
                log.warn("OpenAI response has no output field");
                return null;
            }

            StringBuilder result = new StringBuilder();

            for (Map<String, Object> out : outputs) {

                Object contentObj = out.get("content");
                if (!(contentObj instanceof List)) continue;

                List<Map<String, Object>> contents =
                        (List<Map<String, Object>>) contentObj;

                for (Map<String, Object> c : contents) {

                    String type = Objects.toString(c.get("type"), "");
                    log.debug("[AI CONTENT BLOCK] type={}, payload={}", type, c);

                    if ("output_text".equals(type)
                            || "summary_text".equals(type)) {

                        Object text = c.get("text");
                        if (text != null) {
                            result.append(text.toString());
                        }
                    }

                    if ("refusal".equals(type)) {
                        return "AI가 안전 정책상 해당 요청을 처리할 수 없습니다.";
                    }
                }
            }

            return result.length() > 0 ? result.toString().trim() : null;

        } catch (Exception e) {
            log.warn("Failed to parse OpenAI response body", e);
            return null;
        }
    }


    private double toDouble(Object v) {
        if (v == null) return 0.0;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
