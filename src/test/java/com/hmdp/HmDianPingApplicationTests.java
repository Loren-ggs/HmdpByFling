package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;


import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;
    @Test
    public void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(2L);
        cacheClient.setWithLogicalExpire("cache:shop:" + 2L,shop,10L, TimeUnit.SECONDS);

    }



}
