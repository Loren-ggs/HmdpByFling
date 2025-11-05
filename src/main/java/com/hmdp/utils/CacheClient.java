package com.hmdp.utils;


import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //存空值解决缓存穿透
    public <R,ID> R queryWithPassThrough(String preFixKey, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        {
            String key = preFixKey + id;
            //从redis查id
            String Json = stringRedisTemplate.opsForValue().get(key);
            //有就返回
            if(StrUtil.isNotBlank(Json)){
                return JSONUtil.toBean(Json, type);

            }
            //命中的是否是null值
            if (Json != null){
                return null;
            }

            //没有就查数据库
            R r = dbFallback.apply(id);

            //数据库里没有则报错，对redis中写入空值，防止缓存击穿
            if (r == null){
                stringRedisTemplate.opsForValue().set(key, "",2,TimeUnit.MINUTES);
                return null;

            }

            //有数据存到redis并返回
            this.set(key, r, time, unit);


            return r;
        }
    }


    //================= 互斥锁击穿封装 =================
    public <R,ID> R queryWithMutexLock(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit,
            String lockKeyPrefix
    ) {
        String key = keyPrefix + id;
        // 1. 读缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 命中空值
            return null;
        }

        // 2. 缓存重建（互斥）
        String lockKey = lockKeyPrefix + id;
        try {
            Boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 失败则稍等并递归重试
                Thread.sleep(50);
                return queryWithMutexLock(keyPrefix, id, type, dbFallback, time, unit, lockKeyPrefix);
            }
            // 获取锁成功，查数据库
            R r = dbFallback.apply(id);
            if (r == null) {
                // 写入空值，短期过期，防穿透
                stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                return null;
            }
            // 写入正常缓存
            this.set(key, r, time, unit);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
    }

    //================= 逻辑过期击穿封装 =================
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit,
            String lockKeyPrefix
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 未命中缓存
            return null;
        }
        // 命中缓存，先反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 1) 未过期则直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 2) 过期则尝试异步重建
        String lockKey = lockKeyPrefix + id;
        Boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    // 逻辑过期写入
                    setWithLogicalExpire(keyPrefix + id, fresh, time, unit);
                } catch (Exception e) {
                    log.error("Cache rebuild error", e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 返回旧值
        return r;
    }

    //================= 简单分布式锁 =================
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
