package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @GetMapping("list")
    public Result queryTypeList() {
        //直接到数据库查询
//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
//        从redis中查询
        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
        String shopType = stringRedisTemplate.opsForValue().get(key);
        //判读缓存中是否存在
        if (shopType != null) {
            //存在，将缓存返回,但是不知道怎么把字符串转换为list类型
            //将数据转换为json存进redis，取出来用json转换为list类型
            List<ShopType> typeList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(typeList);
        }
        //不存在，查询数据库
        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        //填入缓存中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);


        //hash类型还没做出来
//        String key = RedisConstants.CACHE_SHOPTYPE_KEY;
//        List shopType = (List)stringRedisTemplate.opsForHash().get(key, "shopType");
//        //判读缓存中是否存在
//        if (!shopType.isEmpty()) {
//            //存在，将缓存返回
//            return Result.ok(shopType);
//        }
//        //不存在，查询数据库
//        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
//        //填入缓存中
//        stringRedisTemplate.opsForHash().put(key,"shopType",typeList);
//        return Result.ok(typeList);
    }
}
