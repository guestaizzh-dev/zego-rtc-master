package com.toyeah.dispatching.service;

import com.toyeah.dispatching.dto.OrderParticipantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChbzgOrderInfoService {

    private static final Logger logger = LoggerFactory.getLogger(ChbzgOrderInfoService.class);

    @Value("${chbzg.order-info.url:}")
    private String url;

    @Value("${chbzg.order-info.token:}")
    private String token;

    @Value("${chbzg.order-info.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${chbzg.order-info.read-timeout-ms:5000}")
    private int readTimeoutMs;

    private final Map<String, OrderParticipantInfo> cache = new ConcurrentHashMap<>();
    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(500, connectTimeoutMs));
        factory.setReadTimeout(Math.max(500, readTimeoutMs));
        restTemplate = new RestTemplate(factory);
        if (isBlank(url) || isBlank(token)) {
            logger.info("chbzg1 订单姓名查询未配置，录制后台将回退显示 userID");
        }
    }

    public OrderParticipantInfo findByOrderID(String orderID) {
        String normalized = normalize(orderID);
        if (isBlank(normalized) || isBlank(url) || isBlank(token)) {
            return null;
        }
        OrderParticipantInfo cached = cache.get(normalized);
        if (cached != null) {
            return cached;
        }
        try {
            String requestUrl = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("orderID", normalized)
                    .build(true)
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Recording-Token", token);
            headers.set("X-Requested-With", "XMLHttpRequest");
            ResponseEntity<Map> response = restTemplate.exchange(requestUrl, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            OrderParticipantInfo info = parseResponse(response.getBody());
            if (info != null && (!isBlank(info.getDoctorName()) || !isBlank(info.getPeerName()))) {
                if (isBlank(info.getOrderID())) {
                    info.setOrderID(normalized);
                }
                cache.put(normalized, info);
                return info;
            }
        } catch (Exception e) {
            logger.warn("查询 chbzg1 订单姓名失败，orderID={}", normalized, e);
        }
        return null;
    }

    private OrderParticipantInfo parseResponse(Map body) {
        if (body == null) {
            return null;
        }
        Object data = body.get("data");
        if (data instanceof Map) {
            return parseInfo((Map) data);
        }
        return parseInfo(body);
    }

    private OrderParticipantInfo parseInfo(Map data) {
        if (data == null) {
            return null;
        }
        OrderParticipantInfo info = new OrderParticipantInfo();
        info.setOrderID(stringValue(data.get("orderID")));
        if (isBlank(info.getOrderID())) {
            info.setOrderID(stringValue(data.get("id")));
        }
        info.setDoctorName(stringValue(data.get("doctorName")));
        info.setPeerName(stringValue(data.get("peerName")));
        return info;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
