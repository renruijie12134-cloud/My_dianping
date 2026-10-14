package com.hmdp.service.impl;

import org.springframework.data.redis.domain.geo.GeoReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 处理缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 使用CacheClient工具类处理缓存穿透  有了它就不需要 public Shop queryWithPassThrough(Long id)
        // Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 互斥锁处理缓存击穿
        //Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        // 使用CacheClient工具类处理缓存穿透  有了它就不需要 public Shop queryWithLogicalExpire(Long id)
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);


        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 6.返回结果
        return Result.ok(shop);
    }

    /**
     * 封装缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        // 采用缓存空值的方法来防止缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从Redis中查询店铺缓存 -- 可能获取到的是店铺信息，也可能是空值
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.如果返回的是店铺，返回店铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 如果返回的是空值，返回错误信息
        if (shopJson != null) {
            return null;
        }
        // 3.现在可判断店铺在数据库中是否存在
        Shop shop = getById(id);
        // 4.数据库不存在，返回错误信息
        if (shop == null) {
            // 缓存中不存在，数据库中也不存在，向缓存里放空值
            stringRedisTemplate.opsForValue().set(key, "",  RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库存在，返回店铺信息,并写入Redis,设置缓存时间为 30分钟
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6.返回结果
        return shop;
    }

    /**
     * 封装互斥锁解决缓存击穿
      * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        // 采用缓存空值的方法来防止缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从Redis中查询店铺缓存 -- 可能获取到的是店铺信息，也可能是空值
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.如果返回的是店铺，返回店铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 如果返回的是空值，返回错误信息
        if (shopJson != null) {
            return null;
        }

        // 3.现在既未命中店铺也未命中空值，这就是击穿

        // 4.实现缓存击穿
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop=null;
        boolean isLocked = false;
        try{
            // 4.1 获取互斥锁
            isLocked = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLocked) {
                // 4.3 获取失败，则休眠重试
                Thread.sleep(100);
                return queryWithMutex(id);
            }
            // 4.4 获取成功，再次查询 Redis，有值则返回
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 如果返回的是空值，返回错误信息  穿透问题
            if (shopJson != null) {
                return null;
            }

            // 4.5查询数据库，并写入Redis,设置缓存时间为 30分钟
            shop = getById(id);
            // 模拟重建延时
            Thread.sleep(200);
            // 5.数据库不存在，返回错误信息
            if (shop == null) {
                // 缓存中不存在，数据库中也不存在，向缓存里放空值
                stringRedisTemplate.opsForValue().set(key, "",  RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.数据库存在，返回店铺信息,并写入Redis,设置缓存时间为 30分钟
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            if (isLocked) {
                unlock(lockKey);
            }
        }
        // 8.返回结果
        return shop;
    }

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    // 先建立一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        // 采用缓存空值的方法来防止缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从Redis中查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在，如果不存在，返回null,,说明它都不是 热点id
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 3.命中，说明这是一个热点key,将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4.判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 4.1 未过期，返回店铺信息
            return shop;
        }
        // 4.2 过期了，需要缓存重建
        // 5. 获取互斥锁----依旧是利用redis是单线程的特性获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            // 5.1 取锁成功，开启新线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try{
                    // 重建缓存
                    this.saveShop2Redis(id, 30L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }

            });

        }
        // 5.2 取锁失败，返回过期的店铺信息
        return shop;
    }

    /**
     * 用于
     * 预热，先把热点的商铺数据放入Redis
     * 新线程的缓存重建
     */
    public void saveShop2Redis(Long id, Long expireMinutes) {
        // 模拟缓存重建的延迟
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 查询店铺数据
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 更新商铺信息
     */
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.更新数据库
        boolean result = updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }


    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));

            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3. 查询 Redis，按照距离排序、分页。结果：shopId、distance
        String key = "shop:geo:" + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo()
                        .search(
                                key,
                                GeoReference.fromCoordinate(x, y),
                                new Distance(5000),
                                RedisGeoCommands.GeoSearchCommandArgs
                                        .newGeoSearchArgs()
                                        .includeDistance()
                                        .limit(end)
                        );

        // 4. 解析出 id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list =
                results.getContent();

        if(list.size()<=from){
            // 根本没有下一页，直接诶返回
            return Result.ok(Collections.emptyList());
        }

        // 4.1 截取 from ~ end 的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());

        list.stream()
                .skip(from)
                .forEach(result -> {
                    // 4.2 获取店铺 id
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));

                    // 4.3 获取距离
                    Distance distance = result.getDistance();
                    distanceMap.put(shopIdStr, distance);
                });

        // 5. 根据 id 查询 Shop
        String idStr = StrUtil.join(",", ids);

        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();

        for (Shop shop : shops) {
            shop.setDistance(
                    distanceMap.get(shop.getId().toString()).getValue()
            );
        }

        // 6. 返回
        return Result.ok(shops);
    }

}
