package com.hmdp.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
public class BaiduMapGeoService {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public BaiduMapGeoService(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public GeoPoint geocode(String city, String locationHint) {
        if (!Boolean.TRUE.equals(aiProperties.getBaiduMap().getEnabled())
                || !StringUtils.hasText(aiProperties.getBaiduMap().getAk())
                || !StringUtils.hasText(locationHint)) {
            log.info("baidu_geo disabled_or_missing city={}, locationHint={}", city, locationHint);
            return null;
        }
        RestClient restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                .build();
        GeoCandidate best = null;
        try {
            for (String address : buildCandidateAddresses(city, locationHint)) {
                GeoCandidate candidate = doGeocode(restClient, address, city, locationHint);
                if (candidate == null) {
                    continue;
                }
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            }
            if (best == null) {
                return null;
            }
            GeoPoint point = new GeoPoint(best.lng(), best.lat(), best.address());
            log.info("baidu_geo geocode_hit city={}, locationHint={}, lng={}, lat={}, resolved={}, confidence={}, precise={}, level={}",
                    city, locationHint, point.lng(), point.lat(), point.resolvedAddress(),
                    best.confidence(), best.precise(), best.level());
            return point;
        } catch (Exception e) {
            log.warn("baidu_geo geocode_failed city={}, locationHint={}", city, locationHint, e);
            return null;
        }
    }

    private Set<String> buildCandidateAddresses(String city, String locationHint) {
        String base = StringUtils.hasText(city) ? city + locationHint : locationHint;
        Set<String> addresses = new LinkedHashSet<String>();
        addresses.add(base);
        if (StringUtils.hasText(city)) {
            addresses.add(locationHint);
        }
        addresses.add(base + "地铁站");
        addresses.add(base + "商圈");
        if (looksLikeCommercialAlias(locationHint)) {
            addresses.add(base + "广场");
            addresses.add(base + "购物中心");
            addresses.add(base + "城市广场");
            addresses.add(base + "中心");
        }
        if (looksLikeCampusOrPark(locationHint)) {
            addresses.add(base + "校区");
            addresses.add(base + "园区");
        }
        return addresses;
    }

    private boolean looksLikeCommercialAlias(String locationHint) {
        if (!StringUtils.hasText(locationHint)) {
            return false;
        }
        return locationHint.matches(".*[A-Za-z0-9].*") || locationHint.length() <= 2;
    }

    private boolean looksLikeCampusOrPark(String locationHint) {
        if (!StringUtils.hasText(locationHint)) {
            return false;
        }
        return locationHint.contains("大学") || locationHint.contains("学院")
                || locationHint.contains("园") || locationHint.contains("校");
    }

    private GeoCandidate doGeocode(RestClient restClient, String address, String city, String locationHint) throws Exception {
        String url = aiProperties.getBaiduMap().getGeocodingUrl()
                + "?address=" + URLEncoder.encode(address, StandardCharsets.UTF_8)
                + "&output=json&ret_coordtype=gcj02ll&ak=" + aiProperties.getBaiduMap().getAk();
        String response = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(response);
        if (root.path("status").asInt(-1) != 0) {
            log.warn("baidu_geo geocode_status_not_ok city={}, locationHint={}, address={}, status={}, body={}",
                    city, locationHint, address, root.path("status").asInt(-1), response);
            return null;
        }
        JsonNode result = root.path("result");
        JsonNode location = result.path("location");
        if (!location.has("lng") || !location.has("lat")) {
            log.warn("baidu_geo geocode_no_location city={}, locationHint={}, address={}, body={}",
                    city, locationHint, address, response);
            return null;
        }
        int precise = result.path("precise").asInt(0);
        int confidence = result.path("confidence").asInt(0);
        String level = result.path("level").asText("");
        double score = precise * 100D + confidence + levelScore(level);
        log.info("baidu_geo candidate city={}, locationHint={}, address={}, lng={}, lat={}, precise={}, confidence={}, level={}, score={}",
                city, locationHint, address, location.get("lng").asDouble(), location.get("lat").asDouble(),
                precise, confidence, level, score);
        return new GeoCandidate(
                address,
                location.get("lng").asDouble(),
                location.get("lat").asDouble(),
                precise,
                confidence,
                level,
                score
        );
    }

    private double levelScore(String level) {
        if (!StringUtils.hasText(level)) {
            return 0D;
        }
        if (level.contains("城市")) {
            return -200D;
        }
        if (level.contains("道路")) {
            return 10D;
        }
        return 60D;
    }

    public record GeoPoint(Double lng, Double lat, String resolvedAddress) {
    }

    private record GeoCandidate(String address,
                                Double lng,
                                Double lat,
                                Integer precise,
                                Integer confidence,
                                String level,
                                Double score) {
    }
}
