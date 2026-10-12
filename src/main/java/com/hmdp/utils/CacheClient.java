package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    /**
     * 用于
     * 预热，先把热点的商铺数据放入Redis
     * 新线程的缓存重建
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 封装缓存穿透
     * @param id
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        // 采用缓存空值的方法来防止缓存穿透
        String key =keyPrefix+ id;
        // 1.从Redis中查询店铺缓存 -- 可能获取到的是店铺信息，也可能是空值
        String Json = stringRedisTemplate.opsForValue().get(key);
        // 2.如果返回的是店铺，返回店铺信息
        if (StrUtil.isNotBlank(Json)) {
            return  JSONUtil.toBean(Json, type);
        }
        // 如果返回的是空值，返回错误信息
        if (Json != null) {
            return null;
        }
        // 3.现在可判断店铺在数据库中是否存在
        R r= dbFallback.apply(id);
        // 4.数据库不存在，返回错误信息
        if (r == null) {
            // 缓存中不存在，数据库中也不存在，向缓存里放空值
            stringRedisTemplate.opsForValue().set(key, "",  RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库存在，返回店铺信息,并写入Redis,设置缓存时间为 30分钟
        this.set(key, r, time, unit);
        // 6.返回结果
        return r;
    }


    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    // 先建立一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        // 采用缓存空值的方法来防止缓存穿透
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从Redis中查询店铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在，如果不存在，返回null,,说明它都不是 热点id
        if (StrUtil.isBlank(Json)) {
            return null;
        }

        // 3.命中，说明这是一个热点key,将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4.判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 4.1 未过期，返回店铺信息
            return r;
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
                   // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }

            });

        }
        // 5.2 取锁失败，返回过期的店铺信息
        return r;
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

}
