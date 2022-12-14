package com.hmdp.service.impl;

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
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient client;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        Shop shop = client.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithPassMutex(id);
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商铺缓存
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        //查询是否存在
        if (StrUtil.isBlank(shopJSON)){
            //不存在，直接返回
           return null;
        }
        //把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回店铺信息
            return shop;
        }
        //已过期
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断获取锁是否成功
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }

            });
        }


        //从redis中查询商铺缓存,二次检查
        shopJSON = stringRedisTemplate.opsForValue().get(key);
        //查询是否存在
        if (StrUtil.isBlank(shopJSON)){
            //不存在，直接返回
            return null;
        }
        //把json反序列化为对象
        redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        data = (JSONObject) redisData.getData();
        shop = JSONUtil.toBean(data, Shop.class);
        expireTime = redisData.getExpireTime();
        //判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回店铺信息
            return shop;
        }


        //店铺存在
        //将数据缓存到redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    //互斥锁解决缓存击穿
    public Shop queryWithPassMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商铺缓存
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        //查询是否存在
        if (StrUtil.isNotBlank(shopJSON)){
            //存在，直接返回
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        //存在，但值是""
        if(shopJSON != null){
            return null;
        }

        String lockKey = null;
        Shop shop = null;
        try {
            lockKey = LOCK_SHOP_KEY + id;
            //尝试获取互斥锁
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                //获取互斥锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithPassMutex(id);
            }
            //再次检查redis缓存是否存在
            shopJSON  = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJSON)){
                //存在，直接返回
                shop = JSONUtil.toBean(shopJSON, Shop.class);
                return shop;
            }
            //存在，但值是""
            if(shopJSON != null){
                return null;
            }

            //获取互斥锁成功，并且确定了redis不存在

            //不存在,根据id查询数据库
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            //判断数据中是否存在
            if (shop == null){
                //将空字符串写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //店铺存在
            //将数据缓存到redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }




    //解决缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询商铺缓存
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        //查询是否存在
        if (StrUtil.isNotBlank(shopJSON)){
            //存在，直接返回
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        //存在，但值是""
        if(shopJSON != null){
            return null;
        }

        //不存在,根据id查询数据库
        Shop shop = getById(id);
        //判断数据中是否存在
        if (shop == null){
            //将空字符串写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //店铺存在
        //将数据缓存到redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }



    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 1, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id , JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null){
            // 不需要坐标查询根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3.查询redis、按照距离排序分页。结果shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        // 4.1截取from ~ end 的数据
        ArrayList<Long> ids = new ArrayList<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        // 5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
