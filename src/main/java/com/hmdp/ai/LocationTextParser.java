package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse "city + location hint" from user text.
 * The parser is intentionally conservative:
 * it prefers missing a location over turning generic words into fake landmarks.
 */
@Component
public class LocationTextParser {

    private static final Pattern NEARBY_PHRASE_PATTERN =
            Pattern.compile("([\\p{IsHan}A-Za-z0-9·_-]{2,24}?)(?:附近|周边|一带|旁边|周围)");
    private static final Pattern CITY_SUFFIX_PATTERN =
            Pattern.compile("^([\\p{IsHan}]{2,12}(?:市|地区|自治州|特别行政区|自治区|盟|州))");

    private static final String[] NOISE_PREFIXES = {
            "帮我", "给我", "请帮我", "请给我", "推荐", "帮忙", "看看", "查查", "搜搜",
            "有没有", "有啥", "请问", "想找", "找找", "找个", "给我找", "来点", "来个", "一下"
    };

    private static final String[] PHRASE_BOUNDARIES = {
            "有什么", "有啥", "哪里", "哪儿", "推荐", "餐厅", "饭店", "小馆", "馆子", "店",
            "好吃", "便宜", "平价", "评价", "评分", "环境", "适合", "约会", "聚餐", "优惠",
            "折扣", "预算", "人均", "吃什么", "喝什么", "玩什么", "不要", "别", "不想"
    };

    private static final String[] LOCATION_SUFFIX_SIGNALS = {
            "路", "街", "大道", "广场", "中心", "商圈", "地铁站", "校区", "大厦", "园区",
            "城", "村", "镇", "区", "口", "楼", "站", "桥", "湖", "山"
    };

    private static final String[] INVALID_LOCATION_TERMS = {
            "找个", "找找", "附近", "周边", "吃饭", "好吃", "便宜", "平价", "推荐", "这个项目", "这个软件", "为什么"
    };

    private final IShopService shopService;
    private volatile List<String> knownCities = List.of();
    private volatile boolean citiesLoaded = false;

    public LocationTextParser(IShopService shopService) {
        this.shopService = shopService;
    }

    public ParsedLocation parse(String message) {
        if (StrUtil.isBlank(message)) {
            return ParsedLocation.empty();
        }

        String normalized = normalizeMessage(message);
        String phrase = extractLocationPhrase(normalized);
        String city = extractCityCandidate(phrase);
        if (StrUtil.isBlank(city)) {
            city = extractCityCandidate(normalized);
        }

        String locationHint = trimLocationHint(stripLeadingCity(phrase, city));
        if (StrUtil.isBlank(locationHint) && StrUtil.isBlank(city) && StrUtil.isNotBlank(phrase)) {
            locationHint = trimLocationHint(phrase);
        }

        if (StrUtil.equals(city, locationHint) || isInvalidLocation(locationHint)) {
            locationHint = null;
        }

        return new ParsedLocation(StrUtil.emptyToNull(city), StrUtil.emptyToNull(locationHint));
    }

    private String extractLocationPhrase(String normalized) {
        if (StrUtil.isBlank(normalized)) {
            return null;
        }

        Matcher nearbyMatcher = NEARBY_PHRASE_PATTERN.matcher(normalized);
        if (nearbyMatcher.find()) {
            return stripNoise(nearbyMatcher.group(1));
        }

        int nearbyIndex = firstIndex(normalized, "附近", "周边", "一带", "旁边", "周围");
        if (nearbyIndex > 0) {
            String prefix = stripNoise(normalized.substring(0, nearbyIndex));
            int cityIndex = findKnownCityIndex(prefix);
            if (cityIndex >= 0) {
                prefix = prefix.substring(cityIndex);
            }
            int boundary = firstBoundaryIndex(prefix);
            if (boundary > 0) {
                prefix = prefix.substring(0, boundary);
            }
            return trimLocationHint(prefix);
        }

        return null;
    }

    private String extractCityCandidate(String source) {
        if (StrUtil.isBlank(source)) {
            return null;
        }

        String normalized = stripNoise(source);
        ensureKnownCitiesLoaded();
        for (String city : knownCities) {
            if (normalized.startsWith(city)) {
                return city;
            }
        }

        Matcher matcher = CITY_SUFFIX_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return normalizeCityName(matcher.group(1));
        }
        return null;
    }

    private void ensureKnownCitiesLoaded() {
        if (citiesLoaded) {
            return;
        }
        synchronized (this) {
            if (citiesLoaded) {
                return;
            }
            if (shopService == null) {
                knownCities = List.of();
                citiesLoaded = true;
                return;
            }
            Set<String> cities = new LinkedHashSet<String>();
            for (Shop shop : shopService.list()) {
                String area = normalizeCityName(shop.getArea());
                if (StrUtil.isNotBlank(area)) {
                    cities.add(area);
                }
            }
            List<String> ordered = new ArrayList<String>(cities);
            ordered.sort((left, right) -> Integer.compare(right.length(), left.length()));
            knownCities = Collections.unmodifiableList(ordered);
            citiesLoaded = true;
        }
    }

    private int findKnownCityIndex(String source) {
        if (StrUtil.isBlank(source)) {
            return -1;
        }
        ensureKnownCitiesLoaded();
        int bestIndex = -1;
        int bestLength = -1;
        for (String city : knownCities) {
            int index = source.indexOf(city);
            if (index >= 0 && city.length() > bestLength) {
                bestIndex = index;
                bestLength = city.length();
            }
        }
        return bestIndex;
    }

    private int firstBoundaryIndex(String source) {
        if (StrUtil.isBlank(source)) {
            return -1;
        }
        int boundary = -1;
        for (String token : PHRASE_BOUNDARIES) {
            int index = source.indexOf(token);
            if (index > 0 && (boundary == -1 || index < boundary)) {
                boundary = index;
            }
        }
        return boundary;
    }

    private String stripLeadingCity(String phrase, String city) {
        if (StrUtil.isBlank(phrase) || StrUtil.isBlank(city)) {
            return phrase;
        }
        return phrase.startsWith(city) ? phrase.substring(city.length()) : phrase;
    }

    private String trimLocationHint(String value) {
        String normalized = stripNoise(value);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }

        String current = normalized;
        while (current.length() > 0) {
            String shorter = current
                    .replaceAll("(附近|周边|一带|旁边|周围)$", "")
                    .replaceAll("(有什么|有啥|哪里|哪儿|推荐)$", "")
                    .trim();
            if (shorter.equals(current)) {
                break;
            }
            current = shorter;
        }

        if (isInvalidLocation(current)) {
            return null;
        }
        return StrUtil.emptyToNull(current);
    }

    private String stripNoise(String value) {
        String normalized = normalizeMessage(value);
        for (String prefix : NOISE_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        return normalized;
    }

    private String normalizeMessage(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("，", "")
                .replace("。", "")
                .replace("？", "")
                .replace("！", "")
                .replace("、", "")
                .replace(",", "")
                .replace(".", "")
                .replace("?", "")
                .replace("!", "")
                .replace(" ", "")
                .trim();
    }

    private String normalizeCityName(String city) {
        if (StrUtil.isBlank(city)) {
            return null;
        }
        String normalized = city.trim();
        normalized = normalized.replace("特别行政区", "")
                .replace("自治区", "")
                .replace("自治州", "")
                .replace("地区", "")
                .replace("盟", "")
                .replace("市", "");
        return StrUtil.emptyToNull(normalized);
    }

    private boolean isInvalidLocation(String value) {
        if (StrUtil.isBlank(value)) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        for (String term : INVALID_LOCATION_TERMS) {
            if (normalized.equals(term) || normalized.contains(term)) {
                return true;
            }
        }
        return normalized.length() < 2;
    }

    private int firstIndex(String source, String... tokens) {
        int bestIndex = -1;
        for (String token : tokens) {
            int index = source.indexOf(token);
            if (index >= 0 && (bestIndex == -1 || index < bestIndex)) {
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    public record ParsedLocation(String city, String locationHint) {
        private static ParsedLocation empty() {
            return new ParsedLocation(null, null);
        }
    }
}
