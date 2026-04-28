package com.hmdp.ai;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocationTextParserTest {

    @Test
    void shouldExtractCityAndLocationConservatively() {
        IShopService shopService = mock(IShopService.class);
        Shop gz = new Shop();
        gz.setArea("广州市");
        Shop xm = new Shop();
        xm.setArea("厦门市");
        when(shopService.list()).thenReturn(List.of(gz, xm));

        LocationTextParser parser = new LocationTextParser(shopService);

        LocationTextParser.ParsedLocation zhengjia = parser.parse("帮我推荐广州正佳附近好吃不贵的餐厅");
        LocationTextParser.ParsedLocation sm = parser.parse("厦门SM周边有没有平价一点的咖啡店");
        LocationTextParser.ParsedLocation noisy = parser.parse("给我找个附近吃饭的地方");
        LocationTextParser.ParsedLocation shortNearby = parser.parse("五山附近!!! 30以内!! 快餐!!");

        assertEquals("广州", zhengjia.city());
        assertEquals("正佳", zhengjia.locationHint());
        assertEquals("厦门", sm.city());
        assertEquals("SM", sm.locationHint());
        assertNull(noisy.locationHint());
        assertNull(shortNearby.city());
        assertEquals("五山", shortNearby.locationHint());
    }
}
