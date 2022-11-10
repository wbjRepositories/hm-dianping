package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTE = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTE.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String  queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.orders
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        //2.1如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    //3.解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功
                    handleVoucherOrder(voucherOrder);
                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

//    //创建阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    //获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户id
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断锁是否获取成功
        if (!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIDWorker.nextId("order");
        //1.执行lua脚本
        long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId));
        //2.判断结果是否为0
        if (result != 0){
            //2.1不为0，代表没有购买资格
            return Result.fail(result == 1?"库存不足":"不能重复下单");
        }
        //3.获取代理对象
        //proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//        //1.执行lua脚本
//        long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        //2.判断结果是否为0
//        if (result != 0){
//            //2.1不为0，代表没有购买资格
//            return Result.fail(result == 1?"库存不足":"不能重复下单");
//        }
//        //2.2为零，有购买资格，把下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //2.3订单id
//        long orderId = redisIDWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //2.4用户id
//        voucherOrder.setUserId(userId);
//        //2.5代金卷id
//        voucherOrder.setVoucherId(voucherId);
//        //2.6创建阻塞队列
//        orderTasks.add(voucherOrder);
//        //3.获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //4.返回订单id
//        return Result.ok(orderId);
//    }


//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//
//        //判断库存是否充足
//        if (voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        //判断锁是否获取成功
//        if (!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //用户id
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0){
            log.error("该用户已经购买过了");
            return;
        }

        //扣减库存
        boolean success = seckillVoucherService
                .update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if (!success){
            //扣减视频
            log.error("库存不足");
            return;
        }
        //保存订单
        save(voucherOrder);
    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0){
//            return Result.fail("该用户已经购买过了");
//        }
//
//
//        //扣减库存
//        boolean success = seckillVoucherService
//                .update().setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock",0)
//                .update();
//        if (!success){
//            //扣减视频
//            return Result.fail("库存不足");
//        }
//
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //订单id
//        Long orderId = redisIDWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        voucherOrder.setUserId(userId);
//        //代金卷id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//    }
}
