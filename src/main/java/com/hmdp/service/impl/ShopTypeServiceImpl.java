package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {

        String shopTypeListJson = stringRedisTemplate.opsForValue().get("cache:shop:list");
        List<ShopType> shopTypeList = null;

        if (shopTypeListJson != null) {
            shopTypeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return Result.ok(shopTypeList);
        }

        shopTypeList = query().list();


        if(shopTypeList != null)
        {
            String jsonStr = JSONUtil.toJsonStr(shopTypeList);
            stringRedisTemplate.opsForValue().set("cache:shop:list", jsonStr, 30, TimeUnit.MINUTES);
        }

        else{
            return Result.fail("没有数据");
        }

        return Result.ok(shopTypeList);
    }

}
