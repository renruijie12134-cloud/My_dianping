package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    // 业务名，用于生成锁的key，不同的业务，你不能加同一把锁，这就得给不同的业务设置不同名字的锁
    // 下面这两个参数得让用户传过来，我不能自己的定，尤其是stringRedisTemplate
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final String KEY_PREFIX = "lock:";

    // 解锁Lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        // 初始化
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 设置脚本位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置脚本返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 构造函数
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识以充当锁的值，
        // 值不能随便设置，因为会有特殊情况，第一个线程执行到删除锁之前，key过期了
        // 其他线程就进来执行操作，这样就会把其他线程的锁给删了，这个值会用来比较是否是该线程的锁。具体可以看谷粒商城p158
        // value是防止释放锁的时候造成误删，总不能线程2的锁被线程1删了吧
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 这么写的目的是防止自动拆箱导致的空指针异常
        return Boolean.TRUE.equals(success);


    }

    @Override
    // 基于Lua脚本释放锁
    public void unlock() {
        // 调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }

//    @Override
//    public void unlock() {
//        // 获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取锁中的标识
//        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 判断锁是否属于当前线程
//        if (threadId.equals(lockId)) {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
