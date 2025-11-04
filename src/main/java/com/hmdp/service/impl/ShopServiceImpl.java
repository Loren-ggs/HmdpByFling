package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.injector.methods.UpdateById;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.apache.ibatis.annotations.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
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
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {

        //Shop shop = queryWithPassThrough(id);
        //Shop shop = queryWithMutexLock(id);
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //给缓存重建设一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //基于逻辑过期解决缓存击穿
    private Shop queryWithLogicalExpire(Long id) {
        {
            String key = "cache:shop:" + id;
            //从redis查id
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //为空则返回
            if(StrUtil.isBlank(shopJson)){
                return null;

            }

            //有就判断缓存是否过期
            //先把json转为对象
            RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
            Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            LocalDateTime expireTime = redisData.getExpireTime();
            //判断是否过期，未过期则直接返回店铺信息
            if (expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }
            //过期则要缓存重建
            //开始缓存重建，获取互斥锁
            String lockKey = "lock:shop:" + id;
            Boolean isLock = tryLock(lockKey);

            //判断是否获取锁成功
            //获取锁成功，开启一个新线程，实现缓存重建（由id查数据库，返回店铺信息，写入redis，设置逻辑过期时间，释放锁）
            if (isLock){
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        //缓存重建
                        this.saveShopToRedis(id, 10L);//测试用，先设个10s过期看看效果

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        //释放锁
                        unLock(lockKey);
                    }
                });
            }
            //获取锁失败，返回过期的商铺信息
            return shop;
        }
    }


    //记录null值解决缓存穿透
    private Shop queryWithPassThrough(Long id) {
        {
            String key = "cache:shop:" + id;
            //从redis查id
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //有就返回
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);

            }
            //命中的是否是null值
            if (shopJson != null){
                return null;
            }

            //没有就查数据库
            Shop shop = getById(id);

            //数据库里没有则报错，对redis中写入空值，防止缓存击穿
            if (shop == null){
                stringRedisTemplate.opsForValue().set(key, "",2,TimeUnit.MINUTES);
                return null;

            }

            //有数据存到redis并返回
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, jsonStr,30, TimeUnit.MINUTES);


            return shop;
        }
    }
//用互斥锁解决缓存击穿
    private Shop queryWithMutexLock(Long id) {
        {
            String key = "cache:shop:" + id;
            //从redis查id
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            //有就返回
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);

            }
            //命中的是否是null值
            if (shopJson != null) {
                return null;
            }

            //实现缓存重建
            Shop shop;
            String lockKey = "lock:shop:" + id;
            try {

                Boolean isLock = tryLock(lockKey);
                if (!isLock) {
                    //等待
                    Thread.sleep(50);
                    //递归重试
                    return queryWithMutexLock(id);

                }

                //获取锁成功，查数据库
                shop = getById(id);

                //数据库里没有则报错，对redis中写入空值，防止缓存击穿
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                    return null;

                }

                //有数据存到redis并返回
                String jsonStr = JSONUtil.toJsonStr(shop);
                stringRedisTemplate.opsForValue().set(key, jsonStr, 30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }


            return shop;
        }
    }
    private boolean tryLock(String key) {
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}
    private void unLock(String key) {
    stringRedisTemplate.delete(key);
}
    //缓存重建
    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {

        Shop shop = getById(id);

        Thread.sleep(200);//模拟缓存重建延迟

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set("cache:shop:" + id, JSONUtil.toJsonStr(redisData));
}
}
