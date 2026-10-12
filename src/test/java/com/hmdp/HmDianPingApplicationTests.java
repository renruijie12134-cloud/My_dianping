package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void testSaveShop2Redis() {
        shopService.saveShop2Redis(1L,35L);
    }

    @Test
    public void testCacheClient() {
        Shop shop = new Shop();
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, shop, 30L, TimeUnit.MINUTES);

    }

    // 为了测试RedisIdWorker是否能正常工作，我们通过线程池准备500个线程
    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();

        long end = System.currentTimeMillis();

        System.out.println("time = " + (end - begin));
    }


}
