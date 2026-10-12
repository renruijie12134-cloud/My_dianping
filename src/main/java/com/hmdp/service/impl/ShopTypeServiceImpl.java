package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;




    /**
     * 店铺分类查询，用于展示首页头部店铺分类
     * @return
     */
    @Override
    public Result queryList() {
        // 定义key
        String key = "cache:shop-type";
        // 1.查询redis看是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.如果存在，则直接返回
        if (StrUtil.isNotBlank(json)) {
            List<ShopType> typeList = JSONUtil.toList(json, ShopType.class);
            log.info("使用缓存cache:shop-type");
            return Result.ok(typeList);
        }
        // 3.如果不存在，则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 4.如果数据库查询结果为空，则返回错误
        if (typeList == null) {
            return Result.fail("店铺分类查询失败");
        }
        // 5.如果数据库存在，将数据库查询结果写入redis,设置缓存时间为 30分钟
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), 30, TimeUnit.MINUTES);
        log.info("将数据写入redis");

        // 返回
        return Result.ok(typeList);
    }

}
