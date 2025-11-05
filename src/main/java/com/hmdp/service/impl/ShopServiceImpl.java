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
import com.hmdp.utils.CacheClient;
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
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {

//        Shop shop = cacheClient.queryWithPassThrough("cache:shop:", id, Shop.class, this::getById, 10L, TimeUnit.MINUTES);
        //Shop shop = cacheClient.queryWithMutexLock("cache:shop:", id, Shop.class, this::getById, 10L, TimeUnit.MINUTES, "lock:shop:");
        Shop shop = cacheClient.queryWithLogicalExpire("cache:shop:", id, Shop.class, this::getById, 10L, TimeUnit.MINUTES, "lock:shop:");
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
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
