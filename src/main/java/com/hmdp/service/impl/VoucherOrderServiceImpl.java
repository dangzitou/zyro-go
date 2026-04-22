package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * VoucherOrderServiceImpl绫荤殑浠ｇ悊瀵硅薄
     * 灏嗕唬鐞嗗璞＄殑浣滅敤鍩熻繘琛屾彁鍗囷紝鏂逛究瀛愮嚎绋嬪彇鐢?
     */
    private IVoucherOrderService proxy;



    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //鑾峰彇闃熷垪涓殑璁㈠崟淇℃伅
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //鍒ゆ柇鏄惁鑾峰彇鎴愬姛
                    if(list == null || list.isEmpty()){
                        //濡傛灉鑾峰彇澶辫触锛岃鏄庨槦鍒椾腑娌℃湁璁㈠崟淇℃伅锛岀户缁笅涓€娆″惊鐜?
                        continue;
                    }
                    //濡傛灉鑾峰彇鎴愬姛锛屽垱寤鸿鍗曚俊鎭?
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //鍒涘缓璁㈠崟
                    handleVoucherOrder(voucherOrder);
                    //纭娑堟伅宸叉秷璐?
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e) {
                    log.error(e.getMessage());
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    //鑾峰彇pending-list涓殑璁㈠崟淇℃伅
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    //鍒ゆ柇鏄惁鑾峰彇鎴愬姛
                    if(list == null || list.isEmpty()){
                        //濡傛灉鑾峰彇澶辫触锛岃鏄巔ending-list涓病鏈夎鍗曚俊鎭紝缁撴潫寰幆
                        break;
                    }
                    //濡傛灉鑾峰彇鎴愬姛锛屽垱寤鸿鍗曚俊鎭?
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //鍒涘缓璁㈠崟
                    handleVoucherOrder(voucherOrder);
                    //纭娑堟伅宸叉秷璐?
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e) {
                    log.error(e.getMessage());
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //鑾峰彇闃熷垪涓殑璁㈠崟淇℃伅
                    VoucherOrder voucherOrder = orderTasks.take();
                    //鍒涘缓璁㈠崟
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //鍒涘缓閿佸璞?
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //鑾峰彇閿?
        boolean isLock = lock.tryLock();
        //鍒ゆ柇鏄惁鑾峰彇閿佹垚鍔?
        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            // 鍒涘缓璁㈠崟锛堜娇鐢ㄤ唬鐞嗗璞¤皟鐢紝鏄负浜嗙‘淇濅簨鍔＄敓鏁堬級
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //閲婃斁閿?
            lock.unlock();
        }
    }

    //鍔犺浇lua鑴氭湰
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result secKill(Long voucherId){
        //鐢ㄦ埛id
        Long userId = UserHolder.getUser().getId();
        //璁㈠崟id
        long orderId = redisIdWorker.nextId("order");
        //鎵цlua鑴氭湰
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //鍒ゆ柇缁撴灉鏄惁涓?
        int r = result.intValue();
        if(r != 0){
            //涓嶄负0锛屼唬琛ㄦ病鏈夎喘涔拌祫鏍?
            return Result.fail(r == 1 ? "搴撳瓨涓嶈冻" : "涓嶈兘閲嶅涓嬪崟");
        }
        //鑾峰彇浠ｇ悊瀵硅薄(鍥犱负浜嬪姟鏄€氳繃浠ｇ悊瀵硅薄鏉ュ疄鐜扮殑)
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //杩斿洖璁㈠崟id
        return Result.ok(orderId);
    }

    /*@Override
    public Result secKill(Long voucherId){
        //鎵цlua鑴氭湰
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString()
        );
        //鍒ゆ柇缁撴灉鏄惁涓?
        int r = result.intValue();
        if(r != 0){
            //涓嶄负0锛屼唬琛ㄦ病鏈夎喘涔拌祫鏍?
            return Result.fail(r == 1 ? "搴撳瓨涓嶈冻" : "涓嶈兘閲嶅涓嬪崟");
        }
        //涓?锛屾湁璐拱璧勬牸锛屾妸涓嬪崟淇℃伅淇濆瓨鍒伴樆濉為槦鍒?
        VoucherOrder voucherOrder = new VoucherOrder();
        //璁㈠崟id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //浼樻儬鍒竔d
        voucherOrder.setVoucherId(voucherId);
        //鐢ㄦ埛id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        orderTasks.add(voucherOrder);
        //鑾峰彇浠ｇ悊瀵硅薄(鍥犱负浜嬪姟鏄€氳繃浠ｇ悊瀵硅薄鏉ュ疄鐜扮殑)
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //杩斿洖璁㈠崟id
        return Result.ok(orderId);
    }*/

    /*@Override
    public Result secKill(Long voucherId) {
        //鏌ヨ浼樻儬鍒告槸鍚﹀瓨鍦?
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher == null){
            return Result.fail("浼樻儬鍒镐笉瀛樺湪");
        }
        //鏌ヨ绉掓潃鏄惁宸插紑濮?
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("绉掓潃灏氭湭寮€濮?);
        }
        //鏌ヨ绉掓潃鏄惁宸茬粨鏉?
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("绉掓潃宸茬粨鏉?);
        }

        //鏌ヨ搴撳瓨鏄惁鍏呰冻
        if(voucher.getStock() < 1){
            return Result.fail("搴撳瓨涓嶈冻");
        }

        Long userId = UserHolder.getUser().getId();
        //鍒涘缓閿佸璞?
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //鑾峰彇閿?
        boolean isLock = lock.tryLock();
        //鍒ゆ柇鏄惁鑾峰彇閿佹垚鍔?
        if(!isLock){
            return Result.fail("涓嶅厑璁搁噸澶嶄笅鍗?);
        }
        try {
            //鑾峰彇浠ｇ悊瀵硅薄(鍥犱负浜嬪姟鏄€氳繃浠ｇ悊瀵硅薄鏉ュ疄鐜扮殑)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //鎵ц涓嬪崟
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //閲婃斁閿?
            lock.unlock();
        }
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //涓€浜轰竴鍗?
        Long userId = voucherOrder.getUserId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
            log.error("您已购买过一次");
        }

        //搴撳瓨鎵ｅ噺
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)//涔愯閿?
                .update();
        if(!success){
            log.error("搴撳瓨涓嶈冻");
        }
        //淇濆瓨璁㈠崟
        save(voucherOrder);
    }
}

