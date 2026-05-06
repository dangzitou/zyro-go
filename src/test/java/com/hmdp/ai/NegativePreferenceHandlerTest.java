package com.hmdp.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NegativePreferenceHandlerTest {

    private NegativePreferenceHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NegativePreferenceHandler();
    }

    @Test
    void testExtractNegativePreferences_Spicy() {
        String message = "推荐一家不要太辣的川菜";
        List<String> preferences = handler.extractNegativePreferences(message);
        
        assertTrue(preferences.contains("太辣"));
    }

    @Test
    void testExtractNegativePreferences_Multiple() {
        String message = "找个不要太辣、别太吵、不要太贵的餐厅";
        List<String> preferences = handler.extractNegativePreferences(message);
        
        assertTrue(preferences.contains("太辣"));
        assertTrue(preferences.contains("太吵"));
        assertTrue(preferences.contains("太贵"));
        assertEquals(3, preferences.size());
    }

    @Test
    void testExtractNegativePreferences_NoPreferences() {
        String message = "推荐一家好吃的餐厅";
        List<String> preferences = handler.extractNegativePreferences(message);
        
        assertTrue(preferences.isEmpty());
    }

    @Test
    void testMatchesNegativePreference_Spicy() {
        boolean matches = handler.matchesNegativePreference(
                "麻辣香锅", "天河区", "体育西路", "太辣"
        );
        
        assertTrue(matches);
    }

    @Test
    void testMatchesNegativePreference_NotSpicy() {
        boolean matches = handler.matchesNegativePreference(
                "清淡小炒", "天河区", "体育西路", "太辣"
        );
        
        assertFalse(matches);
    }

    @Test
    void testMatchesNegativePreference_Noisy() {
        boolean matches = handler.matchesNegativePreference(
                "热闹大排档", "天河区", "体育西路", "太吵"
        );
        
        assertTrue(matches);
    }

    @Test
    void testMatchesDistancePreference_TooFar() {
        boolean matches = handler.matchesDistancePreference(6000.0, "太远");
        
        assertTrue(matches);
    }

    @Test
    void testMatchesDistancePreference_NotTooFar() {
        boolean matches = handler.matchesDistancePreference(2000.0, "太远");
        
        assertFalse(matches);
    }

    @Test
    void testMatchesPricePreference_TooExpensive() {
        boolean matches = handler.matchesPricePreference(200L, "太贵", 100L);
        
        assertTrue(matches);
    }

    @Test
    void testMatchesPricePreference_WithinBudget() {
        boolean matches = handler.matchesPricePreference(80L, "太贵", 100L);
        
        assertFalse(matches);
    }

    @Test
    void testGetPositiveSuggestions() {
        List<String> suggestions = handler.getPositiveSuggestions("太辣");
        
        assertTrue(suggestions.contains("清淡"));
        assertTrue(suggestions.contains("不辣"));
    }

    @Test
    void testGetSupportedPreferences() {
        var supported = handler.getSupportedPreferences();
        
        assertTrue(supported.contains("太辣"));
        assertTrue(supported.contains("太吵"));
        assertTrue(supported.contains("太贵"));
        assertTrue(supported.contains("太远"));
        assertTrue(supported.contains("太油腻"));
        assertTrue(supported.contains("太甜"));
        assertTrue(supported.contains("太挤"));
        assertTrue(supported.contains("太慢"));
    }
}
