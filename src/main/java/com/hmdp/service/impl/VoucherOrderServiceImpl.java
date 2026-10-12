package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    // Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 设置脚本位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 设置脚本返回类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 创建代理对象，有主线程操作seckillVoucher函数进行初始化，
    // 由主线程的子线程操作阻塞队列中的数据进数据库时使用
    private IVoucherOrderService proxy;
    // 创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct  // 表示初始化方法，PostConstruct注解表示该方法在依赖注入完成后执行
    public void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 处理阻塞队列中的订单
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    // 3.获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 4.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常{}", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户id
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        // 现在加入的是一个基于Redisson的分布式锁
        // 这个锁可以不加，因为主线程已经通过Lua脚本实现了分布式锁
        // 现在加入锁的目的是为了兜底，万一Lua脚本执行出错，可以使用这个锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLocked = lock.tryLock();
        // 判断是否获取成功
        if(!isLocked){
            log.error("请勿重复下单！");
            return ;
        }
        try {
            // 获取代理对象 单纯的写下面这两句代码是获取不到代理对象的，因为我现在是一个子线程
            // 但是createVoucherOrder在主线程中调用，所以这里获取不到代理对象
            // 解决方法是将在本类中创建 private IVoucherOrderService proxy; 属性
            // 由主线程初始化，随后，我们子线程再使用proxy对象
            // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //return proxy.createVoucherOrder(voucherId);
            proxy.createVoucherOrder(voucherOrder);

        } finally {
            lock.unlock();
        }

    }

    /**
     * 优惠券秒杀下单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId){
        // 1:执行Lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
        // 2:判断结果是否为0
        int r = result.intValue();
        // 2.1:判断结果不是0
        if(r != 0){
            return Result.fail(result == 1 ? "库存不足！" : "请勿重复下单！");
        }
        // 2.2:判断结果是0 用户有购买资格，将其加入阻塞队列
        // 2.2.1 先给这个用户订单设置一个订单id
        long orderId = redisIdWorker.nextId("order");

        // 2.2.2 TODO 将用户id和订单id设置到阻塞队列中
        // 主要写三个值：订单id、用户id、优惠券id
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.2.3全局id获取订单id
        voucherOrder.setId(orderId);
        // 2.2.4 从TreadLocal获取用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 2.2.5 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 2.2.6 将订单加入阻塞队列
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        orderTasks.add(voucherOrder);
        // 至此，主线程的订单创建任务完成


        // 3.返回
        return Result.ok(orderId);

    }




    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 4.1 实现一人一单，
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
            log.error("用户已经购买过该优惠券！");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if(!success){
            log.error("库存不足！");
        }

        save(voucherOrder);
        
    }





    /**
     * 没有使用异步的实现方法

    public Result seckillVoucher(Long voucherId){
        // 使用CAS法解决库存超卖问题
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if(voucher.getStock() <= 0){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
//        适用于单机，一旦处在集群位置，就不能使用了
//        synchronized (userId.toString().intern()) {
//            // 获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        // 分布式锁
        // 创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock( "order:" + userId,stringRedisTemplate);
        // 基于redisson的分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLocked = lock.tryLock();
        if(!isLocked){
            return Result.fail("请勿重复下单！");
        }
        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }
**/

    /**没有使用异步的createVoucherOrder
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 4.1 实现一人一单，
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            return Result.fail("用户已经购买过该优惠券！");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足！");
        }
        //6.创建订单
        // 主要写三个值：订单id、用户id、优惠券id
        VoucherOrder voucherOrder = new VoucherOrder();
        // 全局id获取订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 从TreadLocal获取用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //7.返回订单id
        return Result.ok(orderId);

    }
    **/
}
