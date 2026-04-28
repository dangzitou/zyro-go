package com.hmdp.service.impl;

import com.hmdp.ai.LocationTextParser;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPlanningServiceImplTest {

    @Test
    void shouldFallbackToRecommendationPlanWhenPlannerDisabled() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setEnabled(false);

        AgentPlanningServiceImpl service = new AgentPlanningServiceImpl(
                aiProperties,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class)
        );

        AgentExecutionPlan plan = service.plan("推荐附近有券的火锅");

        assertEquals("recommendation", plan.getIntent());
        assertTrue(plan.getPreferredTools().contains("recommend_shops"));
        assertEquals("compare_candidates", plan.getReasoningFocus());
    }

    @Test
    void shouldParseGenericCityAndLocationWithoutHardcodedExamples() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setEnabled(false);
        IShopService shopService = mock(IShopService.class);
        Shop guangzhou = new Shop();
        guangzhou.setArea("广州市");
        Shop xiamen = new Shop();
        xiamen.setArea("厦门市");
        when(shopService.list()).thenReturn(List.of(guangzhou, xiamen));

        AgentPlanningServiceImpl service = new AgentPlanningServiceImpl(
                aiProperties,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class),
                new LocationTextParser(shopService)
        );

        AgentExecutionPlan guangzhouPlan = service.plan("帮我推荐广州正佳附近好吃不贵的餐厅");
        AgentExecutionPlan xiamenPlan = service.plan("帮我看看厦门SM附近有没有适合约会的餐厅");

        assertEquals("广州", guangzhouPlan.getCity());
        assertEquals("正佳", guangzhouPlan.getLocationHint());
        assertEquals("厦门", xiamenPlan.getCity());
        assertEquals("SM", xiamenPlan.getLocationHint());
    }

    @Test
    void shouldCaptureBudgetNearbyNegationAndFastFoodSignals() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setEnabled(false);
        IShopService shopService = mock(IShopService.class);
        Shop guangzhou = new Shop();
        guangzhou.setArea("广州市");
        when(shopService.list()).thenReturn(List.of(guangzhou));

        AgentPlanningServiceImpl service = new AgentPlanningServiceImpl(
                aiProperties,
                new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.client.ChatClient.Builder.class),
                new LocationTextParser(shopService)
        );

        AgentExecutionPlan fastFoodPlan = service.plan("五山附近!!! 30以内!! 快餐!!");
        AgentExecutionPlan excludedPlan = service.plan("广州正佳附近不要火锅，想吃点清淡的，两个人，80以内");
        AgentExecutionPlan genericPlan = service.plan("给我找个附近吃饭的地方");

        assertEquals("recommendation", fastFoodPlan.getIntent());
        assertNull(fastFoodPlan.getCity());
        assertEquals("五山", fastFoodPlan.getLocationHint());
        assertEquals(Long.valueOf(30L), fastFoodPlan.getBudgetMax());
        assertTrue(fastFoodPlan.getPreferredTools().contains("recommend_shops"));

        assertEquals("recommendation", excludedPlan.getIntent());
        assertEquals("广州", excludedPlan.getCity());
        assertEquals(Integer.valueOf(2), excludedPlan.getPartySize());
        assertEquals(Long.valueOf(80L), excludedPlan.getBudgetMax());
        assertTrue(excludedPlan.getExcludedCategories().contains("火锅"));
        assertEquals("餐厅", excludedPlan.getCategory());
        assertEquals("清淡", excludedPlan.getSubcategory());

        assertNull(genericPlan.getLocationHint());
    }
}
